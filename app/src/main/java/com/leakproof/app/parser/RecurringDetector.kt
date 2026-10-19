package com.leakproof.app.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max

interface RecurringDetector {
    fun detectRecurring(transactions: List<RawTransactionLine>): List<ParsedMandateCandidate>
    fun normalizeMerchantName(rawNarration: String): String
}

class DefaultRecurringDetector : RecurringDetector {

    private val mandateKeywords = listOf(
        "UPI-MANDATE", "UPI MANDATE", "AUTOPAY", "AUTO-PAY", "AUTO PAY",
        "ACH D-", "ACH-", "ACH/", "NACH", "SI-", "SI/", "IB SI-",
        "STANDING INSTR", "AUTO DEBIT"
    )

    private companion object {
        const val MIN_SUBSCRIPTION_AMOUNT = 10.0
        const val MAX_CYCLE_SPREAD_DAYS = 5.0
        const val MAX_BILL_SWING = 2.5
    }

    /** Gaps between payments of a steady bill vary by a few days (weekends, due dates), not more. */
    private fun intervalSpread(days: List<Long>): Double {
        if (days.size < 2) return 0.0
        val mean = days.average()
        return Math.sqrt(days.sumOf { (it - mean) * (it - mean) } / days.size)
    }

    /**
     * Among a merchant's debits, the largest group of amounts within ±10% of each other that repeat on a
     * regular cycle (or number three or more). Null when the whole group already agrees or none does.
     */
    private fun repeatingSubset(group: List<RawTransactionLine>): List<RawTransactionLine>? {
        if (group.size < 3) return null
        val amounts = group.map { it.debitAmount }
        if ((amounts.max() - amounts.min()) / amounts.max() <= 0.10) return null
        var best: List<RawTransactionLine>? = null
        for (anchor in amounts.distinct()) {
            val subset = group.filter { abs(it.debitAmount - anchor) <= anchor * 0.10 }.sortedBy { it.dateEpochMs }
            if (subset.size < 2 || subset.size == group.size) continue
            val gaps = subset.zipWithNext { a, b -> (b.dateEpochMs - a.dateEpochMs) / (24L * 3600 * 1000) }.filter { it > 0 }
            val avg = if (gaps.isEmpty()) 0.0 else gaps.average()
            val regular = avg in 5.0..9.0 || avg in 24.0..36.0 || avg in 75.0..105.0 || avg in 340.0..390.0
            // Picking a few debits out of a noisy merchant needs strong evidence: three or more, or identical amounts on a cycle
            val identical = subset.map { it.debitAmount }.distinct().size == 1
            if (!(subset.size >= 3 || (identical && regular))) continue
            if (best == null || subset.size > best.size) best = subset
        }
        return best
    }

    /** Categories a statement prints for ordinary spending: repeats here are habits, not subscriptions. */
    private val spendingCategories = setOf(
        "groceries", "grocery", "shopping", "dining", "dining out", "food delivery", "food", "transport", "transfers",
        "transfer", "cash", "travel", "fuel", "p2p", "entertainment", "restaurants", "restaurants & cafes", "health", "pharmacy", "medical"
    )

    /** Words that name a statement column (category or mode), not the merchant; trimmed off the end of a name. */
    private val columnWords = setOf(
        "subscriptions", "subscription", "groceries", "shopping", "dining", "food", "delivery", "transport", "transfers",
        "rent", "cash", "savings", "health", "utilities", "insurance", "loan", "emi", "investments", "education",
        "entertainment", "travel", "salary", "income", "refund", "card", "tuition", "consulting", "fuel",
        "neft", "upi", "pos", "si", "ach", "imps", "bbps", "trf", "atm", "ecs", "nach", "rtgs", "debit", "credit", "dr", "cr"
    )

    private val fillerWords = setOf("premium", "subscription", "com", "app", "standard", "plus", "mobile", "family", "pro", "prime", "one", "gold", "membership", "recharge")

    private val ambiguousBrands = setOf("JIO", "AIRTEL", "APPLE", "AMAZON", "TATA PLAY", "TATAPLAY")

    private val billCategories = setOf("utilities", "utility", "bills", "bill", "insurance", "loan emi", "loan", "emi")
    private val billPattern = Regex("""(?i)\b(electric|electricity|water|gas|broadband|internet|fib(?:er|re)|postpaid|landline|bill|billpay|dth|recharge|maintenance|tax|charges|insurance|premium|emi|loan|fees?)\b""")

