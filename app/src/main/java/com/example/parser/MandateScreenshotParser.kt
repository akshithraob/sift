package com.example.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import kotlin.math.abs

interface MandateScreenshotParser {
    fun parseOcrDocument(document: OcrDocument, sourceAppHint: String? = null): List<ParsedMandateCandidate>
    fun parseOcrText(ocrText: String, sourceAppHint: String? = null): List<ParsedMandateCandidate>
    fun mergeDuplicates(candidates: List<ParsedMandateCandidate>): List<ParsedMandateCandidate>
    fun getSampleCandidates(): List<ParsedMandateCandidate>
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
        val allLines = document.allLines.filter { it.text.isNotBlank() }
        if (allLines.isEmpty()) {
            return emptyList()
        }

        val fullText = allLines.joinToString("\n") { it.text }
        val detectedApp = detectSourceApp(fullText, sourceAppHint)

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
        val merchant = extractMerchant(textLines, combinedText)

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
            if (maxAmount != null && maxAmount > amount && amount > 0) {
                append("Current debit: ₹$amount (Max: ₹$maxAmount). ")
            } else if (maxAmount != null) {
                append("Mandate limit up to ₹$maxAmount. ")
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

        // Otherwise, inspect the lines in the card and pick the most prominent non-UI line
        for (line in lines) {
            val clean = line.replace(Regex("^[•*\\->\\s]+"), "").trim()
            val lower = clean.lowercase()

            if (clean.length in 3..40 &&
                !hasAmount(clean) &&
                !ignoredUiWords.contains(lower) &&
                !isDateString(clean) &&
                !lower.startsWith("account") &&
                !lower.startsWith("ending in") &&
                !lower.startsWith("next") &&
                !lower.startsWith("debited")
            ) {
                return clean
            }
        }

        return "UPI Autopay Mandate"
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
            lower.contains("daily") -> Frequency.WEEKLY
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

        return merged
    }

    private fun normalizeMerchant(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .replace("india", "")
            .replace("premium", "")
            .replace("autopay", "")
    }

    override fun getSampleCandidates(): List<ParsedMandateCandidate> {
        return listOf(
            ParsedMandateCandidate(
                id = UUID.randomUUID().toString(),
                merchant = "Netflix India",
                amount = 649.00,
                maxAmount = 1000.00,
                frequency = Frequency.MONTHLY,
                startDate = "15 Jan 2024",
                endDate = "Until Revoked",
                nextDebitDate = "24 Oct 2026",
                nextDebitDateEpochMs = System.currentTimeMillis() + (7L * 24 * 3600 * 1000),
                rail = PaymentRail.UPI,
                source = SourceType.SCREENSHOT,
                sourceApp = "Google Pay",
                last4 = "4821",
                confidence = Confidence.HIGH,
                confidenceReason = "Extracted from Google Pay Autopay list card. Amount ₹649 (Max ₹1000)."
            ),
            ParsedMandateCandidate(
                id = UUID.randomUUID().toString(),
                merchant = "Spotify Premium",
                amount = 119.00,
                frequency = Frequency.MONTHLY,
                startDate = "01 Mar 2024",
                endDate = "Until Revoked",
                nextDebitDate = "20 Oct 2026",
                nextDebitDateEpochMs = System.currentTimeMillis() + (3L * 24 * 3600 * 1000),
                rail = PaymentRail.UPI,
                source = SourceType.SCREENSHOT,
                sourceApp = "PhonePe",
                last4 = "9012",
                confidence = Confidence.HIGH,
                confidenceReason = "Extracted from PhonePe Autopay card. Monthly frequency on 20th."
            ),
            ParsedMandateCandidate(
                id = UUID.randomUUID().toString(),
                merchant = "Disney+ Hotstar",
                amount = 1499.00,
                maxAmount = 2000.00,
                frequency = Frequency.YEARLY,
                startDate = "15 Nov 2024",
                endDate = "15 Nov 2029",
                nextDebitDate = "15 Nov 2026",
                nextDebitDateEpochMs = System.currentTimeMillis() + (29L * 24 * 3600 * 1000),
                rail = PaymentRail.UPI,
                source = SourceType.SCREENSHOT,
                sourceApp = "Paytm",
                last4 = "3310",
                confidence = Confidence.HIGH,
                confidenceReason = "Extracted from Paytm Automatic Payments. Annual cycle ₹1,499."
            )
        )
    }
}
