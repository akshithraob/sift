package com.leakproof.app.insights

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.parser.ParsedMandateCandidate
import com.leakproof.app.util.CurrencyFormatter
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil

/** Pure, on-device analysis of the user's subscriptions. Nothing here touches storage or the network. */

private const val DAY_MS = 24L * 3600 * 1000

/** Cost of a subscription normalised to a month. */
fun monthlyCost(amount: Double, frequency: String): Double = when (frequency.uppercase()) {
    "YEARLY" -> amount / 12.0
    "WEEKLY" -> amount * 4.33
    "QUARTERLY" -> amount / 3.0
    else -> amount
}

/** Lowercase letters and digits only, minus filler words, so "Netflix India" ~ "netflix". */
internal fun normalizeName(name: String): String =
    name.lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .replace("india", "")
        .replace("premium", "")
        .replace("autopay", "")
        .replace("subscription", "")

// ---------------------------------------------------------------- Overlaps

enum class OverlapKind { DUPLICATE, OVERLAP }

data class OverlapInsight(
    val kind: OverlapKind,
    val label: String,
    val subscriptionIds: List<Long>,
    val merchants: List<String>,
    val monthlyTotal: Double
) {
    val title: String
        get() = when (kind) {
            OverlapKind.DUPLICATE -> "${merchants.first()} appears ${subscriptionIds.size} times"
            OverlapKind.OVERLAP -> "You pay for ${merchants.size} ${label.lowercase()} services"
        }
}

object OverlapDetector {

    private val groups: Map<String, List<String>> = linkedMapOf(
        "Music streaming" to listOf("spotify", "apple music", "youtube music", "youtube premium", "gaana", "jiosaavn", "wynk", "amazon music"),
        "Video streaming" to listOf("netflix", "hotstar", "prime video", "amazon prime", "sony liv", "sonyliv", "zee5", "jiocinema", "apple tv", "mx player"),
        "Cloud storage" to listOf("google one", "icloud", "dropbox", "onedrive"),
        "AI assistant" to listOf("claude", "anthropic", "chatgpt", "openai", "gemini", "perplexity", "copilot"),
        "News & reading" to listOf("the hindu", "times prime", "economist", "hindustan times", "indian express", "kindle unlimited", "bloomberg", "medium"),
        "Fitness" to listOf("cult.fit", "cult fit", "curefit", "healthifyme", "fitbit", "strava"),
        "Food delivery membership" to listOf("swiggy one", "zomato gold", "eatsure"),
        "Online learning" to listOf("coursera", "udemy", "unacademy", "byju", "duolingo", "skillshare")
    )

    fun detect(subscriptions: List<SubscriptionEntity>): List<OverlapInsight> {
        val active = subscriptions.filter { it.status == "ACTIVE" }
        val result = mutableListOf<OverlapInsight>()

        // Exactly the same service tracked more than once (e.g. mandates created in two UPI apps)
        active.groupBy { normalizeName(it.merchant) }
            .filter { it.key.isNotBlank() && it.value.size >= 2 }
            .forEach { (_, dupes) ->
                result.add(
                    OverlapInsight(
                        kind = OverlapKind.DUPLICATE,
                        label = "Duplicate",
                        subscriptionIds = dupes.map { it.id },
                        merchants = listOf(dupes.first().merchant),
                        monthlyTotal = dupes.sumOf { monthlyCost(it.amount, it.frequency) }
                    )
                )
            }

        // Different services that do the same job
        for ((label, keywords) in groups) {
            val members = active.filter { sub ->
                val lower = sub.merchant.lowercase()
                keywords.any { lower.contains(it) }
            }
            val distinctServices = members.distinctBy { normalizeName(it.merchant) }
            if (distinctServices.size >= 2) {
                result.add(
                    OverlapInsight(
                        kind = OverlapKind.OVERLAP,
                        label = label,
                        subscriptionIds = distinctServices.map { it.id },
                        merchants = distinctServices.map { it.merchant },
                        monthlyTotal = distinctServices.sumOf { monthlyCost(it.amount, it.frequency) }
                    )
                )
            }
        }

        return result.sortedWith(compareBy({ it.kind }, { -it.monthlyTotal }))
    }
}

// ---------------------------------------------------------------- Mandate risk

enum class RiskKind { HIGH_LIMIT, OPEN_ENDED }

data class MandateRisk(
    val subscriptionId: Long,
    val merchant: String,
    val kind: RiskKind,
    val message: String
)

object MandateRiskAnalyzer {

    fun analyze(subscriptions: List<SubscriptionEntity>): List<MandateRisk> {
        val risks = mutableListOf<MandateRisk>()
        for (sub in subscriptions.filter { it.status == "ACTIVE" }) {
            val limit = sub.maxAmount ?: continue
            if (limit <= sub.amount) continue

            if (limit >= sub.amount * 4 && limit - sub.amount >= 1000) {
                risks.add(
                    MandateRisk(
                        sub.id, sub.merchant, RiskKind.HIGH_LIMIT,
                        "${sub.merchant} can charge up to ${CurrencyFormatter.format(limit)} but usually takes " +
                            "${CurrencyFormatter.format(sub.amount)}. Consider lowering the limit in your UPI app."
                    )
                )
            } else if (sub.frequency.uppercase() == "AS_PRESENTED" && limit >= 5000) {
                risks.add(
                    MandateRisk(
                        sub.id, sub.merchant, RiskKind.OPEN_ENDED,
                        "${sub.merchant} can charge up to ${CurrencyFormatter.format(limit)} whenever it presents a bill."
                    )
                )
            }
        }
        return risks
    }
}