    private val cashPattern = Regex("""(?i)\b(atm|cash withdrawal|cash wdl|cash deposit)\b|^cash$""")

    private val modeWords = setOf("neft", "upi", "pos", "si", "ach", "imps", "bbps", "trf", "atm", "ecs", "nach", "rtgs", "dr", "cr")

    /**
     * Some statements print a category after an UPPER-CASE narration ("HOME LOAN EMI Loan EMI ACH"). When
     * every word after the all-caps part is a known column word, drop them.
     */
    private fun cutCategorySuffix(raw: String): String {
        val tokens = raw.trim().split(Regex("\\s+"))
        fun hasLower(t: String) = t.any { it.isLowerCase() }
        val firstLower = tokens.indexOfFirst { hasLower(it) }
        if (firstLower <= 0 || tokens.take(firstLower).any { hasLower(it) }) return raw
        val tail = tokens.drop(firstLower)
        return if (tail.all { it.lowercase().trim('·', '•') in columnWords }) tokens.take(firstLower).joinToString(" ") else raw
    }

    private fun categoryOf(tx: RawTransactionLine): String? {
        tx.category?.let { return it.lowercase() }
        // Inline category: the last words of the narration (after the reference was removed)
        val tokens = tx.description.lowercase().split(Regex("[^a-z&]+")).filter { it.isNotEmpty() }
        for (n in 2 downTo 1) {
            if (tokens.size >= n) {
                val phrase = tokens.takeLast(n).joinToString(" ")
                if (phrase in spendingCategories) return phrase
            }
        }
        // "SI" / "ACH" style mode words can follow the category
        val trimmed = tokens.dropLastWhile { it in setOf("neft", "upi", "pos", "si", "ach", "imps", "bbps", "trf", "atm", "ecs", "nach") }
        for (n in 2 downTo 1) {
            if (trimmed.size >= n) {
                val phrase = trimmed.takeLast(n).joinToString(" ")
                if (phrase in spendingCategories) return phrase
            }
        }
        return null
    }

    private val knownSubscriptions = setOf("Netflix", "Spotify Premium", "Disney+ Hotstar", "Tata Play", "Cult.fit")

