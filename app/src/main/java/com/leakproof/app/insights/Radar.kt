package com.leakproof.app.insights

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.parser.RecurringKind
import java.util.Calendar

/** Pure helpers for the annual-renewal radar, the year in review and the home-screen widget. */

private const val DAY_MS = 24L * 3600 * 1000

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun daysBetween(fromMs: Long, toMs: Long): Int = ((startOfDay(toMs) - startOfDay(fromMs)) / DAY_MS).toInt()

// ---------------------------------------------------------------- Annual-renewal radar

data class RadarItem(val subscription: SubscriptionEntity, val dateMs: Long, val daysAway: Int)

/** Yearly charges are the easy ones to forget, so they get their own look-ahead. */
object RenewalRadar {
    const val HORIZON_DAYS = 90
    const val HEADS_UP_DAYS = 30

    /** Yearly payments renewing within [horizonDays], soonest first. */
    fun upcoming(subs: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis(), horizonDays: Int = HORIZON_DAYS): List<RadarItem> {
        val from = startOfDay(nowMs)
        val to = from + (horizonDays + 1L) * DAY_MS
        return subs
            .filter { it.frequency.equals("YEARLY", ignoreCase = true) }
            .mapNotNull { sub ->
                val next = Schedule.occurrences(sub, from, to).firstOrNull() ?: return@mapNotNull null
                RadarItem(sub, next.dateMs, daysBetween(nowMs, next.dateMs))
            }
            .filter { it.daysAway in 0..horizonDays }
            .sortedBy { it.dateMs }
    }

    fun total(items: List<RadarItem>): Double = items.sumOf { it.subscription.amount }
}

// ---------------------------------------------------------------- Year in review

data class YearReview(
    val activeCount: Int,
    val yearlyTotal: Double,
    val perDay: Double,
    val priciest: SubscriptionEntity?,
    val priciestYearly: Double,
    val cancelledCount: Int,
    val cancelledYearlySaved: Double,
    val next12MonthsOut: Double,
    val busiestMonth: MonthForecast?,
    val leaksFound: Int,
    val byKindYearly: Map<RecurringKind, Double>
) {
    companion object {
        fun compute(subs: List<SubscriptionEntity>, leaks: List<Leak>, nowMs: Long = System.currentTimeMillis()): YearReview {
            val active = subs.filter { it.status == "ACTIVE" }
            fun yearly(s: SubscriptionEntity) = monthlyCost(s.amount, s.frequency) * 12
            val total = active.sumOf { yearly(it) }
            val priciest = active.filter { it.kind == RecurringKind.SUBSCRIPTION.name }.maxByOrNull { yearly(it) }
            val cancelled = subs.filter { it.status == "CANCELLED" && it.kind == RecurringKind.SUBSCRIPTION.name }
            val forecast = Schedule.forecast(subs, nowMs)
            return YearReview(
                activeCount = active.size,
                yearlyTotal = total,
                perDay = total / 365.0,
                priciest = priciest,
                priciestYearly = priciest?.let { yearly(it) } ?: 0.0,
                cancelledCount = cancelled.size,
                cancelledYearlySaved = cancelled.sumOf { yearly(it) },
                next12MonthsOut = forecast.sumOf { it.total },
                busiestMonth = forecast.maxByOrNull { it.total }?.takeIf { it.total > 0 },
                leaksFound = leaks.size,
                byKindYearly = active.groupBy { RecurringKind.fromName(it.kind) }.mapValues { (_, l) -> l.sumOf { yearly(it) } }
            )
        }
    }
}

// ---------------------------------------------------------------- Widget

data class WidgetSummary(
    val activeCount: Int,
    val monthlyTotal: Double,
    val next: Occurrence?,
    val nextDays: Int?,
    val dueThisWeekCount: Int,
    val dueThisWeekTotal: Double
) {
    companion object {
        fun build(subs: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis()): WidgetSummary {
            val active = subs.filter { it.status == "ACTIVE" }
            val from = startOfDay(nowMs)
            val upcoming = Schedule.occurrences(active, from, from + 400L * DAY_MS)
            val next = upcoming.firstOrNull()
            val week = upcoming.filter { it.dateMs < from + 8L * DAY_MS }
            return WidgetSummary(
                activeCount = active.size,
                monthlyTotal = active.sumOf { monthlyCost(it.amount, it.frequency) },
                next = next,
                nextDays = next?.let { daysBetween(nowMs, it.dateMs) },
                dueThisWeekCount = week.size,
                dueThisWeekTotal = week.sumOf { it.subscription.amount }
            )
        }
    }
}
