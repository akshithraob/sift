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

enum class OverlapKind { DUPLICATE, BUNDLE, SAME_SERVICE, OVERLAP }

data class OverlapInsight(
    val kind: OverlapKind,
    val label: String,
    val subscriptionIds: List<Long>,
    val merchants: List<String>,
    /** What the listed services cost together per month (for a bundle: what the redundant ones cost). */
    val monthlyTotal: Double,
    /** For a bundle: the plan that already includes the others. */
    val bundleName: String? = null
) {
    val title: String
        get() = when (kind) {
            OverlapKind.DUPLICATE -> "${merchants.first()} appears ${subscriptionIds.size} times"
            OverlapKind.BUNDLE -> "$bundleName already includes ${joinNames(merchants)}"
            OverlapKind.SAME_SERVICE -> "You pay for ${merchants.size} $label plans"
            OverlapKind.OVERLAP -> "You pay for ${merchants.size} ${label.lowercase()} services"
        }

    private fun joinNames(names: List<String>) = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

object OverlapDetector {

    /** Different services that do the same job. Paying for two of the same group is worth a look. */
    private val groups: Map<String, List<String>> = linkedMapOf(
        "Music streaming" to listOf("spotify", "apple music", "youtube music", "youtube premium", "gaana", "jiosaavn", "wynk", "amazon music"),
        "Video streaming" to listOf("netflix", "hotstar", "prime video", "amazon prime", "sony liv", "sonyliv", "zee5", "jiocinema", "apple tv", "mx player", "discovery+", "lionsgate", "hoichoi", "sun nxt", "sunnxt"),
        "Cloud storage" to listOf("google one", "icloud", "dropbox", "onedrive", "mega"),
        "AI assistant" to listOf("claude", "anthropic", "chatgpt", "openai", "gemini", "perplexity", "copilot"),
        "News & reading" to listOf("the hindu", "times prime", "economist", "hindustan times", "indian express", "kindle unlimited", "bloomberg", "medium", "wall street journal", "wsj", "financial times"),
        "Audio & books" to listOf("audible", "storytel", "pocket fm", "kuku fm", "kindle unlimited"),
        "Fitness" to listOf("cult.fit", "cult fit", "curefit", "healthifyme", "fitbit", "strava", "apple fitness"),
        "Meditation & wellness" to listOf("headspace", "calm", "wysa", "insight timer"),
        "Food delivery membership" to listOf("swiggy one", "zomato gold", "eatsure"),
        "Online learning" to listOf("coursera", "udemy", "unacademy", "byju", "duolingo", "skillshare", "masterclass", "linkedin learning", "chess.com"),
        "Gaming" to listOf("xbox game pass", "game pass", "playstation plus", "ps plus", "nintendo switch online", "ea play", "steam", "discord nitro", "geforce now"),
        "VPN" to listOf("nordvpn", "expressvpn", "surfshark", "windscribe", "protonvpn", "proton vpn", "cyberghost"),
        "Design & creative" to listOf("canva", "adobe", "figma", "picsart", "lightroom", "photoshop"),
        "Notes & productivity" to listOf("notion", "evernote", "todoist", "obsidian", "microsoft 365", "office 365", "google workspace"),
        "Writing tools" to listOf("grammarly", "quillbot", "prowritingaid"),
        "Password manager" to listOf("1password", "lastpass", "bitwarden", "dashlane")
    )

    /** One service, several plans: paying for two of these is almost always one too many. */
    private val families: Map<String, List<String>> = linkedMapOf(
        "Spotify" to listOf("spotify"),
        "Netflix" to listOf("netflix"),
        "Hotstar" to listOf("hotstar", "jiohotstar"),
        "YouTube" to listOf("youtube premium", "youtube music"),
        "Amazon Prime" to listOf("amazon prime", "prime video", "prime lite"),
        "Google One" to listOf("google one"),
        "Microsoft 365" to listOf("microsoft 365", "office 365"),
        "Xbox Game Pass" to listOf("xbox game pass", "game pass"),
        "Sony LIV" to listOf("sony liv", "sonyliv"),
        "Audible" to listOf("audible")
    )

    /** Names the parser or the user gives when the real service is unknown; two of them are not duplicates. */
    private val placeholderNames = setOf("subscriptionviapaytm", "autopaysubscription", "upiautopaymandate", "bankmandate", "banktransaction", "unknown", "googleplay")

    /** A plan that already contains other services. Paying for both is paying twice. */
    private class Bundle(val name: String, val names: List<String>, val includes: List<String>)

    private val bundles = listOf(
        Bundle("Apple One", listOf("apple one"), listOf("apple music", "icloud", "apple tv", "apple arcade", "apple fitness")),
        Bundle("Amazon Prime", listOf("amazon prime", "prime lite"), listOf("prime video", "amazon music")),
        Bundle("YouTube Premium", listOf("youtube premium"), listOf("youtube music")),
        Bundle("Microsoft 365", listOf("microsoft 365", "office 365"), listOf("onedrive")),
        Bundle("Xbox Game Pass Ultimate", listOf("game pass ultimate"), listOf("ea play", "xbox live", "game pass core", "pc game pass"))
    )

    private fun matches(sub: SubscriptionEntity, keywords: List<String>): Boolean {
        val lower = sub.merchant.lowercase()
        return keywords.any { lower.contains(it) }
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }

    /** The same service seen twice: same name, a one-letter slip, or one name contained in the other. */
    private fun sameName(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (minOf(a.length, b.length) >= 6 && editDistance(a, b) <= 1) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 5 && long.contains(short)
    }

    fun detect(subscriptions: List<SubscriptionEntity>): List<OverlapInsight> {
        // Two EMIs or two SIPs are not "paying twice": only subscriptions can overlap
        val active = subscriptions.filter { it.status == "ACTIVE" && it.kind == "SUBSCRIPTION" }
        val result = mutableListOf<OverlapInsight>()
        val claimed = mutableListOf<Set<Long>>()

        fun add(insight: OverlapInsight) {
            val ids = insight.subscriptionIds.toSet()
            // Skip a finding that is already part of a bigger, more specific one
            if (claimed.any { it.containsAll(ids) }) return
            claimed.add(ids)
            result.add(insight)
        }

        // Plans that already include other services
        for (bundle in bundles) {
            val owners = active.filter { matches(it, bundle.names) }
            if (owners.isEmpty()) continue
            val owner = owners.first()
            val covered = active.filter { it.id != owner.id && owners.none { o -> o.id == it.id } && matches(it, bundle.includes) }
            if (covered.isEmpty()) continue
            add(
                OverlapInsight(
                    kind = OverlapKind.BUNDLE,
                    label = "Bundle",
                    subscriptionIds = listOf(owner.id) + covered.map { it.id },
                    merchants = covered.map { it.merchant },
                    monthlyTotal = covered.sumOf { monthlyCost(it.amount, it.frequency) },
                    bundleName = owner.merchant
                )
            )
        }

        // The same service tracked more than once (two UPI apps, a slip in the name) or as two plans
        val names = active.map { normalizeName(it.merchant) }
        val parent = IntArray(active.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in active.indices) for (j in i + 1 until active.size) {
            if (!sameName(names[i], names[j])) continue
            // A placeholder name ("Subscription via Paytm") says nothing about the service, and two
            // mandates with very different amounts are two different things
            if (names[i] in placeholderNames || names[j] in placeholderNames) continue
            val hi = maxOf(active[i].amount, active[j].amount)
            val lo = minOf(active[i].amount, active[j].amount)
            if (names[i] == names[j] && lo > 0 && hi / lo > 1.25) continue
            parent[find(i)] = find(j)
        }
        active.indices.groupBy { find(it) }.values.filter { it.size >= 2 }.forEach { members ->
            val subs = members.map { active[it] }
            val allSame = members.all { names[it] == names[members.first()] || editDistance(names[it], names[members.first()]) <= 1 }
            val cost = subs.sumOf { monthlyCost(it.amount, it.frequency) }
            if (allSame) {
                add(OverlapInsight(OverlapKind.DUPLICATE, "Duplicate", subs.map { it.id }, listOf(subs.first().merchant), cost))
            } else {
                val brand = subs.minByOrNull { it.merchant.length }!!.merchant
                add(OverlapInsight(OverlapKind.SAME_SERVICE, brand, subs.map { it.id }, subs.map { it.merchant }, cost))
            }
        }

        // Well-known services whose plans have different names
        for ((brand, aliases) in families) {
            val members = active.filter { matches(it, aliases) }
            if (members.size >= 2) {
                add(OverlapInsight(OverlapKind.SAME_SERVICE, brand, members.map { it.id }, members.map { it.merchant }, members.sumOf { monthlyCost(it.amount, it.frequency) }))
            }
        }

        // Different services that do the same job
        for ((label, keywords) in groups) {
            val members = active.filter { matches(it, keywords) }
            val distinctServices = members.distinctBy { normalizeName(it.merchant) }
            if (distinctServices.size >= 2) {
                add(
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
    val missing: List<SubscriptionEntity>,
    /** Active mandates the imported statement (long enough to judge) shows no debit for. */
    val unseen: List<SubscriptionEntity> = emptyList()
) {
    fun matchFor(candidateId: String): MatchedCandidate? = matches.firstOrNull { it.candidate.id == candidateId }
}

object ImportDiff {

    private const val GOOGLE_PLAY = "Google Play"
    private const val MIN_COVERAGE_DAYS = 60

    fun compare(
        existing: List<SubscriptionEntity>,
        candidates: List<ParsedMandateCandidate>,
        statementCoverageDays: Int? = null
    ): ImportDiffResult {
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

        // A statement that spans at least two months can show that an authorised mandate never debited.
        // Yearly and other long-cycle items are skipped: the statement could not have caught them.
        val unseen = if (statementCoverageDays == null || statementCoverageDays < MIN_COVERAGE_DAYS ||
            candidates.none { it.source.name == "STATEMENT" }
        ) emptyList() else existing.filter {
            it.status == "ACTIVE" && it.rail in setOf("UPI", "NACH", "SI") && it.id !in taken &&
                frequencyDays(it.frequency) * 1.2 <= statementCoverageDays
        }

        return ImportDiffResult(fresh, matches, missing, unseen)
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

// ---------------------------------------------------------------- Leaks (what needs attention)

enum class LeakSeverity { HIGH, MEDIUM }

enum class LeakKind { OVER_BUDGET, NEAR_BUDGET, TRIAL, DUPLICATE, BUNDLE, SAME_SERVICE, OVERLAP, HIGH_INTEREST, STALE_CHARGE, MANDATE_LIMIT }

data class Leak(
    val kind: LeakKind,
    val severity: LeakSeverity,
    val title: String,
    val body: String,
    val subscriptionIds: List<Long> = emptyList()
)

/** Gathers everything the home screen should flag, most urgent first. */
object LeakFinder {

    private const val TRIAL_WARNING_DAYS = 14
    private const val HIGH_INTEREST_PCT = 18.0

    fun find(
        subscriptions: List<SubscriptionEntity>,
        budget: BudgetStatus,
        nowMs: Long = System.currentTimeMillis()
    ): List<Leak> {
        val leaks = mutableListOf<Leak>()

        when (budget.level) {
            BudgetLevel.OVER -> leaks.add(
                Leak(
                    LeakKind.OVER_BUDGET, LeakSeverity.HIGH,
                    "${CurrencyFormatter.format(-budget.remaining)} over budget",
                    "Subscriptions cost ${CurrencyFormatter.format(budget.spent)} a month against your budget of ${CurrencyFormatter.format(budget.budget)}."
                )
            )
            BudgetLevel.NEAR -> leaks.add(
                Leak(
                    LeakKind.NEAR_BUDGET, LeakSeverity.MEDIUM,
                    "Close to your budget",
                    "${CurrencyFormatter.format(budget.remaining)} left of ${CurrencyFormatter.format(budget.budget)} this month."
                )
            )
            else -> Unit
        }

        for (sub in Trials.activeTrials(subscriptions, nowMs)) {
            val days = Trials.daysUntil(sub.trialEndEpochMs ?: continue, nowMs)
            if (days > TRIAL_WARNING_DAYS) continue
            leaks.add(
                Leak(
                    LeakKind.TRIAL,
                    if (days <= 2) LeakSeverity.HIGH else LeakSeverity.MEDIUM,
                    "${sub.merchant}: ${Trials.label(days).replaceFirstChar { it.lowercase() }}",
                    "Cancel before it ends to avoid being charged ${CurrencyFormatter.format(sub.amount)}.",
                    listOf(sub.id)
                )
            )
        }

        for (insight in OverlapDetector.detect(subscriptions)) {
            val monthly = CurrencyFormatter.format(insight.monthlyTotal)
            val yearly = CurrencyFormatter.format(insight.monthlyTotal * 12)
            val (kind, severity, body) = when (insight.kind) {
                OverlapKind.DUPLICATE -> Triple(
                    LeakKind.DUPLICATE, LeakSeverity.HIGH,
                    "${insight.merchants.joinToString(" + ")} together cost $monthly/month. Keep one and cancel the other."
                )
                OverlapKind.BUNDLE -> Triple(
                    LeakKind.BUNDLE, LeakSeverity.HIGH,
                    "You also pay for them separately: $monthly/month ($yearly a year). Cancelling the separate ones keeps the same access."
                )
                OverlapKind.SAME_SERVICE -> Triple(
                    LeakKind.SAME_SERVICE, LeakSeverity.MEDIUM,
                    "${insight.merchants.joinToString(" + ")} cost $monthly/month together. One plan is usually enough."
                )
                OverlapKind.OVERLAP -> Triple(
                    LeakKind.OVERLAP, LeakSeverity.MEDIUM,
                    "${insight.merchants.joinToString(" + ")} together cost $monthly/month. Keep the one you use most?"
                )
            }
            leaks.add(Leak(kind, severity, insight.title, body, insight.subscriptionIds))
        }

        for (sub in subscriptions.filter { it.status == "ACTIVE" && it.kind == "EMI" }) {
            val rate = sub.interestRatePct ?: continue
            if (rate < HIGH_INTEREST_PCT) continue
            leaks.add(
                Leak(
                    LeakKind.HIGH_INTEREST, LeakSeverity.MEDIUM,
                    "High-interest loan: ${sub.merchant} at ${"%.1f".format(java.util.Locale.ENGLISH, rate)}%",
                    "Part payments cost less than paying interest for the full term. Open it to see what an extra payment would save.",
                    listOf(sub.id)
                )
            )
        }

        for (sub in subscriptions.filter { it.status == "ACTIVE" }) {
            val last = sub.lastChargedAtMs ?: continue
            val cycleDays = frequencyDays(sub.frequency)
            val sinceDays = (nowMs - last) / DAY_MS
            // Two and a half missed cycles (and at least two months) is more than a late payment
            if (sinceDays < maxOf(cycleDays * 2.5, 60.0)) continue
            val months = (sinceDays / 30.4375).toInt()
            leaks.add(
                Leak(
                    LeakKind.STALE_CHARGE, LeakSeverity.MEDIUM,
                    "${sub.merchant}: no charge for $months months",
                    "The last debit we saw was in ${java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(last))}. " +
                        "If you no longer use it, revoke the mandate so it can't start charging again.",
                    listOf(sub.id)
                )
            )
        }

        for (risk in MandateRiskAnalyzer.analyze(subscriptions)) {
            leaks.add(
                Leak(LeakKind.MANDATE_LIMIT, LeakSeverity.MEDIUM, "Mandate limit: ${risk.merchant}", risk.message, listOf(risk.subscriptionId))
            )
        }

        return leaks.sortedWith(compareBy({ it.severity }, { it.kind }))
    }
}

// ---------------------------------------------------------------- Health score

enum class HealthLevel(val label: String) { HEALTHY("Healthy"), NEEDS_A_LOOK("Needs a look"), LEAKING("Leaking") }

data class HealthScore(val score: Int, val level: HealthLevel) {
    companion object {
        private const val HIGH_PENALTY = 12
        private const val MEDIUM_PENALTY = 6

        /** 100 minus a penalty per leak; null when there is nothing to judge yet. */
        fun compute(subscriptions: List<SubscriptionEntity>, leaks: List<Leak>): HealthScore? {
            if (subscriptions.none { it.status == "ACTIVE" }) return null
            val penalty = leaks.sumOf { if (it.severity == LeakSeverity.HIGH) HIGH_PENALTY else MEDIUM_PENALTY }
            val score = (100 - penalty).coerceIn(0, 100)
            val level = when {
                score >= 85 -> HealthLevel.HEALTHY
                score >= 60 -> HealthLevel.NEEDS_A_LOOK
                else -> HealthLevel.LEAKING
            }
            return HealthScore(score, level)
        }
    }
}
