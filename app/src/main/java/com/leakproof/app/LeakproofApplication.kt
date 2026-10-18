package com.leakproof.app

import android.app.Application
import com.leakproof.app.data.local.AppDatabase
import com.leakproof.app.data.repository.MerchantRepository
import com.leakproof.app.data.repository.SubscriptionRepository
import com.leakproof.app.data.security.PreferencesManager
import com.leakproof.app.parser.DefaultMandateScreenshotParser
import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import com.leakproof.app.parser.MandateScreenshotParser
import com.leakproof.app.parser.RecurringDetector
import com.leakproof.app.parser.StatementParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LeakproofApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var subscriptionRepository: SubscriptionRepository
        private set

    lateinit var merchantRepository: MerchantRepository
        private set

    lateinit var preferencesManager: PreferencesManager
        private set

    val screenshotParser: MandateScreenshotParser by lazy { DefaultMandateScreenshotParser() }
    val statementParser: StatementParser by lazy { DefaultStatementParser() }
    val recurringDetector: RecurringDetector by lazy { DefaultRecurringDetector() }
    val mlKitOcrScanner: com.leakproof.app.data.ocr.MlKitOcrScanner by lazy {
        com.leakproof.app.data.ocr.MlKitOcrScanner(this, screenshotParser)
    }
    val statementReader: com.leakproof.app.data.statement.StatementReader by lazy {
        com.leakproof.app.data.statement.StatementReader(this, statementParser, recurringDetector)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        preferencesManager = PreferencesManager(this)
        database = AppDatabase.getInstance(this)
        subscriptionRepository = SubscriptionRepository(database.subscriptionDao())
        merchantRepository = MerchantRepository(this)

        com.leakproof.app.reminder.RenewalReminderWorker.schedule(this, preferencesManager.isNotificationsEnabled)

        // Demo data is seeded in debug builds only; release users start with an empty vault
        if (BuildConfig.DEBUG) CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!preferencesManager.isFirstRunSeeded) {
                    val currentList = subscriptionRepository.getAllSubscriptions().first()
                    if (currentList.isEmpty()) {
                        subscriptionRepository.seedInitialDataIfEmpty()
                    }
                    preferencesManager.isFirstRunSeeded = true
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        lateinit var instance: LeakproofApplication
            private set
    }
}
