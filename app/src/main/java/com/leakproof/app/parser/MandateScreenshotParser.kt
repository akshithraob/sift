package com.leakproof.app.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import kotlin.math.abs

interface MandateScreenshotParser {
    fun parseOcrDocument(document: OcrDocument, sourceAppHint: String? = null): List<ParsedMandateCandidate>
    fun parseOcrText(ocrText: String, sourceAppHint: String? = null): List<ParsedMandateCandidate>
    fun mergeDuplicates(candidates: List<ParsedMandateCandidate>): List<ParsedMandateCandidate>
}

class DefaultMandateScreenshotParser : MandateScreenshotParser {

    private val knownMerchants = listOf(
        "Netflix India", "Netflix", "Spotify Premium", "Spotify", "Disney+ Hotstar", "Hotstar",
        "Amazon Prime", "Prime Video", "YouTube Premium", "Swiggy One", "Swiggy",
        "Zomato Gold", "Zomato", "Cult.fit", "Curefit", "Zerodha Coin", "Zerodha",
        "Groww SIP", "Groww", "Airtel Postpaid", "Airtel", "Jio Fiber", "Jio",
        "Tata Play", "Google One", "Apple Services", "Apple", "Blinkit", "Zepto"
    )

    private val ignoredUiWords = setOf(
        "autopay", "upi autopay", "mandates", "live", "active", "pending", "paused", "cancelled",
        "completed", "manage", "automatic payments", "frequency", "debited", "account",
        "starting", "ending", "next payment", "next debit", "view details", "modify",
        "cancel autopay", "pause autopay", "history", "upi", "mandate", "details"
    )

    override fun parseOcrDocument(document: OcrDocument, sourceAppHint: String?): List<ParsedMandateCandidate> {
        val nonBlank = document.allLines.filter { it.text.isNotBlank() }
        parsePlayStoreSubscriptions(nonBlank)?.let { return it }
        // Detect the source app from the whole screen, including header and promo text
        val detectedApp = detectSourceApp(nonBlank.joinToString("\n") { it.text }, sourceAppHint)
        val allLines = nonBlank.filterNot { isNoiseLine(it.text) }
        if (allLines.isEmpty()) {
            return emptyList()
        }

        // Group lines into cards using spatial vertical distance or blocks
        val cardGroups = groupLinesIntoCards(allLines)
        val candidates = mutableListOf<ParsedMandateCandidate>()

        for (group in cardGroups) {
            val candidate = parseCardLines(group, detectedApp)
            if (candidate != null) {
                candidates.add(candidate)
            }
        }

        // If card grouping produced nothing (e.g. single mandate detail screen or unstructured text),
        // fallback to parsing the document as a single mandate
        if (candidates.isEmpty()) {
            val fallback = parseCardLines(allLines, detectedApp)
            if (fallback != null) {
                candidates.add(fallback)
            }
        }

        return mergeDuplicates(candidates)
    }

    override fun parseOcrText(ocrText: String, sourceAppHint: String?): List<ParsedMandateCandidate> {
        if (ocrText.isBlank()) return emptyList()

        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Synthesize an OcrDocument with approximate vertical coordinates based on line order
        var currentY = 100
        val ocrLines = lines.map { text ->
            val line = OcrTextLine(
                text = text,
                boundingBox = OcrBoundingBox(left = 50, top = currentY, right = 500, bottom = currentY + 30)
            )
            currentY += 40
            line
        }
        val doc = OcrDocument(blocks = listOf(OcrTextBlock(text = ocrText, lines = ocrLines)))
        return parseOcrDocument(doc, sourceAppHint)
    }

