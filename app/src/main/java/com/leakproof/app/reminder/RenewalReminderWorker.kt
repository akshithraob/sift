package com.leakproof.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leakproof.app.MainActivity
import com.leakproof.app.data.local.AppDatabase
import com.leakproof.app.data.security.PreferencesManager
import com.leakproof.app.util.CurrencyFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Local-only reminders, run once a day: renewals in [DAYS_BEFORE] days, free trials ending today or
 * tomorrow, and a monthly nudge to re-import so the list stays current. Nothing leaves the device;
 * the lock-screen version of every notification is generic so merchant names and amounts stay private.
 */
class RenewalReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!canNotify(context)) return Result.success()

        val dao = try {
            AppDatabase.getInstance(context).subscriptionDao()
        } catch (_: Exception) {
            return Result.retry()
        }

        try {
            val renewals = dao.getActiveRenewingBetween(startOfDay(DAYS_BEFORE), startOfDay(DAYS_BEFORE + 1))
            if (renewals.isNotEmpty()) {
                val title = if (renewals.size == 1) "1 subscription renews in $DAYS_BEFORE days"
                else "${renewals.size} subscriptions renew in $DAYS_BEFORE days"
                post(NOTIFICATION_RENEWALS, title, renewals.joinToString(", ") { "${it.merchant} (${CurrencyFormatter.format(it.amount)})" })
            }

            // Yearly charges are the easy ones to forget: a heads-up a month ahead and again a week ahead
            for (days in listOf(YEARLY_HEADS_UP_DAYS, YEARLY_LAST_CALL_DAYS)) {
                val yearly = dao.getActiveRenewingBetween(startOfDay(days), startOfDay(days + 1))
                    .filter { it.frequency.equals("YEARLY", ignoreCase = true) }
                if (yearly.isNotEmpty()) {
                    val label = if (days >= 28) "a month" else "$days days"
                    post(
                        NOTIFICATION_YEARLY + days,
                        if (yearly.size == 1) "A yearly payment renews in $label" else "${yearly.size} yearly payments renew in $label",
                        yearly.joinToString(", ") { "${it.merchant} (${CurrencyFormatter.format(it.amount)})" } + ". Keep it or cancel it?"
                    )
                }
            }

            val trials = dao.getActiveTrialsEndingBetween(startOfDay(0), startOfDay(2))
            if (trials.isNotEmpty()) {
                val title = if (trials.size == 1) "A free trial is ending" else "${trials.size} free trials are ending"
                post(
                    NOTIFICATION_TRIALS, title,
                    trials.joinToString("; ") { "${it.merchant} converts to ${CurrencyFormatter.format(it.amount)} - cancel to avoid the charge" }
                )
            }
        } catch (_: Exception) {
            return Result.retry()
        }

        nudgeToReimport()
        com.leakproof.app.widget.LeakproofWidgetProvider.refresh(context)
        return Result.success()
    }

    private fun nudgeToReimport() {
        val prefs = PreferencesManager(context)
        val now = System.currentTimeMillis()
        val stale = prefs.lastImportAtMs > 0 && now - prefs.lastImportAtMs > REIMPORT_AFTER_MS
        val alreadyNudged = now - prefs.lastReimportNudgeAtMs < REIMPORT_AFTER_MS
        if (stale && !alreadyNudged) {
            post(
                NOTIFICATION_REIMPORT, "Refresh your subscriptions",
                "It's been a month. Import a fresh Autopay screenshot to catch new, changed or cancelled mandates."
            )
            prefs.lastReimportNudgeAtMs = now
        }
    }

    private fun post(id: Int, title: String, detail: String) {
        ensureChannel(context)

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Leakproof")
            .setContentText("You have an update")
            .build()

        val openApp = android.app.PendingIntent.getActivity(
            context, 0, android.content.Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        // Permission is re-checked here (it can be revoked while the worker runs)
        if (!canNotify(context)) return
        try {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun startOfDay(daysFromToday: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, daysFromToday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        const val DAYS_BEFORE = 2
        const val YEARLY_HEADS_UP_DAYS = 30
        const val YEARLY_LAST_CALL_DAYS = 7
        private const val NOTIFICATION_YEARLY = 1100
        private const val REIMPORT_AFTER_MS = 30L * 24 * 3600 * 1000
        private const val CHANNEL_ID = "renewal_reminders"
        private const val NOTIFICATION_RENEWALS = 1001
        private const val NOTIFICATION_TRIALS = 1002
        private const val NOTIFICATION_REIMPORT = 1003
        private const val WORK_NAME = "renewal_reminders_daily"

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<RenewalReminderWorker>(1, TimeUnit.DAYS).build()
                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                wm.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