    override fun detectRecurring(transactions: List<RawTransactionLine>): List<ParsedMandateCandidate> {
        // Debits under ₹10 are verification or test transfers, not subscriptions (unless marked as a mandate)
        val debits = transactions.filter {
            it.isDebit && it.debitAmount > 0.0 &&
                (it.debitAmount >= MIN_SUBSCRIPTION_AMOUNT || mandateKeywords.any { k -> it.description.contains(k, ignoreCase = true) })
        }
        val candidates = mutableListOf<ParsedMandateCandidate>()

        // The statement's own category says groceries, shopping, dining...: habits, not subscriptions,
        // unless the narration carries explicit mandate wording (AUTOPAY, SI-, NACH...)
        val relevant = debits.filter { tx ->
            val mandate = mandateKeywords.any { tx.description.contains(it, ignoreCase = true) }
            // Cash withdrawals repeat but are never a subscription
            if (!mandate && cashPattern.containsMatchIn(tx.description)) return@filter false
            val cat = categoryOf(tx)
            cat == null || cat !in spendingCategories || mandateKeywords.any { tx.description.contains(it, ignoreCase = true) }
        }

        // Group by normalized merchant name
        val grouped = relevant.groupBy { normalizeMerchantName(it.description) }

        for ((normalizedMerchant, fullGroup) in grouped) {
            // One merchant can mix a subscription with ordinary purchases (a Prime fee among shopping);
            // when the amounts do not agree, look for the subset that does
            val txGroup = repeatingSubset(fullGroup) ?: fullGroup
            if (normalizedMerchant.isBlank() || normalizedMerchant.length < 2) continue
            // Cash withdrawals repeat but are not payments to a subscription
            if (Regex("""(?i)\b(atm|cash withdrawal|cash wdl)\b""").containsMatchIn(normalizedMerchant) &&
                fullGroup.none { tx -> mandateKeywords.any { tx.description.contains(it, ignoreCase = true) } }
            ) continue

            val hasMandatePrefix = txGroup.any { tx ->
                mandateKeywords.any { keyword -> tx.description.contains(keyword, ignoreCase = true) }
            }

            val rail = detectRail(txGroup.first().description)
            val last4 = extractAccountLast4(txGroup.first().description)

            if (txGroup.size >= 2) {
                // Sort by transaction timestamp
                val sorted = txGroup.sortedBy { it.dateEpochMs }
                val amounts = sorted.map { it.debitAmount }
                val minAmt = amounts.minOrNull() ?: 0.0
                val maxAmt = amounts.maxOrNull() ?: 0.0

                // Check amount variance: within ±10%
                val isAmountSimilar = if (maxAmt > 0) (maxAmt - minAmt) / maxAmt <= 0.10 else false

                // Check regular intervals between consecutive debits
                val intervalsDays = mutableListOf<Long>()
                for (i in 0 until sorted.size - 1) {
                    val diffMs = sorted[i + 1].dateEpochMs - sorted[i].dateEpochMs
                    val days = diffMs / (24 * 3600 * 1000)
                    if (days > 0) intervalsDays.add(days)
                }

                val avgInterval = if (intervalsDays.isNotEmpty()) intervalsDays.average() else 30.0

                val detectedFrequency = when {
                    avgInterval in 5.0..9.0 -> Frequency.WEEKLY
                    avgInterval in 24.0..36.0 -> Frequency.MONTHLY
                    avgInterval in 75.0..105.0 -> Frequency.QUARTERLY
                    avgInterval in 340.0..390.0 -> Frequency.YEARLY
                    hasMandatePrefix -> Frequency.MONTHLY
                    else -> Frequency.MONTHLY
                }

                val isRegularInterval = avgInterval in 5.0..9.0 ||
                        avgInterval in 24.0..36.0 ||
                        avgInterval in 75.0..105.0 ||
                        avgInterval in 340.0..390.0

                // A known subscription service on a regular cycle counts even when the price changed
                val knownSubscriptionOnCycle = normalizedMerchant in knownSubscriptions && isRegularInterval

                // Two similar debits that are not a week/month/quarter/year apart are just repeat purchases
                val nearIdentical = (maxAmt - minAmt) <= maxAmt * 0.02
                val steadySpacing = intervalSpread(intervalsDays) <= MAX_CYCLE_SPREAD_DAYS
                // Two debits must match almost exactly; more can drift 10% but must keep a steady rhythm
                val similarOnCycle = isAmountSimilar && isRegularInterval &&
                    (if (txGroup.size == 2) nearIdentical else steadySpacing)

                // Bills (electricity, broadband, postpaid) repeat on a steady cycle but the amount changes
                val steadyCycle = txGroup.size >= 3 && isRegularInterval && intervalSpread(intervalsDays) <= MAX_CYCLE_SPREAD_DAYS &&
                    maxAmt <= minAmt * MAX_BILL_SWING
                // An amount that keeps changing only counts when it looks like a bill (utilities, loan, insurance...)
                val billLike = txGroup.any { tx -> billPattern.containsMatchIn(tx.description) || categoryOf(tx) in billCategories }
                val variableBill = steadyCycle && billLike && !(similarOnCycle || hasMandatePrefix || knownSubscriptionOnCycle)

                if (similarOnCycle || hasMandatePrefix || knownSubscriptionOnCycle || variableBill) {
                    val avgAmount = Math.round(amounts.average() * 100.0) / 100.0
                    val confidence = when {
                        variableBill -> Confidence.MEDIUM
                        hasMandatePrefix && isAmountSimilar -> Confidence.HIGH
                        isAmountSimilar && isRegularInterval -> Confidence.HIGH
                        hasMandatePrefix -> Confidence.HIGH
                        isAmountSimilar -> Confidence.MEDIUM
                        else -> Confidence.LOW
                    }

                    // Estimate next debit date based on last debit date + detected interval
                    val lastDateMs = sorted.last().dateEpochMs
                    val intervalMs = when (detectedFrequency) {
                        Frequency.WEEKLY -> 7L * 24 * 3600 * 1000
                        Frequency.MONTHLY -> 30L * 24 * 3600 * 1000
                        Frequency.QUARTERLY -> 90L * 24 * 3600 * 1000
                        Frequency.YEARLY -> 365L * 24 * 3600 * 1000
                        Frequency.AS_PRESENTED -> 30L * 24 * 3600 * 1000
                    }
                    val nextDateMs = if (lastDateMs > 0) lastDateMs + intervalMs else System.currentTimeMillis() + intervalMs
                    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

                    val reason = buildString {
                        if (hasMandatePrefix) append("Narration matches Autopay/Mandate pattern. ")
                        if (variableBill) append("${txGroup.size} payments on a steady cycle, with changing amounts (like a bill). ")
                        if (isAmountSimilar) append("${txGroup.size} debits with similar amounts (±10% variance). ")
                        if (isRegularInterval) append("Debited on a regular ${detectedFrequency.displayName.lowercase()} cycle (~${avgInterval.toInt()} days). ")
                    }.trim()

                    candidates.add(
                        ParsedMandateCandidate(
                            id = UUID.randomUUID().toString(),
                            merchant = normalizedMerchant,
                            amount = avgAmount,
                            frequency = detectedFrequency,
                            nextDebitDate = sdf.format(nextDateMs),
                            nextDebitDateEpochMs = nextDateMs,
                            rail = rail,
                            source = SourceType.STATEMENT,
                            sourceApp = "Bank Statement",
                            last4 = last4,
                            confidence = confidence,
                            confidenceReason = reason,
                            kind = KindClassifier.classify(normalizedMerchant + " " + txGroup.first().description),
                            lastDebitEpochMs = sorted.last().dateEpochMs.takeIf { it > 0 }
                        )
                    )
                }
            } else if (hasMandatePrefix) {
                // A single debit containing explicit Autopay or Mandate narration
                val singleTx = txGroup.first()
                val nextDateMs = if (singleTx.dateEpochMs > 0) singleTx.dateEpochMs + (30L * 24 * 3600 * 1000) else System.currentTimeMillis() + (30L * 24 * 3600 * 1000)
                val sdf = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)

                candidates.add(
                    ParsedMandateCandidate(
                        id = UUID.randomUUID().toString(),
                        merchant = normalizedMerchant,
                        amount = singleTx.debitAmount,
                        frequency = Frequency.MONTHLY,
                        nextDebitDate = sdf.format(nextDateMs),
                        nextDebitDateEpochMs = nextDateMs,
                        rail = rail,
                        source = SourceType.STATEMENT,
                        sourceApp = "Bank Statement",
                        last4 = last4,
                        confidence = Confidence.HIGH,
                        confidenceReason = "Identified recurring Autopay/Mandate keyword in bank narration",
                        kind = KindClassifier.classify(normalizedMerchant + " " + singleTx.description),
                        lastDebitEpochMs = singleTx.dateEpochMs.takeIf { it > 0 }
                    )
                )
            }
        }

