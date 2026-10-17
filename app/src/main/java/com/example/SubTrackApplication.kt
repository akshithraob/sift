package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.repository.MerchantRepository
import com.example.data.repository.SubscriptionRepository
import com.example.data.security.PreferencesManager
import com.example.parser.DefaultMandateScreenshotParser
import com.example.parser.DefaultRecurringDetector
import com.example.parser.DefaultStatementParser
import com.example.parser.MandateScreenshotParser
import com.example.parser.RecurringDetector
import com.example.parser.StatementParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SubTrackApplication : Application() {

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
    val mlKitOcrScanner: com.example.data.ocr.MlKitOcrScanner by lazy {
        com.example.data.ocr.MlKitOcrScanner(this, screenshotParser)
    }
    val statementReader: com.example.data.statement.StatementReader by lazy {
        com.example.data.statement.StatementReader(this, statementParser, recurringDetector)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        preferencesManager = PreferencesManager(this)
        database = AppDatabase.getInstance(this)
        subscriptionRepository = SubscriptionRepository(database.subscriptionDao())
        merchantRepository = MerchantRepository(this)

        // Seed initial starter data if first run
        CoroutineScope(Dispatchers.IO).launch {
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
        lateinit var instance: SubTrackApplication
            private set
    }
}