    /**
     * Groups OCR text lines into cards based on spatial coordinates and layout gaps.
     */
    private fun groupLinesIntoCards(lines: List<OcrTextLine>): List<List<OcrTextLine>> {
        // Sort lines from top to bottom
        val sortedLines = lines.sortedBy { it.boundingBox?.top ?: 0 }
        val cards = mutableListOf<MutableList<OcrTextLine>>()
        var currentCard = mutableListOf<OcrTextLine>()

        for (i in sortedLines.indices) {
            val line = sortedLines[i]
            val text = line.text.trim()

            // Skip top screen header lines like "Autopay", "Manage Autopay", "Back"
            if (i < 3 && (text.equals("Autopay", ignoreCase = true) ||
                        text.equals("Manage Autopay", ignoreCase = true) ||
                        text.equals("Automatic Payments", ignoreCase = true) ||
                        text.equals("UPI Mandates", ignoreCase = true))
            ) {
                continue
            }

            if (currentCard.isEmpty()) {
                currentCard.add(line)
            } else {
                val prevLine = currentCard.last()
                val prevBottom = prevLine.boundingBox?.bottom ?: 0
                val currTop = line.boundingBox?.top ?: 0

                val verticalGap = currTop - prevBottom
                val isNewMerchantStart = isLikelyMerchantLine(text) && currentCard.any { hasAmount(it.text) }

                // If gap is unusually large (e.g. > 70px) and current card already contains amount/merchant,
                // or if a clear new merchant starts after an amount, begin new card
                if ((verticalGap > 75 && currentCard.size >= 2) || isNewMerchantStart) {
                    cards.add(currentCard)
                    currentCard = mutableListOf(line)
                } else {
                    currentCard.add(line)
                }
            }
        }

        if (currentCard.isNotEmpty()) {
            cards.add(currentCard)
        }

        return cards
    }

