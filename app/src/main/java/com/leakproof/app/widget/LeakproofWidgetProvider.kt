package com.leakproof.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.leakproof.app.MainActivity
import com.leakproof.app.R
import com.leakproof.app.data.local.AppDatabase
import com.leakproof.app.data.security.PreferencesManager
import com.leakproof.app.insights.WidgetSummary
import com.leakproof.app.util.CurrencyFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget: this month's total and the next renewal.
 *
 * It reads the same encrypted database as the app, on the device, and nothing is sent anywhere. A
 * widget sits on the home screen where anyone holding the phone can read it, so amounts and names stay
 * hidden until the user switches them on in Settings. Tapping it opens the app (and the app lock).
 */
class LeakproofWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                update(context, manager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Redraw every placed widget; call after the data or the privacy setting changes. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, LeakproofWidgetProvider::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch { update(app, manager, ids) }
        }

        private suspend fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val showDetails = PreferencesManager(context).widgetShowDetails
            val subs = try {
                AppDatabase.getInstance(context).subscriptionDao().getAllOnce()
            } catch (_: Exception) {
                null
            }
            val lines = describe(subs?.let { WidgetSummary.build(it) }, showDetails, subs == null)

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_leakproof).apply {
                    setTextViewText(R.id.widget_main, lines.main)
                    setTextViewText(R.id.widget_line1, lines.line1)
                    setTextViewText(R.id.widget_line2, lines.line2)
                    setOnClickPendingIntent(R.id.widget_root, open)
                }
                manager.updateAppWidget(id, views)
            }
        }

        internal data class Lines(val main: String, val line1: String, val line2: String)

        internal fun describe(summary: WidgetSummary?, showDetails: Boolean, unavailable: Boolean): Lines {
            if (unavailable || summary == null) return Lines("Leakproof", "Open the app to get started", "")
            if (!showDetails) {
                return Lines("Leakproof", "Tap to open", "Amounts are hidden. Show them in Settings.")
            }
            if (summary.activeCount == 0) return Lines("Nothing tracked yet", "Tap to add your first one", "")

            val next = summary.next
            val line1 = if (next == null) "No renewals coming up" else {
                val days = summary.nextDays ?: 0
                val whenText = when {
                    days <= 0 -> "today"
                    days == 1 -> "tomorrow"
                    else -> "in $days days"
                }
                "Next: ${next.subscription.merchant}, $whenText"
            }
            val line2 = buildString {
                if (next != null) append(CurrencyFormatter.format(next.subscription.amount))
                if (summary.dueThisWeekCount > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("${summary.dueThisWeekCount} due this week (${CurrencyFormatter.format(summary.dueThisWeekTotal)})")
                }
            }
            return Lines("${CurrencyFormatter.format(summary.monthlyTotal)}/mo", line1, line2)
        }
    }
}