// ---------------------------------------------------------------- Re-import diff

data class MatchedCandidate(
    val candidate: ParsedMandateCandidate,
    val existing: SubscriptionEntity,
    val amountChanged: Boolean,
    val dateChanged: Boolean
) {
    val hasChanges: Boolean get() = amountChanged || dateChanged
}

data class ImportDiffResult(
    val newCandidates: List<ParsedMandateCandidate>,
    val matches: List<MatchedCandidate>,
    /** Active, screenshot-sourced UPI mandates that this import no longer shows. */
    val missing: List<SubscriptionEntity>
) {
    fun matchFor(candidateId: String): MatchedCandidate? = matches.firstOrNull { it.candidate.id == candidateId }
}

object ImportDiff {

    private const val GOOGLE_PLAY = "Google Play"

    fun compare(existing: List<SubscriptionEntity>, candidates: List<ParsedMandateCandidate>): ImportDiffResult {
        val taken = mutableSetOf<Long>()
        val matches = mutableListOf<MatchedCandidate>()
        val fresh = mutableListOf<ParsedMandateCandidate>()

        for (candidate in candidates) {
            val match = findMatch(existing.filter { it.id !in taken }, candidate)
            if (match == null) {
                fresh.add(candidate)
            } else {
                taken.add(match.id)
                val dateMs = candidate.nextDebitDateEpochMs
                matches.add(
                    MatchedCandidate(
                        candidate = candidate,
                        existing = match,
                        amountChanged = abs(match.amount - candidate.amount) > 0.5,
                        dateChanged = dateMs != null && abs(match.nextDateEpochMs - dateMs) > DAY_MS
                    )
                )
            }
        }

        // "No longer seen" only makes sense after a full mandate list, not a lone Play subscriptions page
        val isFullList = candidates.any { it.source.name == "SCREENSHOT" && it.sourceApp != GOOGLE_PLAY }
        val missing = if (!isFullList) emptyList() else existing.filter {
            it.status == "ACTIVE" && it.source == "SCREENSHOT" && it.rail == "UPI" && it.id !in taken
        }

        return ImportDiffResult(fresh, matches, missing)
    }

    /** Same service by name (price changes still match); the closest amount wins among look-alikes. */
    fun findMatch(existing: List<SubscriptionEntity>, candidate: ParsedMandateCandidate): SubscriptionEntity? {
        val target = normalizeName(candidate.merchant)
        if (target.isBlank()) return null
        val sameName = existing.filter { sub ->
            val name = normalizeName(sub.merchant)
            name.isNotBlank() && (name == target || name.contains(target) || target.contains(name))
        }
        if (sameName.isEmpty()) return null
        val exact = sameName.filter { normalizeName(it.merchant) == target }
        return (exact.ifEmpty { sameName }).minByOrNull { abs(it.amount - candidate.amount) }
    }
}

// ---------------------------------------------------------------- Budget

enum class BudgetLevel { NONE, OK, NEAR, OVER }

data class BudgetStatus(val level: BudgetLevel, val budget: Double, val spent: Double) {
    val ratio: Float get() = if (budget > 0) (spent / budget).toFloat() else 0f
    val remaining: Double get() = budget - spent
}

object Budget {
    fun evaluate(spentPerMonth: Double, budget: Double): BudgetStatus {
        val level = when {
            budget <= 0.0 -> BudgetLevel.NONE
            spentPerMonth > budget -> BudgetLevel.OVER
            spentPerMonth >= budget * 0.85 -> BudgetLevel.NEAR
            else -> BudgetLevel.OK
        }
        return BudgetStatus(level, budget, spentPerMonth)
    }
}

// ---------------------------------------------------------------- Free trials

object Trials {

    /** Whole calendar days from today until [epochMs] (0 = today, 1 = tomorrow, negative = passed). */
    fun daysUntil(epochMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        fun startOfDay(ms: Long): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = ms }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        return ceil((startOfDay(epochMs) - startOfDay(nowMs)).toDouble() / DAY_MS).toInt()
    }

    /** Active trials that have not ended yet, soonest first. */
    fun activeTrials(subscriptions: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis()): List<SubscriptionEntity> =
        subscriptions
            .filter { it.status == "ACTIVE" && it.trialEndEpochMs != null && daysUntil(it.trialEndEpochMs, nowMs) >= 0 }
            .sortedBy { it.trialEndEpochMs }

    fun label(daysLeft: Int): String = when {
        daysLeft <= 0 -> "Trial ends today"
        daysLeft == 1 -> "Trial ends tomorrow"
        else -> "Trial ends in $daysLeft days"
    }
}
