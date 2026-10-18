package com.example.reminder

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
import com.example.MainActivity
import com.example.data.local.AppDatabase
import com.example.util.CurrencyFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Local-only renewal reminders: once a day, notifies about active subscriptions renewing in
 * [DAYS_BEFORE] days. Nothing leaves the device; the lock-screen version of the notification
 * is generic so merchant names and amounts stay private.
 */
class RenewalReminderWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!canNotify(context)) return Result.success()

        val dayStart = startOfDay(DAYS_BEFORE)
        val dayEnd = startOfDay(DAYS_BEFORE + 1)
        val due = try {
            AppDatabase.getInstance(context).subscriptionDao().getActiveRenewingBetween(dayStart, dayEnd)
        } catch (_: Exception) {
            return Result.retry()
        }
        if (due.isEmpty()) return Result.success()

        ensureChannel(context)
        val title = if (due.size == 1) "1 subscription renews in $DAYS_BEFORE days"
        else "${due.size} subscriptions renew in $DAYS_BEFORE days"
        val detail = due.joinToString(", ") { "${it.merchant} (${CurrencyFormatter.format(it.amount)})" }

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SubTrack")
            .setContentText("Upcoming renewal")
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

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return Result.success()
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
        private const val CHANNEL_ID = "renewal_reminders"
        private const val NOTIFICATION_ID = 1001
        private const val WORK_NAME = "renewal_reminders_daily"

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Renewal reminders", NotificationManager.IMPORTANCE_DEFAULT
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