    private fun parseCardLines(cardLines: List<OcrTextLine>, detectedApp: String): ParsedMandateCandidate? {
        val combinedText = cardLines.joinToString("\n") { it.text }
        val textLines = cardLines.map { it.text.trim() }

        // 1. Amount and Max Amount Extraction
        val (amount, maxAmount) = extractAmountAndMax(combinedText)
        if (amount == 0.0 && maxAmount == null) {
            return null // A mandate requires a detectable amount or max limit
        }

        // 2. Merchant Name Extraction
        val rawMerchant = extractMerchantFromLayout(cardLines)
            ?: extractMerchant(textLines, combinedText)
        // Play Store mandates list the merchant as plain "GOOGLE" with a playstore VPA beneath
        val merchant = if (rawMerchant.equals("Google", ignoreCase = true) &&
            textLines.any { it.contains("playstore", ignoreCase = true) }
        ) "Google Play" else rawMerchant

        // 3. Frequency Extraction
        val frequency = extractFrequency(combinedText)

        // 4. Dates Extraction
        val (nextDate, nextDateMs, startDate, endDate) = extractDates(combinedText)

        // 5. Account Last 4 Digits
        val last4 = extractLast4Digits(combinedText)

        val finalAmount = if (amount > 0.0) amount else (maxAmount ?: 0.0)

        // Confidence calculation
        val hasKnownMerchant = knownMerchants.any { merchant.contains(it, ignoreCase = true) }
        val hasValidDate = nextDate != null || startDate != null
        val confidence = when {
            hasKnownMerchant && finalAmount > 0.0 && hasValidDate -> Confidence.HIGH
            finalAmount > 0.0 && (hasKnownMerchant || hasValidDate) -> Confidence.HIGH
            finalAmount > 0.0 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val reason = buildString {
            append("Detected in $detectedApp. ")
            if (merchant == UNNAMED_PLAY_MANDATE) {
                append("Google Pay doesn't show which app this is: import your Play Store subscriptions screenshot too, or tap Edit to name it. ")
            }
            if (maxAmount != null && maxAmount > amount && amount > 0) {
                append("Current debit: ₹${formatRupees(amount)} (Max: ₹${formatRupees(maxAmount)}). ")
            } else if (maxAmount != null) {
                append("Mandate limit up to ₹${formatRupees(maxAmount)}. ")
            }
            if (nextDate != null) append("Next debit scheduled for $nextDate. ")
            if (last4 != null) append("Linked to account ending in $last4. ")
        }.trim()

        return ParsedMandateCandidate(
            id = UUID.randomUUID().toString(),
            merchant = merchant,
            amount = finalAmount,
            maxAmount = maxAmount,
            frequency = frequency,
            startDate = startDate,
            endDate = endDate,
            nextDebitDate = nextDate,
            nextDebitDateEpochMs = nextDateMs,
            rail = PaymentRail.UPI,
            source = SourceType.SCREENSHOT,
            sourceApp = detectedApp,
            last4 = last4,
            confidence = confidence,
            confidenceReason = reason
        )
    }

    private fun detectSourceApp(text: String, hint: String?): String {
        if (!hint.isNullOrBlank()) return hint
        val lower = text.lowercase()
        return when {
            lower.contains("google pay") || lower.contains("gpay") -> "Google Pay"
            lower.contains("phonepe") -> "PhonePe"
            lower.contains("paytm") -> "Paytm"
            lower.contains("bhim") -> "BHIM UPI"
            lower.contains("hdfc") -> "HDFC Bank"
            lower.contains("icici") -> "ICICI Bank"
            lower.contains("sbi") || lower.contains("yono") -> "SBI YONO"
            else -> "UPI App"
        }
    }

    private fun extractAmountAndMax(text: String): Pair<Double, Double?> {
        var detectedAmount = 0.0
        var detectedMax: Double? = null

        // 1. Check for "Up to ₹X", "Max Amount: ₹X", "Maximum: ₹X"
        val maxPattern = Pattern.compile(
            "(?:up\\s*to|max(?:imum)?(?:\\s*amount)?|limit)\\s*[:\\-]?\\s*(?:[₹]|Rs\\.?|INR)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE
        )
        val maxMatcher = maxPattern.matcher(text)
        if (maxMatcher.find()) {
            val str = maxMatcher.group(1)?.replace(",", "") ?: ""
            detectedMax = str.toDoubleOrNull()
        }

        // 2. Check for general amounts (₹X, Rs. X, INR X)
        val generalPattern = Pattern.compile(
            "(?:[₹]|Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = generalPattern.matcher(text)
        val foundAmounts = mutableListOf<Double>()
        while (matcher.find()) {
            val amt = matcher.group(1)?.replace(",", "")?.toDoubleOrNull()
            if (amt != null && amt > 0) {
                foundAmounts.add(amt)
            }
        }

        if (foundAmounts.isNotEmpty()) {
            // If we found multiple amounts, usually the smaller one is current debit and larger is max
            if (foundAmounts.size >= 2) {
                val sorted = foundAmounts.sorted()
                detectedAmount = sorted.first()
                if (detectedMax == null) {
                    detectedMax = sorted.last()
                }
            } else {
                detectedAmount = foundAmounts.first()
            }
        } else if (detectedMax != null) {
            detectedAmount = detectedMax
        }

        return Pair(detectedAmount, detectedMax)
    }

    private fun formatRupees(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ENGLISH, "%.2f", value)

    private fun hasAmount(text: String): Boolean {
        return text.contains("₹") || text.contains("Rs", ignoreCase = true) || text.contains("INR", ignoreCase = true)
    }

    private fun extractMerchant(lines: List<String>, fullText: String): String {
        val lowerFull = fullText.lowercase()

        // Check if a known merchant is explicitly mentioned
        for (m in knownMerchants) {
            if (lowerFull.contains(m.lowercase())) {
                return m
            }
        }

        // A Play Store mandate shows the merchant as "GOOGLE" with a playstore VPA beneath it
        if (lines.any { it.contains("playstore", ignoreCase = true) }) {
            return "Google Play"
        }

        // Otherwise, inspect the lines in the card and pick the most prominent non-UI line
        for (line in lines) {
            val clean = line.replace(Regex("^[•*\\->\\s]+"), "").trim()
            if (isPlausibleMerchantText(clean)) {
                return tidyCase(clean)
            }
        }

        return "UPI Autopay Mandate"
    }


    private val noisePhrases = listOf(
        "automatic payments", "set up once", "never miss a deadline", "how to use autopay",
        "easily manage autopay", "other apps on google pay", "transfer now"
    )

    private fun isNoiseLine(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in setOf("live", "pending", "completed") || noisePhrases.any { lower.contains(it) }
    }

    private fun isPlausibleMerchantText(clean: String): Boolean {
        val lower = clean.lowercase()
        return clean.length in 3..40 &&
            !hasAmount(clean) &&
            !clean.contains('@') &&
            !ignoredUiWords.contains(lower) &&
            !isDateString(clean) &&
            !lower.startsWith("account") &&
            !lower.startsWith("ending in") &&
            !lower.startsWith("next") &&
            !lower.startsWith("debited") &&
            !lower.startsWith("up to") &&
            !lower.startsWith("max") &&
            !lower.startsWith("limit") &&
            !lower.startsWith("autopay") &&
            !lower.contains("payment completed") &&
            !lower.startsWith("as presented") &&
            clean.count { it.isLetter() } > clean.count { it.isDigit() }
    }

    // ALL-CAPS merchant names from some apps ("GOOGLE") read better in title case
    private fun tidyCase(name: String): String {
        val letters = name.filter { it.isLetter() }
        if (letters.isEmpty() || letters != letters.uppercase()) return name
        return name.lowercase().split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        }
    }

    /**
     * Uses bounding boxes: in list layouts the merchant sits on the same row as the amount,
     * to its left (e.g. "GOOGLE ........ Up to ₹1,999"). Returns null when no such row exists.
     */
    private fun extractMerchantFromLayout(cardLines: List<OcrTextLine>): String? {
        val amountLine = cardLines.firstOrNull { hasAmount(it.text) || it.text.contains("up to", ignoreCase = true) }
        val amountBox = amountLine?.boundingBox ?: return null
        val candidates = cardLines.filter { line ->
            val box = line.boundingBox
            line !== amountLine && box != null &&
                abs(box.centerY - amountBox.centerY) <= maxOf(box.height, amountBox.height) / 2 &&
                box.left < amountBox.left &&
                isPlausibleMerchantText(line.text.trim())
        }
        val best = candidates.minByOrNull { it.boundingBox!!.left } ?: return null
        return tidyCase(best.text.trim())
    }

    private fun isLikelyMerchantLine(text: String): Boolean {
        val lower = text.lowercase().trim()
        return knownMerchants.any { lower.contains(it.lowercase()) }
    }

    private fun extractFrequency(text: String): Frequency {
        val lower = text.lowercase()
        return when {
            lower.contains("yearly") || lower.contains("annual") || lower.contains("/ yr") || lower.contains("/ year") -> Frequency.YEARLY
            lower.contains("weekly") || lower.contains("/ wk") || lower.contains("/ week") -> Frequency.WEEKLY
            lower.contains("quarterly") || lower.contains("/ qtr") -> Frequency.QUARTERLY
            lower.contains("as presented") || lower.contains("as and when") -> Frequency.AS_PRESENTED
            lower.contains("daily") -> Frequency.AS_PRESENTED
            else -> Frequency.MONTHLY
        }
    }

    private data class DateExtractResult(
        val nextDate: String?,
        val nextDateMs: Long?,
        val startDate: String?,
        val endDate: String?
    )

    private fun extractDates(text: String): DateExtractResult {
        // Pattern 1: "17 Oct 2026", "24-Oct-2026", "15 November 2026"
        val alphaDatePattern = Pattern.compile(
            "\\b([0-3]?[0-9])[\\s\\-/]+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)[\\s\\-/]+(20[2-3][0-9])\\b",
            Pattern.CASE_INSENSITIVE
        )

        // Pattern 2: "17/10/2026", "17-10-2026"
        val numericDatePattern = Pattern.compile(
            "\\b([0-3]?[0-9])[\\-/]([0-1]?[0-9])[\\-/](20[2-3][0-9])\\b"
        )

        val foundDates = mutableListOf<Pair<String, Long>>()

        val alphaMatcher = alphaDatePattern.matcher(text)
        while (alphaMatcher.find()) {
            val dateStr = alphaMatcher.group(0) ?: ""
            val ms = parseDateStrToMillis(dateStr)
            foundDates.add(Pair(dateStr, ms))
        }

        val numericMatcher = numericDatePattern.matcher(text)
        while (numericMatcher.find()) {
            val dateStr = numericMatcher.group(0) ?: ""
            val ms = parseDateStrToMillis(dateStr)
            foundDates.add(Pair(dateStr, ms))
        }

        var nextDate: String? = null
        var nextDateMs: Long? = null
        var startDate: String? = null
        var endDate: String? = null

        val now = System.currentTimeMillis()
        val futureDates = foundDates.filter { it.second >= now - (24 * 3600 * 1000) }.sortedBy { it.second }
        val pastDates = foundDates.filter { it.second < now }.sortedBy { it.second }

        if (futureDates.isNotEmpty()) {
            nextDate = futureDates.first().first
            nextDateMs = futureDates.first().second
            if (futureDates.size > 1) {
                endDate = futureDates.last().first
            }
        } else if (foundDates.isNotEmpty()) {
            nextDate = foundDates.first().first
            nextDateMs = foundDates.first().second
        }

        if (pastDates.isNotEmpty()) {
            startDate = pastDates.first().first
        }

        if (text.contains("until revoked", ignoreCase = true) || text.contains("until cancelled", ignoreCase = true)) {
            endDate = "Until Revoked"
        }

        return DateExtractResult(nextDate, nextDateMs, startDate, endDate)
    }

    private fun isDateString(text: String): Boolean {
        return text.matches(Regex(".*(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|[0-9]{2}/[0-9]{2}/[0-9]{4}).*", RegexOption.IGNORE_CASE))
    }

    private fun parseDateStrToMillis(dateStr: String): Long {
        val formats = listOf(
            "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "dd MMMM yyyy",
            "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }

    private fun extractLast4Digits(text: String): String? {
        val pattern = Pattern.compile(
            "(?:account|a/c|ending(?:\\s+in)?|xx|\\*{2,}|•{2,})\\s*([0-9]{4})\\b",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        return null
    }

    override fun mergeDuplicates(candidates: List<ParsedMandateCandidate>): List<ParsedMandateCandidate> {
        val merged = mutableListOf<ParsedMandateCandidate>()

        for (candidate in candidates) {
            val normalizedNew = normalizeMerchant(candidate.merchant)
            val existingIndex = merged.indexOfFirst {
                val normExisting = normalizeMerchant(it.merchant)
                (normExisting == normalizedNew || normExisting.contains(normalizedNew) || normalizedNew.contains(normExisting)) &&
                        (abs(it.amount - candidate.amount) < 1.0 || (it.last4 != null && it.last4 == candidate.last4))
            }

            if (existingIndex >= 0) {
                // Merge candidates by picking the most informative fields
                val existing = merged[existingIndex]
                val betterMerchant = if (candidate.merchant.length > existing.merchant.length) candidate.merchant else existing.merchant
                val betterNextDate = candidate.nextDebitDate ?: existing.nextDebitDate
                val betterNextDateMs = candidate.nextDebitDateEpochMs ?: existing.nextDebitDateEpochMs
                val betterLast4 = candidate.last4 ?: existing.last4
                val betterMax = candidate.maxAmount ?: existing.maxAmount
                val betterStart = candidate.startDate ?: existing.startDate
                val betterEnd = candidate.endDate ?: existing.endDate
                val betterConfidence = if (candidate.confidence == Confidence.HIGH || existing.confidence == Confidence.HIGH) Confidence.HIGH else Confidence.MEDIUM

                merged[existingIndex] = existing.copy(
                    merchant = betterMerchant,
                    nextDebitDate = betterNextDate,
                    nextDebitDateEpochMs = betterNextDateMs,
                    last4 = betterLast4,
                    maxAmount = betterMax,
                    startDate = betterStart,
                    endDate = betterEnd,
                    confidence = betterConfidence
                )
            } else {
                merged.add(candidate)
            }
        }

        return linkPlayStoreMandates(merged)
    }

    private val playNextPayment = Pattern.compile(
        // The ₹ glyph is often dropped or misread by the Latin OCR model, so the symbol is optional
        // Play always prints two decimals and "on <date>"; that keeps a plain "Next payment: 24 Oct 2026"
        // line from a UPI app's mandate card from being mistaken for a Play subscription
        "next\\s+payment\\s*:?\\s*[^0-9\\s]{0,4}\\s*([0-9,]+\\.[0-9]{2})\\s+on\\s+" +
            "([0-3]?[0-9]\\s+[A-Za-z]{3,9}\\s+20[2-3][0-9])",
        Pattern.CASE_INSENSITIVE
    )
    private val playSectionHeadings = setOf("active", "expired", "canceled", "cancelled", "inactive", "subscriptions")

    /**
     * Google Play > Payments & subscriptions > Subscriptions screen. Unlike the Google Pay
     * Autopay list (which only says "GOOGLE"), this screen names the app and gives the real
     * price and renewal date. Returns null when the text is not that screen.
     */
    private fun parsePlayStoreSubscriptions(allLines: List<OcrTextLine>): List<ParsedMandateCandidate>? {
        val lines = allLines
            .sortedBy { it.boundingBox?.top ?: 0 }
            .map { it.text.trim() }
        if (lines.none { playNextPayment.matcher(it).find() }) return null

        var activeSection = true
        val result = mutableListOf<ParsedMandateCandidate>()
        for ((i, line) in lines.withIndex()) {
            val lower = line.lowercase()
            if (lower in playSectionHeadings) {
                activeSection = lower == "active" || lower == "subscriptions"
                continue
            }
            val m = playNextPayment.matcher(line)
            if (!m.find() || !activeSection) continue

            val amount = m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            val dateStr = m.group(2) ?: continue
            val plan = lines.getOrNull(i - 1)?.takeIf { it.lowercase() !in playSectionHeadings }
            val nameLine = lines.getOrNull(i - 2)?.takeIf { it.lowercase() !in playSectionHeadings } ?: plan ?: continue
            val merchant = nameLine.substringBefore(" by ").trim().ifBlank { continue }
            val nextMs = parseDateStrToMillis(dateStr)

            result.add(
                ParsedMandateCandidate(
                    id = UUID.randomUUID().toString(),
                    merchant = merchant,
                    amount = amount,
                    frequency = extractFrequency("${plan.orEmpty()} $line"),
                    nextDebitDate = dateStr,
                    nextDebitDateEpochMs = nextMs,
                    rail = PaymentRail.OTHER,
                    source = SourceType.SCREENSHOT,
                    sourceApp = PLAY_STORE_APP,
                    confidence = Confidence.HIGH,
                    confidenceReason = "Active Google Play subscription" +
                        (plan?.let { " ($it)" } ?: "") + ". Next payment $dateStr."
                )
            )
        }
        return mergeDuplicates(result)
    }

    /**
     * A Google Pay mandate for the Play Store is just "GOOGLE". When exactly one Play
     * subscription fits under its limit, take the real name, price and date from it.
     */
    private fun linkPlayStoreMandates(list: List<ParsedMandateCandidate>): List<ParsedMandateCandidate> {
        val unnamed = list.filter { it.merchant == UNNAMED_PLAY_MANDATE && it.sourceApp != PLAY_STORE_APP }
        val named = list.filter { it.sourceApp == PLAY_STORE_APP && it.merchant != UNNAMED_PLAY_MANDATE }
        if (unnamed.isEmpty() || named.isEmpty()) return list

        fun fits(sub: ParsedMandateCandidate, mandate: ParsedMandateCandidate) =
            sub.amount <= (mandate.maxAmount ?: mandate.amount) + 0.5

        val replacements = mutableMapOf<String, ParsedMandateCandidate>()
        val consumed = mutableSetOf<String>()
        for (mandate in unnamed) {
            val subs = named.filter { fits(it, mandate) }
            val sub = subs.singleOrNull() ?: continue
            if (unnamed.count { fits(sub, it) } != 1) continue
            replacements[mandate.id] = mandate.copy(
                merchant = sub.merchant,
                amount = sub.amount,
                frequency = sub.frequency,
                nextDebitDate = sub.nextDebitDate,
                nextDebitDateEpochMs = sub.nextDebitDateEpochMs,
                sourceApp = "Google Pay + Google Play",
                confidence = Confidence.HIGH,
                confidenceReason = "Autopay mandate found in Google Pay (limit ₹${formatRupees(mandate.maxAmount ?: mandate.amount)}); " +
                    "name, price and renewal date come from your Google Play subscriptions."
            )
            consumed.add(sub.id)
        }
        return list.filterNot { it.id in consumed }.map { replacements[it.id] ?: it }
    }

    private companion object {
        const val PLAY_STORE_APP = "Google Play"
        const val UNNAMED_PLAY_MANDATE = "Google Play"
    }

    private fun normalizeMerchant(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .replace("india", "")
            .replace("premium", "")
            .replace("autopay", "")
    }

}
