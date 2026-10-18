package com.leakproof.app.insights

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.parser.RecurringKind
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * EMI and loan arithmetic, all on-device and informational only. Interest maths assumes a standard
 * reducing-balance loan paid in equal monthly instalments, which can differ from a lender's own schedule.
 */

private const val DAY_MS = 24L * 3600 * 1000

/** Days in one billing cycle. */
internal fun frequencyDays(frequency: String): Double = when (frequency.uppercase()) {
    "WEEKLY" -> 7.0
    "QUARTERLY" -> 91.3125
    "YEARLY" -> 365.25
    else -> 30.4375
}

data class EmiStatus(
    val total: Int?,
    val paid: Int?,
    val remaining: Int?,
    val payoffMs: Long?,
    val remainingPayable: Double?,
    val progress: Float?
)

data class FreedUp(
    val merchant: String,
    val monthlyAmount: Double,
    val payoffMs: Long,
    /** Total monthly EMI outgo that is still left after this one has ended. */
    val monthlyAfter: Double
)

data class Prepayment(
    val monthsSaved: Int,
    val interestSaved: Double,
    val newRemainingMonths: Int,
    val principalUsed: Double
)

object EmiMath {

    fun remainingInstallments(sub: SubscriptionEntity): Int? {
        val total = sub.installmentsTotal
        if (total != null) return max(0, total - (sub.installmentsPaid ?: 0))
        val end = sub.endEpochMs ?: return null
        if (end < sub.nextDateEpochMs) return 0
        val stepMs = frequencyDays(sub.frequency) * DAY_MS
        return ((end - sub.nextDateEpochMs) / stepMs).toInt() + 1
    }

    fun payoffMs(sub: SubscriptionEntity): Long? {
        sub.endEpochMs?.let { return it }
        val remaining = remainingInstallments(sub) ?: return null
        if (remaining <= 0) return null
        return sub.nextDateEpochMs + ((remaining - 1) * frequencyDays(sub.frequency) * DAY_MS).toLong()
    }

    fun status(sub: SubscriptionEntity): EmiStatus {
        val remaining = remainingInstallments(sub)
        val total = sub.installmentsTotal
        val paid = sub.installmentsPaid
        return EmiStatus(
            total = total,
            paid = paid,
            remaining = remaining,
            payoffMs = payoffMs(sub),
            remainingPayable = remaining?.let { it * sub.amount },
            progress = if (total != null && total > 0 && paid != null) (paid.toFloat() / total).coerceIn(0f, 1f) else null
        )
    }

    /** When each active EMI ends, soonest first, and how much monthly outgo that frees up. */
    fun freedUp(subscriptions: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis()): List<FreedUp> {
        val emis = subscriptions.filter { it.status == "ACTIVE" && it.kind == RecurringKind.EMI.name }
        val withEnd = emis.mapNotNull { sub ->
            val end = payoffMs(sub) ?: return@mapNotNull null
            if (end < nowMs - DAY_MS) null else Triple(sub, end, monthlyOf(sub))
        }.sortedBy { it.second }
        var left = emis.sumOf { monthlyOf(it) }
        return withEnd.map { (sub, end, monthly) ->
            left -= monthly
            FreedUp(sub.merchant, monthly, end, left.coerceAtLeast(0.0))
        }
    }

    /** Date the last known EMI ends, or null when none has a known end. */
    fun debtFreeMs(subscriptions: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis()): Long? {
        val emis = subscriptions.filter { it.status == "ACTIVE" && it.kind == RecurringKind.EMI.name }
        if (emis.isEmpty() || emis.any { payoffMs(it) == null }) return null
        return emis.maxOf { payoffMs(it)!! }.takeIf { it >= nowMs - DAY_MS }
    }

    fun totalRemainingPayable(subscriptions: List<SubscriptionEntity>): Double =
        subscriptions
            .filter { it.status == "ACTIVE" && it.kind == RecurringKind.EMI.name }
            .sumOf { (remainingInstallments(it) ?: 0) * it.amount }

    /** Share of recurring monthly outgo that goes to EMIs (0..1), or null when there is nothing recurring. */
    fun burden(subscriptions: List<SubscriptionEntity>): Double? {
        val active = subscriptions.filter { it.status == "ACTIVE" }
        val total = active.sumOf { monthlyOf(it) }
        if (total <= 0) return null
        return active.filter { it.kind == RecurringKind.EMI.name }.sumOf { monthlyOf(it) } / total
    }

    /**
     * What a one-time extra payment now would do: how many instalments it removes and how much
     * interest it saves. Needs the yearly rate; uses the outstanding balance if known, otherwise
     * the balance implied by the instalment, rate and instalments left.
     */
    fun prepayment(sub: SubscriptionEntity, extra: Double): Prepayment? {
        val rate = sub.interestRatePct ?: return null
        val n = remainingInstallments(sub) ?: return null
        if (n <= 0 || extra <= 0 || sub.amount <= 0) return null
        val emi = sub.amount
        val r = rate / 1200.0

        val principal = sub.outstanding ?: if (r > 0) emi * (1 - (1 + r).pow(-n.toDouble())) / r else emi * n
        val reduced = (principal - extra).coerceAtLeast(0.0)

        val newMonths = when {
            reduced <= 0.0 -> 0
            r > 0 -> {
                val x = r * reduced / emi
                if (x >= 1.0) return null // the instalment would not even cover the interest
                ceil(-ln(1 - x) / ln(1 + r)).toInt()
            }
            else -> ceil(reduced / emi).toInt()
        }.coerceAtMost(n)

        val baseInterest = (emi * n - principal).coerceAtLeast(0.0)
        val newInterest = (emi * newMonths - reduced).coerceAtLeast(0.0)
        return Prepayment(
            monthsSaved = n - newMonths,
            interestSaved = (baseInterest - newInterest).coerceAtLeast(0.0),
            newRemainingMonths = newMonths,
            principalUsed = principal
        )
    }

    private fun monthlyOf(sub: SubscriptionEntity) = monthlyCost(sub.amount, sub.frequency)
}

/** Monthly outgo split by what the money is for. */
object KindBreakdown {
    fun monthlyByKind(subscriptions: List<SubscriptionEntity>): Map<RecurringKind, Double> =
        subscriptions
            .filter { it.status == "ACTIVE" }
            .groupBy { RecurringKind.fromName(it.kind) }
            .mapValues { (_, subs) -> subs.sumOf { monthlyCost(it.amount, it.frequency) } }
            .filterValues { it > 0 }
}
