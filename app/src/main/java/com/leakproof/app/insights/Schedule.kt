package com.leakproof.app.insights

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.parser.RecurringKind
import java.util.Calendar

/** When each recurring payment will next be charged. Used by the forecast chart and the calendar. */

data class Occurrence(val dateMs: Long, val subscription: SubscriptionEntity)

data class MonthForecast(
    val year: Int,
    val month: Int, // 0-11
    val total: Double,
    val byKind: Map<RecurringKind, Double>
)

object Schedule {

    private const val SAFETY_LIMIT = 800

    private fun addCycle(cal: Calendar, frequency: String) {
        when (frequency.uppercase()) {
            "WEEKLY" -> cal.add(Calendar.DAY_OF_YEAR, 7)
            "QUARTERLY" -> cal.add(Calendar.MONTH, 3)
            "YEARLY" -> cal.add(Calendar.YEAR, 1)
            else -> cal.add(Calendar.MONTH, 1)
        }
    }

    /**
     * Charge dates of one payment from [fromMs] (inclusive) up to [toMs] (exclusive). Open-ended
     * "as presented" mandates are skipped (their amount is only a limit), and EMIs stop after the
     * last instalment or end date.
     */
    fun occurrences(sub: SubscriptionEntity, fromMs: Long, toMs: Long): List<Occurrence> {
        if (sub.status != "ACTIVE" || sub.frequency.equals("AS_PRESENTED", ignoreCase = true)) return emptyList()
        if (sub.nextDateEpochMs <= 0) return emptyList()

        val remaining = if (sub.kind == RecurringKind.EMI.name) EmiMath.remainingInstallments(sub) else null
        val endMs = sub.endEpochMs
        val result = mutableListOf<Occurrence>()
        val cal = Calendar.getInstance().apply { timeInMillis = sub.nextDateEpochMs }

        var chargesSoFar = 0
        var guard = 0
        while (cal.timeInMillis < toMs && guard++ < SAFETY_LIMIT) {
            val ms = cal.timeInMillis
            if (endMs != null && ms > endMs + 24L * 3600 * 1000) break
            if (remaining != null && chargesSoFar >= remaining) break
            if (ms >= fromMs) result.add(Occurrence(ms, sub))
            chargesSoFar++
            addCycle(cal, sub.frequency)
        }
        return result
    }

    fun occurrences(subs: List<SubscriptionEntity>, fromMs: Long, toMs: Long): List<Occurrence> =
        subs.flatMap { occurrences(it, fromMs, toMs) }.sortedBy { it.dateMs }

    private fun startOfMonth(year: Int, month: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(year, month, 1, 0, 0, 0)
        }.timeInMillis

    /** Money going out in each of the next [months] calendar months, split by what it is for. */
    fun forecast(subs: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis(), months: Int = 12): List<MonthForecast> {
        val start = Calendar.getInstance().apply { timeInMillis = nowMs }
        val firstYear = start.get(Calendar.YEAR)
        val firstMonth = start.get(Calendar.MONTH)

        val startOfToday = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply { timeInMillis = startOfMonth(firstYear, firstMonth); add(Calendar.MONTH, months) }.timeInMillis
        val all = occurrences(subs, startOfToday, end)

        return (0 until months).map { i ->
            val cal = Calendar.getInstance().apply { timeInMillis = startOfMonth(firstYear, firstMonth); add(Calendar.MONTH, i) }
            val from = cal.timeInMillis
            val to = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.MONTH, 1) }.timeInMillis
            val inMonth = all.filter { it.dateMs in from until to }
            val byKind = inMonth.groupBy { RecurringKind.fromName(it.subscription.kind) }
                .mapValues { (_, list) -> list.sumOf { it.subscription.amount } }
            MonthForecast(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), byKind.values.sum(), byKind)
        }
    }

    /** Total due on each day of the given month (1-31), for the calendar heat-map. */
    fun dueByDay(subs: List<SubscriptionEntity>, year: Int, month: Int): Map<Int, List<Occurrence>> {
        val from = startOfMonth(year, month)
        val to = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.MONTH, 1) }.timeInMillis
        return occurrences(subs, from, to).groupBy {
            Calendar.getInstance().apply { timeInMillis = it.dateMs }.get(Calendar.DAY_OF_MONTH)
        }
    }
}