        // Return detected candidates. The raw transactions in 'transactions' list are NOT retained!
        return candidates
    }

    override fun normalizeMerchantName(rawNarration: String): String {
        var clean = cutCategorySuffix(rawNarration).uppercase().trim()

        clean = clean.replace(Regex("\\([^)]*\\)"), " ")

        // Channel words that start a narration ("ECS/SI DR - X", "UPI DR - X", "SIP DR X", "BILLPAY X")
        clean = clean.replace(Regex("^(?:(?:ECS|SI|DR|CR|UPI|POS|NACH|NEFT|EMI|SIP|BILLPAY|BILL|ATM|WDL|IMPS|RTGS|ACH|ECOM|AUTOPAY|AUTODEBIT|DEBIT|CREDIT|RECURRING)(?:[ /]+|$))+(?:[-:]+ *)?"), " ")
        clean = clean.trim()

        // 1. Strip UPI IDs (e.g. swiggy@icici, spotify@paytm, user@okaxis)
        clean = clean.replace(Regex("[a-zA-Z0-9._]+@[a-zA-Z0-9.]+"), " ")

        // 2. Strip standard Indian bank transfer prefixes
        clean = clean.replace(Regex("^((?:UPI|POS|AUTOPAY|AUTO PAY|ATM|ACH|NACH|SI|NEFT|RTGS|IMPS|ECS|EMI)\\s+[-/]\\s+|TO\\s+|FROM\\s+|TRANSFER TO\\s+[0-9]+/UPI/|UPI/CR/[0-9]+/|UPI-MANDATE-|UPI-AUTOPAY-|UPI/|UPI-|ACH D-|ACH-|ACH/|NACH-|NACH/|IB SI-|SI-|SI/|POS\\s+[0-9]+|POS/|POS-|NEFT-|RTGS-|IMPS-|BY TRANSFER-)"), " ")

        // 3. Strip alphanumeric reference codes / IFSC / Bank codes (e.g. HDFC000123, UTIR0000123, MND98124)
        clean = clean.replace(Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b"), " ")
        clean = clean.replace(Regex("\\bMND[0-9A-Z]+\\b"), " ")

        // 4. Strip reference/UTR numbers (5 or more digits)
        clean = clean.replace(Regex("\\b[0-9]{5,}\\b"), " ")

        // 5. Strip delimiter characters
        clean = clean.replace(Regex("[-/|:_]+"), " ")

        // 6. Strip corporate suffixes, cities, and transaction keywords
        clean = clean.replace(
            Regex("\\b(PVT|LTD|LIMITED|PRIVATE|LLP|INC|CORP|INDIA|IN|MUMBAI|DELHI|BANGALORE|BENGALURU|HYDERABAD|CHENNAI|AUTO|DEBIT|PAYMENT|FROM|BILL|TECH|TECHNOLOGY|TECHNOLOGIES|INVEST|BROKING|SERVICES|HEALTHCARE|ORDER|DELIVERY)\\b"),
            " "
        )

        // 7. Remove remaining standalone numbers or short noise
        clean = clean.replace(Regex("\\b[0-9]+\\b"), " ")
        clean = clean.trim().replace(Regex("\\s+"), " ")

        // Bullets separate parts of a narration
        clean = clean.replace(Regex("[·•]"), " ").trim()
        // Trailing mode words (UPI, NEFT, SI...) are the payment channel, not the merchant
        val words = clean.split(" ").filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last().lowercase() in modeWords) words.removeAt(words.lastIndex)
        clean = words.joinToString(" ")

        // Known merchant mapping
        val merchantDisplayMap = mapOf(
            "NETFLIX" to "Netflix",
            "SPOTIFY" to "Spotify Premium",
            "HOTSTAR" to "Disney+ Hotstar",
            "DISNEY HOTSTAR" to "Disney+ Hotstar",
            "AMAZON" to "Amazon Prime",
            "SWIGGY" to "Swiggy One",
            "ZOMATO" to "Zomato Gold",
            "CULT FIT" to "Cult.fit",
            "CUREFIT" to "Cult.fit",
            "ZERODHA" to "Zerodha Coin (SIP)",
            "GROWW" to "Groww (SIP)",
            "BESCOM" to "BESCOM Electricity",
            "AIRTEL" to "Airtel Postpaid",
            "JIO" to "Jio Fiber",
            "TATA PLAY" to "Tata Play",
            "TATAPLAY" to "Tata Play",
            "NIPPON" to "Nippon India Mutual Fund",
            "APPLE" to "Apple Services"
        )

        for ((key, displayName) in merchantDisplayMap) {
            // Whole-word match only ("JIOSAAVN PRO" is not "JIO"). Brands that run several products
            // (Jio, Airtel, Apple, Amazon) are renamed only when nothing but filler follows the brand,
            // so "APPLE ARCADE" is not relabelled "Apple Services".
            val rest = clean.replace(Regex("\\b" + Regex.escape(key) + "\\b"), " ").trim()
            if (rest == clean.trim()) continue
            val ambiguous = key in ambiguousBrands
            if (!ambiguous || rest.split(" ").filter { it.isNotEmpty() }.all { it in fillerWords }) {
                return displayName
            }
        }

        // Title case formatting if not in explicit dictionary
        return clean.lowercase().split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        }.ifBlank { "Bank Mandate" }
    }

    private fun detectRail(desc: String): PaymentRail {
        val upper = desc.uppercase()
        return when {
            upper.contains("UPI") -> PaymentRail.UPI
            upper.contains("ACH") || upper.contains("NACH") -> PaymentRail.NACH
            upper.contains("SI-") || upper.contains("SI/") || upper.contains("STANDING") -> PaymentRail.SI
            upper.contains("POS") || upper.contains("CARD") -> PaymentRail.CARD
            else -> PaymentRail.OTHER
        }
    }

    private fun extractAccountLast4(desc: String): String? {
        val match = Regex("(?:[X*•]{2,}|\\bA/C\\s*)([0-9]{4})\\b", RegexOption.IGNORE_CASE).find(desc)
        return match?.groupValues?.get(1)
    }
}
