package com.leakproof.app.parser

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

interface StatementParser {
    fun parseStatementLines(lines: List<String>): List<RawTransactionLine>
    fun parseCsv(csvContent: String): List<RawTransactionLine>
}

/**
 * Parses bank statement exports (CSV, or text pulled from PDFs) into raw transaction lines.
 *
 * Two layouts are handled: delimited rows with a recognisable header (CSV) and free text rows
 * where columns are only separated by whitespace (PDF text). The debit/credit direction is
 * decided from, in order: an explicit Dr/Cr marker on the amount, the change in running
 * balance, and finally wording in the narration. Raw lines are never retained by callers.
 */
class DefaultStatementParser : StatementParser {

    private companion object {
        const val YEAR_2000_MS = 946_684_800_000L
    }

    private enum class Direction { DEBIT, CREDIT }

    private data class Parsed(val tx: RawTransactionLine, val balance: Double?)

    private class Columns {
        var date = -1
        var desc = -1
        var debit = -1
        var credit = -1
        var ref = -1
        var balance = -1
        var type = -1
        var category = -1
    }

    private val balanceRowPattern = Regex(
        """(?i)\b(opening\s+balance|closing\s+balance|balance\s+b/?f|balance\s+c/?f|brought\s+forward|carried\s+forward|b/f|c/f)\b"""
    )
    private val openingPattern = Regex("""(?i)\b(opening\s+balance|balance\s+b/?f|brought\s+forward|b/f)\b""")
    private val skipLinePattern = Regex(
        """(?i)(page\s+\d+|computer\s+generated|statement\s+of|account\s+statement|registered\s+office|generated\s+on|www\.|ifsc|account\s+(no|number)|customer\s+(id|name)|total\s+(debit|credit)|does\s+not\s+require)"""
    )
    private val creditHintPattern = Regex(
        """(?i)(\bUPI/CR\b|/CR/|\bCREDIT\s+INT|\bINT\.?\s*PD|\bINTEREST\b|\bSALARY\b|\bREFUND\b|\bREVERSAL\b|\bCASHBACK\b|\bNEFT\s*CR\b|\bBY\s+TRANSFER\b|\bDEPOSIT\b)"""
    )

    // Amounts always carry two decimals in statements. A trailing Dr/Cr belongs to the amount.
    private val amountPattern = Regex(
        """(?<![\d.,/])(?<![A-Za-z0-9]-)(?:₹|Rs\.?|INR)?\s*(\d{1,3}(?:,\d{2,3})*|\d+)\.(\d{2})(?![\d.])(?:[\s,|(]*(Dr|Cr)\b\)?)?""",
        RegexOption.IGNORE_CASE
    )
    private val datePattern = Regex(
        """(?i)\b(\d{1,2}[-/.]\d{1,2}[-/.](?:20)?\d{2}|\d{1,2}[-\s](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-\s,]+(?:20)?\d{2}|20\d{2}[-/.]\d{2}[-/.]\d{2})\b"""
    )
    private val markerPattern = Regex("""(?i)\b(dr|cr)\b""")

    override fun parseStatementLines(lines: List<String>): List<RawTransactionLine> {
        val result = mutableListOf<RawTransactionLine>()
        val cols = Columns()
        var headerFound = false
        var prevBalance: Double? = null

        // Wallet and fintech statements print each transaction as a block of short lines
        val source = normaliseBlockLayout(lines.map { fixZeroTokens(it) }) ?: lines.map { fixZeroTokens(it) }
        for (line in joinQuotedLines(source)) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            val tokens = splitCsvLine(trimmed)
            if (tokens.isEmpty()) continue

            // Header row (also repeated on every page of a multi-page statement)
            if (isHeaderRow(tokens)) {
                if (!headerFound) {
                    mapColumns(tokens, cols)
                    headerFound = true
                }
                continue
            }
            if (looksLikeHeaderText(trimmed)) continue

            // Opening/closing balance rows are not transactions, but seed the running balance
            if (balanceRowPattern.containsMatchIn(trimmed)) {
                if (openingPattern.containsMatchIn(trimmed)) {
                    prevBalance = amountPattern.findAll(trimmed).lastOrNull()?.let { amountOf(it) } ?: prevBalance
                }
                continue
            }

            val parsed = if (headerFound && cols.date != -1 && cols.desc != -1) {
                parseStructuredRow(tokens, cols, prevBalance)
            } else {
                parseUnstructuredLine(trimmed, prevBalance)
            }

            if (parsed != null) {
                result.add(parsed.tx)
                prevBalance = parsed.balance ?: prevBalance
            } else if (!headerFound && result.isNotEmpty() && isContinuationLine(trimmed)) {
                // Wrapped narration in PDF text: attach to the previous transaction
                val last = result.last()
                result[result.lastIndex] = last.copy(description = "${last.description} $trimmed".trim())
            }
        }

        // Statements that spread one transaction over several lines (date and narration first, the
        // amounts a line or two later) defeat the line-by-line pass: read them as records instead
        val dated = source.count { l -> leadingDate(l.trim()) != null }
        if (result.size < 3 || dated >= 3) {
            val best = listOf(parseRecordLayout(source), parseDateHeaderLayout(source), parseCardLayout(source))
                .maxByOrNull { it.size }
            if (best != null && best.size > result.size) return best
        }

        return result
    }

    // A second (value) date at the start of the narration, e.g. "01/04/26 NEFT CR - ..."
    private val leadingDates = Regex("""^\s*(?:\d{1,2}[-/.]\d{1,2}[-/.](?:20)?\d{2}\s*)+""")

    // Reference numbers: pure digits, or letters mixed with digits (NEF2607011000, 0402SI007307, 0424ac007559)
    // An empty withdrawal or deposit column is printed "0.0" by some banks; make it a normal "0.00" amount
    private val zeroToken = Regex("""(?<![\d.,])0\.0(?![\d.])""")
    private fun fixZeroTokens(line: String) = zeroToken.replace(line, "0.00")

    // The sign in front of a figure ("-9,44,949.50" on a loan account balance)
    private fun signedValue(line: String, m: MatchResult): Double {
        val i = m.range.first - 1
        return if (i >= 0 && line[i] == '-') -amountOf(m) else amountOf(m)
    }

    /** A date that opens the line, optionally after a serial number ("111 22/04/2024 ..."). */
    private fun leadingDate(line: String): MatchResult? {
        val m = datePattern.find(line) ?: return null
        val prefix = line.substring(0, m.range.first)
        return m.takeIf { prefix.isBlank() || (prefix.length <= 6 && prefix.all { it.isDigit() || it.isWhitespace() }) }
    }

    private val refTokenPattern = Regex("""(?i)\b(?:(?=[A-Z0-9]*\d{4})(?=[A-Z0-9]*[A-Z])[A-Z0-9]{8,}|[0-9]{6,})\b""")

    /**
     * Record-based reading for any statement where a transaction starts at a line that begins with its
     * date and runs until the next such line. The amounts are taken from the last line of the record
     * that has any; debit or credit comes from a Dr/Cr marker, the running balance, or the wording.
     * Page headers and footers are removed, and so are lines that repeat across the document without
     * digits (category labels, bank name), so they do not end up in the merchant name.
     */
    private fun parseRecordLayout(lines: List<String>): List<RawTransactionLine> {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val begin = clean.indexOfFirst { looksLikeHeaderText(it) }.let { if (it < 0) 0 else it }
        val body = clean.drop(begin)
        class Record(val date: MatchResult, val parts: MutableList<String>)
        val records = mutableListOf<Record>()
        var prevBalance: Double? = null
        for (line in body) {
            if (looksLikeHeaderText(line) || skipLinePattern.containsMatchIn(line)) continue
            if (balanceRowPattern.containsMatchIn(line)) {
                if (openingPattern.containsMatchIn(line) && records.isEmpty()) {
                    prevBalance = amountPattern.findAll(line).lastOrNull()?.let { amountOf(it) } ?: prevBalance
                }
                continue
            }
            val date = leadingDate(line)
            // A line that is only two dates is a statement period ("01 Jul 2026 - 30 Sep 2026"); a transaction line
            // with a value date ("01/04/26 01/04/26 NEFT ...") has more text after them
            val isPeriodLine = date != null && datePattern.findAll(line).count() >= 2 && amountPattern.find(line) == null &&
                datePattern.replace(line, " ").replace(Regex("""(?i)(to|till|and|from|period)|[-–—·:,]"""), " ").isBlank()
            if (date != null && !isPeriodLine) {
                records.add(Record(date, mutableListOf(line.substring(date.range.last + 1).trim())))
            } else if (records.isNotEmpty()) {
                records.last().parts.add(line)
            }
        }

        // Some statements print a category label on the line above the amount ("Subscriptions", "Housing").
        // When almost every entry has one there, that line is a label; otherwise a short line up there is
        // just a wrapped word of the narration ("PREMIUM") and must stay.
        fun lineAboveAmount(r: Record): String? {
            val at = r.parts.indexOfLast { amountPattern.find(it) != null }
            return if (at >= 2) r.parts[at - 1].takeIf { p -> p.none { it.isDigit() } && p.split(" ").size <= 3 } else null
        }
        val withLine = records.count { lineAboveAmount(it) != null }
        // A category column has several different labels; one repeated word is a wrapped narration
        val labelLayout = records.isNotEmpty() && withLine >= records.size * 0.8 &&
            records.mapNotNull { lineAboveAmount(it) }.toSet().size >= 3
        val repeated: Set<String> = if (labelLayout) records.mapNotNull { lineAboveAmount(it) }.toSet() else emptySet()

        class Row(
            val date: String, val epochMs: Long, val amount: Double, val balance: Double?, val explicit: Direction?,
            val marker: String?, val narration: String, val category: String?
        )

        val rows = mutableListOf<Row>()
        for (rec in records) {
            val amountIdx = rec.parts.indexOfLast { amountPattern.find(it) != null }
            if (amountIdx < 0) continue
            val epochMs = parseDateStrToMillis(rec.date.value)
            if (epochMs == 0L) continue
            val amountLine = rec.parts[amountIdx]
            val amounts = amountPattern.findAll(amountLine).toList()
            val balance = if (amounts.size >= 2) signedValue(amountLine, amounts.last()) else null
            var direction: Direction? = null
            val chosen = when {
                amounts.size >= 3 -> {
                    val d = amountOf(amounts[amounts.size - 3])
                    val c = amountOf(amounts[amounts.size - 2])
                    when {
                        d > 0.0 && c == 0.0 -> { direction = Direction.DEBIT; amounts[amounts.size - 3] }
                        c > 0.0 && d == 0.0 -> { direction = Direction.CREDIT; amounts[amounts.size - 2] }
                        else -> amounts[amounts.size - 2]
                    }
                }
                else -> amounts[0]
            }
            val amount = amountOf(chosen)
            if (amount <= 0.0) continue

            // Anything after the amount line is page furniture (footer, next page header), not narration
            val text = rec.parts.take(amountIdx + 1).mapIndexedNotNull { i, raw ->
                if (i > 0 && raw in repeated) return@mapIndexedNotNull null
                var t = raw
                if (i == amountIdx) {
                    val sb = StringBuilder(t)
                    amounts.sortedByDescending { it.range.first }.forEach { sb.replace(it.range.first, it.range.last + 1, " ") }
                    t = sb.toString()
                }
                // A value date in brackets, e.g. "(01-Mar-2023)", is not narration
                t = datePattern.replace(t, " ").replace(Regex("""\(\s*\)"""), " ")
                t.replace(refTokenPattern, " ").replace(leadingDates, " ").replace(Regex("""\s+"""), " ").trim().ifBlank { null }
            }.joinToString(" ").replace(Regex("^[\\)\\s]+"), "").ifBlank { "Bank Transaction" }

            // "ATM26010203763  \u00b7 CASH": the bullet introduces the statement's own category
            val bulletCategory = Regex("""\s[\u00b7\u2022]\s*([A-Za-z][A-Za-z &]*)$""").find(text)
            val narration = if (bulletCategory != null) text.removeRange(bulletCategory.range).trim() else text
            rows.add(Row(rec.date.value, epochMs, amount, balance, direction, chosen.groups[3]?.value, narration.ifBlank { "Bank Transaction" },
                bulletCategory?.groupValues?.get(1)?.trim()))
        }

        // Some statements list the newest entry first: the running balance only explains an entry's
        // direction against the entry before it in time, so walk the rows oldest to newest
        val newestFirst = rows.size >= 2 && rows.first().epochMs > rows.last().epochMs
        val ordered = if (newestFirst) rows.reversed() else rows
        val out = mutableListOf<RawTransactionLine>()
        var firstIsGuess = false
        for ((i, r) in ordered.withIndex()) {
            if (i == 0 && r.explicit == null && r.marker == null && prevBalance == null) firstIsGuess = true
            val dir = r.explicit ?: resolveDirection(r.amount, r.balance, prevBalance, r.marker, r.narration, null)
            out.add(buildTx(r.date, r.narration, r.amount, dir, r.epochMs, r.category))
            prevBalance = r.balance ?: prevBalance
        }
        // The oldest entry has no earlier balance to compare with. If the same narration appears again,
        // its direction (worked out from the balances) is a better guide than the wording
        if (firstIsGuess && out.size >= 3) {
            val key = { t: String -> t.uppercase().filter { it.isLetter() || it == ' ' }.split(" ").filter { it.isNotEmpty() }.take(2).joinToString(" ") }
            val first = out[0]
            val same = out.drop(1).filter { key(it.description) == key(first.description) }
            if (same.size >= 2 && same.all { it.isDebit == same[0].isDebit } && same[0].isDebit != first.isDebit) {
                out[0] = buildTx(first.date, first.description, first.amount, if (same[0].isDebit) Direction.DEBIT else Direction.CREDIT, first.dateEpochMs, first.category)
            }
        }
        return out
    }

    private val weekdayWords = Regex("""(?i)\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*\b""")

    /** "-" , the Unicode minus and dashes mean money out; "+" means money in. */
    private fun signBefore(line: String, m: MatchResult): Direction? {
        var i = m.range.first - 1
        while (i >= 0 && line[i].isWhitespace()) i--
        return when (if (i >= 0) line[i] else ' ') {
            '+' -> Direction.CREDIT
            '-', '\u2212', '\u2013', '\u2014' -> Direction.DEBIT
            else -> null
        }
    }

    /**
     * Rows grouped under a date heading ("Friday, 01 May 2026"): the heading line holds only a date,
     * and each row beneath it has a narration and signed amounts but no date of its own.
     */
    private fun parseDateHeaderLayout(lines: List<String>): List<RawTransactionLine> {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        fun headerDate(line: String): MatchResult? {
            val d = datePattern.find(line) ?: return null
            val rest = datePattern.replace(line, " ").replace(weekdayWords, " ").replace(Regex("""[,\s]"""), "")
            return if (rest.isEmpty()) d else null
        }
        if (clean.count { headerDate(it) != null } < 3) return emptyList()

        val out = mutableListOf<RawTransactionLine>()
        var current: MatchResult? = null
        var prevBalance: Double? = null
        for (line in clean) {
            val header = headerDate(line)
            if (header != null) { current = header; continue }
            val date = current ?: run {
                if (balanceRowPattern.containsMatchIn(line) && openingPattern.containsMatchIn(line)) {
                    prevBalance = amountPattern.findAll(line).lastOrNull()?.let { amountOf(it) } ?: prevBalance
                }
                null
            } ?: continue
            if (looksLikeHeaderText(line) || skipLinePattern.containsMatchIn(line) || balanceRowPattern.containsMatchIn(line)) continue
            val amounts = amountPattern.findAll(line).toList()
            if (amounts.isEmpty()) continue
            val epochMs = parseDateStrToMillis(date.value)
            if (epochMs == 0L) continue

            val chosen = amounts.first()
            val amount = amountOf(chosen)
            val balance = if (amounts.size >= 2) amountOf(amounts.last()) else null
            if (amount <= 0.0) continue
            val text = removeRanges(line, amounts.map { it.range })
                .replace(refTokenPattern, " ").replace(Regex("""[\u2212\u2013\u2014+]"""), " ")
                .replace(Regex("""\s+"""), " ").trim().ifBlank { "Bank Transaction" }
            val direction = signBefore(line, chosen)
                ?: resolveDirection(amount, balance, prevBalance, chosen.groups[3]?.value, text, null)
            out.add(buildTx(date.value, text, amount, direction, epochMs, null))
            prevBalance = balance ?: prevBalance
        }
        return out
    }

    /**
     * Card-style rows: a narration line (often with a one-letter icon in front), a line like
     * "Mon 01 Jun · Salary · NEFT · REF123" with the date but no year, then a signed amount and a
     * "bal" line. The year comes from the statement period.
     */
    private fun parseCardLayout(lines: List<String>): List<RawTransactionLine> {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val meta = Regex("""^(?:[A-Za-z]{3,9},?\s+)?(\d{1,2})\s+([A-Za-z]{3,9})\.?\s*[\u00b7\u2022|]\s*(.+)$""")
        val metaIdx = clean.indices.filter { meta.matches(clean[it]) && it > 0 }
        if (metaIdx.size < 3) return emptyList()

        // Statement period: the first line holding two full dates ("01 Jun 2026 - 31 Aug 2026")
        val period = clean.firstOrNull { datePattern.findAll(it).count() >= 2 }
            ?.let { datePattern.find(it)?.value }?.let { parseDateStrToMillis(it) }?.takeIf { it > 0 }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = period ?: System.currentTimeMillis() }
        val startYear = cal.get(java.util.Calendar.YEAR)
        val startMonth = cal.get(java.util.Calendar.MONTH)
        val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        val out = mutableListOf<RawTransactionLine>()
        var prevBalance: Double? = clean.firstOrNull { openingPattern.containsMatchIn(it) || it.startsWith("Starting balance", true) }
            ?.let { amountPattern.findAll(it).lastOrNull() }?.let { amountOf(it) }
        for (i in metaIdx) {
            val m = meta.matchEntire(clean[i]) ?: continue
            val day = m.groupValues[1]
            val monthIdx = monthNames.indexOf(m.groupValues[2].take(3).lowercase())
            if (monthIdx < 0) continue
            val year = if (monthIdx < startMonth) startYear + 1 else startYear
            val dateStr = "$day ${monthNames[monthIdx].replaceFirstChar { it.titlecase(Locale.ENGLISH) }} $year"
            val epochMs = parseDateStrToMillis(dateStr)
            if (epochMs == 0L) continue

            val amountIdx = (i + 1..minOf(i + 3, clean.lastIndex)).firstOrNull { amountPattern.find(clean[it]) != null } ?: continue
            val amountLine = clean[amountIdx]
            val amounts = amountPattern.findAll(amountLine).toList()
            val chosen = amounts.first()
            val amount = amountOf(chosen)
            if (amount <= 0.0) continue
            val balLine = clean.getOrNull(amountIdx + 1)?.takeIf { it.startsWith("bal", true) }
            val balance = (balLine ?: amountLine).let { amountPattern.findAll(it).lastOrNull() }
                ?.takeIf { balLine != null || amounts.size >= 2 }?.let { amountOf(it) }

            val narration = clean[i - 1].replace(Regex("""^[A-Za-z]\s+(?=\S)"""), "")
                .replace(refTokenPattern, " ").replace(Regex("""\s+"""), " ").trim().ifBlank { "Bank Transaction" }
            val category = m.groupValues[3].split('\u00b7', '\u2022', '|').map { it.trim() }.firstOrNull()?.ifBlank { null }
            val direction = signBefore(amountLine, chosen)
                ?: resolveDirection(amount, balance, prevBalance, null, narration, null)
            out.add(buildTx(dateStr, narration, amount, direction, epochMs, category))
            prevBalance = balance ?: prevBalance
        }
        return out
    }

    private fun removeRanges(line: String, ranges: List<IntRange>): String {
        val sb = StringBuilder(line)
        ranges.sortedByDescending { it.first }.forEach { sb.replace(it.first, it.last + 1, " ") }
        return sb.toString()
    }

    private fun buildTx(date: String, text: String, amount: Double, direction: Direction, epochMs: Long, category: String?) =
        RawTransactionLine(
            date = date,
            description = text,
            debitAmount = if (direction == Direction.DEBIT) amount else 0.0,
            creditAmount = if (direction == Direction.CREDIT) amount else 0.0,
            isDebit = direction == Direction.DEBIT,
            dateEpochMs = epochMs,
            category = category
        )

    private val dateOnlyPattern = Regex("""^\d{1,2}\s+[A-Za-z]{3,9}\.?\s+(?:20)?\d{2}$""")
    private val timeOnlyPattern = Regex("""(?i)^\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?$""")
    private val amountTailPattern = Regex("""^₹?\s*[\d,]+\.\d{2}(?:\s+₹?\s*[\d,]+\.\d{2})?$""")

    /**
     * Some statements (UPI wallets and neo-banks) do not use one line per transaction. Each one is a
     * block: a date line, a time line, "To <payee>" or "From <payee>", reference lines, then an
     * "amount closing-balance" line. Returns those blocks as single lines
     * ("16 Oct 2026 To Payee 482.00 Dr 0.00"), or null when the text is not laid out that way.
     * "To" is a debit and "From" a credit. Reference numbers and UPI IDs are dropped here.
     */
    private fun normaliseBlockLayout(lines: List<String>): List<String>? {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val starts = clean.indices.filter { i ->
            dateOnlyPattern.matches(clean[i]) && clean.getOrNull(i + 1)?.let { timeOnlyPattern.matches(it) } == true
        }
        if (starts.size < 3) return null

        val out = mutableListOf<String>()
        for ((k, start) in starts.withIndex()) {
            val end = if (k + 1 < starts.size) starts[k + 1] else clean.size
            val body = clean.subList(start + 2, end)
                .filterNot { skipLinePattern.containsMatchIn(it) || looksLikeHeaderText(it) }
            val narration = body.firstOrNull { it.startsWith("To ", true) || it.startsWith("From ", true) }
                ?: body.firstOrNull { !amountTailPattern.matches(it) && !it.contains(':') }
                ?: continue
            val amountLine = body.lastOrNull { amountTailPattern.matches(it) } ?: continue
            val marker = when {
                narration.startsWith("To ", true) -> " Dr"
                narration.startsWith("From ", true) -> " Cr"
                else -> ""
            }
            val parts = amountLine.split(Regex("""\s+"""))
            out.add("${clean[start]} $narration ${parts[0]}$marker ${parts.drop(1).joinToString(" ")}".trim())
        }
        return out
    }

    override fun parseCsv(csvContent: String): List<RawTransactionLine> {
        return parseStatementLines(csvContent.lines())
    }

    // A quoted field may contain a line break; stitch those physical lines back together
    private fun joinQuotedLines(lines: List<String>): List<String> {
        val joined = mutableListOf<String>()
        val pending = StringBuilder()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(line)
            if (pending.count { it == '"' } % 2 == 0) {
                joined.add(pending.toString())
                pending.clear()
            }
        }
        if (pending.isNotEmpty()) joined.add(pending.toString())
        return joined
    }

    private fun isHeaderRow(tokens: List<String>): Boolean {
        if (tokens.size < 3) return false
        val lower = tokens.map { it.lowercase() }
        val hasDate = lower.any { it.contains("date") || it.trim() == "dt" }
        val hasDesc = lower.any { it.isDescHeader() }
        val hasAmount = lower.any { it.contains("debit") || it.contains("withdrawal") || it.contains("amount") || it.contains("credit") || it.contains("deposit") }
        return hasDate && (hasDesc || hasAmount)
    }

    private fun String.isDescHeader() =
        contains("narration") || contains("description") || contains("particular") ||
            contains("remark") || contains("details") || contains("payee") || contains("merchant") ||
            contains("beneficiary")

    // PDF text puts a header on one whitespace-separated line instead of in CSV cells
    private fun looksLikeHeaderText(line: String): Boolean {
        val lower = line.lowercase()
        return lower.isDescHeader() && (lower.contains("debit") || lower.contains("withdrawal") || lower.contains("balance"))
    }

    private fun mapColumns(tokens: List<String>, cols: Columns) {
        for (i in tokens.indices) {
            val lower = tokens[i].lowercase()
            val compact = lower.replace(" ", "")
            when {
                cols.balance == -1 && lower.contains("balance") -> cols.balance = i
                cols.type == -1 && compact in setOf("dr/cr", "cr/dr", "drcr", "type", "txntype") -> cols.type = i
                cols.date == -1 && lower.contains("date") -> cols.date = i
                cols.desc == -1 && lower.isDescHeader() -> cols.desc = i
                cols.debit == -1 && (lower.contains("debit") || lower.contains("withdraw") || markerPattern.containsMatchIn(lower) && lower.contains("dr")) -> cols.debit = i
                cols.credit == -1 && (lower.contains("credit") || lower.contains("deposit") || markerPattern.containsMatchIn(lower) && lower.contains("cr")) -> cols.credit = i
                cols.category == -1 && (lower.contains("category") || lower.contains("bucket")) -> cols.category = i
                cols.ref == -1 && (lower.contains("chq") || lower.contains("ref") || lower.contains("cheque")) -> cols.ref = i
            }
        }
    }

    private fun parseStructuredRow(tokens: List<String>, cols: Columns, prevBalance: Double?): Parsed? {
        fun cell(i: Int) = if (i in tokens.indices) tokens[i].trim() else ""

        val rawDate = cell(cols.date)
        val desc = cell(cols.desc)
        if (rawDate.isBlank() || desc.isBlank()) return null
        val epochMs = parseDateStrToMillis(rawDate)
        if (epochMs == 0L) return null // Must have a valid transaction date

        val balance = cell(cols.balance).takeIf { it.isNotBlank() }?.let { cleanAmount(it) }?.takeIf { it > 0.0 }
        val debitCell = cell(cols.debit)
        val creditCell = cell(cols.credit)
        val debit = cleanAmount(debitCell)
        val credit = cleanAmount(creditCell)
        val typeMarker = when (cell(cols.type).lowercase()) {
            "dr", "debit", "expense", "withdrawal", "out", "payment" -> "dr"
            "cr", "credit", "income", "deposit", "in", "receipt" -> "cr"
            else -> null
        }
        val category = cell(cols.category).takeIf { it.isNotBlank() }

        val amount: Double
        val direction: Direction
        when {
            debit > 0.0 && credit == 0.0 -> {
                amount = debit
                direction = resolveDirection(amount, balance, prevBalance, markerOf(debitCell) ?: typeMarker, desc, Direction.DEBIT)
            }
            credit > 0.0 && debit == 0.0 -> {
                amount = credit
                direction = resolveDirection(amount, balance, prevBalance, markerOf(creditCell) ?: typeMarker, desc, Direction.CREDIT)
            }
            else -> {
                // Amount is not in a debit/credit column: take the first money-looking cell
                val skip = setOf(cols.date, cols.desc, cols.ref, cols.balance)
                val found = tokens.withIndex()
                    .filter { it.index !in skip }
                    .mapNotNull { (_, t) -> signedAmount(t) }
                    .firstOrNull() ?: return null
                amount = found.first
                // A minus sign means money out, a plus sign money in
                direction = when (found.third) {
                    '-' -> Direction.DEBIT
                    '+' -> Direction.CREDIT
                    else -> resolveDirection(amount, balance, prevBalance, found.second ?: typeMarker, desc, null)
                }
            }
        }

        return Parsed(
            RawTransactionLine(
                date = rawDate,
                description = desc,
                debitAmount = if (direction == Direction.DEBIT) amount else 0.0,
                creditAmount = if (direction == Direction.CREDIT) amount else 0.0,
                isDebit = direction == Direction.DEBIT,
                reference = cell(cols.ref).takeIf { it.isNotBlank() && it != "-" },
                dateEpochMs = epochMs,
                category = category
            ),
            balance
        )
    }

    private fun parseUnstructuredLine(line: String, prevBalance: Double?): Parsed? {
        val dateMatch = datePattern.find(line) ?: return null
        if (dateMatch.range.first > 15) return null // narration text that merely contains a date
        val rawDate = dateMatch.value
        val epochMs = parseDateStrToMillis(rawDate)
        if (epochMs == 0L) return null

        val amounts = amountPattern.findAll(line, dateMatch.range.last + 1).toList()
        if (amounts.isEmpty()) return null

        // Typical tail: [debit] [credit] balance, or amount balance, or just amount
        val balance = if (amounts.size >= 2) signedValue(line, amounts.last()) else null
        var direction: Direction? = null
        val chosen = when {
            amounts.size >= 3 -> {
                val d = amountOf(amounts[amounts.size - 3])
                val c = amountOf(amounts[amounts.size - 2])
                when {
                    d > 0.0 && c == 0.0 -> { direction = Direction.DEBIT; amounts[amounts.size - 3] }
                    c > 0.0 && d == 0.0 -> { direction = Direction.CREDIT; amounts[amounts.size - 2] }
                    else -> amounts[amounts.size - 2]
                }
            }
            amounts.size == 2 -> amounts[0]
            else -> amounts[0]
        }
        val amount = amountOf(chosen)
        if (amount <= 0.0) return null

        val marker = chosen.groups[3]?.value?.lowercase()
        val finalDirection = direction ?: resolveDirection(amount, balance, prevBalance, marker, line, null)

        // Description: the line without dates and amounts
        val remove = mutableListOf<IntRange>()
        remove.addAll(amounts.map { it.range })
        // A serial number in front of the date ("12  05-02-2026 SI - X") is not part of the narration
        if (dateMatch.range.first > 0 && line.substring(0, dateMatch.range.first).isBlank().not() &&
            line.substring(0, dateMatch.range.first).trim().all { it.isDigit() }
        ) remove.add(0 until dateMatch.range.first)
        datePattern.findAll(line).filter { it.range.first <= 40 }.forEach { remove.add(it.range) }
        val sb = StringBuilder(line)
        remove.sortedByDescending { it.first }.forEach { sb.replace(it.first, it.last + 1, " ") }
        val desc = sb.toString()
            .replace(refTokenPattern, " ")
            .replace(Regex("""[,|;\t]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() && it != "-" && !it.equals("DR", true) && !it.equals("CR", true) }
            .joinToString(" ")
            .ifBlank { "Bank Transaction" }

        return Parsed(
            RawTransactionLine(
                date = rawDate,
                description = desc,
                debitAmount = if (finalDirection == Direction.DEBIT) amount else 0.0,
                creditAmount = if (finalDirection == Direction.CREDIT) amount else 0.0,
                isDebit = finalDirection == Direction.DEBIT,
                dateEpochMs = epochMs
            ),
            balance
        )
    }

    private fun isContinuationLine(line: String): Boolean =
        line.length <= 120 && !skipLinePattern.containsMatchIn(line) && amountPattern.find(line) == null

    private fun resolveDirection(
        amount: Double,
        balance: Double?,
        prevBalance: Double?,
        marker: String?,
        description: String,
        columnDirection: Direction?
    ): Direction {
        when (marker?.lowercase()) {
            "dr" -> return Direction.DEBIT
            "cr" -> return Direction.CREDIT
        }
        if (balance != null && prevBalance != null) {
            if (abs(prevBalance - amount - balance) < 0.02) return Direction.DEBIT
            if (abs(prevBalance + amount - balance) < 0.02) return Direction.CREDIT
        }
        if (columnDirection != null) return columnDirection
        return if (creditHintPattern.containsMatchIn(description)) Direction.CREDIT else Direction.DEBIT
    }

    private fun amountOf(m: MatchResult): Double =
        "${m.groupValues[1].replace(",", "")}.${m.groupValues[2]}".toDoubleOrNull() ?: 0.0

    private fun markerOf(cell: String): String? = markerPattern.findAll(cell).lastOrNull()?.value?.lowercase()

    // An amount cell with an optional sign: "-645.34", "+1,03,500.00", "(299.00)", "299.00 Dr"
    private fun signedAmount(token: String): Triple<Double, String?, Char?>? {
        val m = Regex("""^\s*([-+\u2212(])?\s*(?:₹|Rs\.?|INR)?\s*(\d{1,3}(?:,\d{2,3})*|\d+)\.(\d{2})\s*\)?\s*(Dr|Cr)?\s*$""", RegexOption.IGNORE_CASE)
            .find(token) ?: return null
        val value = "${m.groupValues[2].replace(",", "")}.${m.groupValues[3]}".toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        val sign = when (m.groupValues[1]) { "-", "\u2212", "(" -> '-'; "+" -> '+'; else -> null }
        return Triple(value, m.groupValues[4].lowercase().ifBlank { null }, sign)
    }

    // A cell that holds just an amount, optionally with a currency symbol and Dr/Cr suffix
    private fun singleAmount(token: String): Pair<Double, String?>? {
        val m = amountPattern.find(token) ?: return null
        val rest = token.replace(m.value, "")
        if (rest.any { it.isLetterOrDigit() }) return null
        val value = amountOf(m)
        return if (value > 0.0) Pair(value, m.groups[3]?.value?.lowercase()) else null
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (ch in line) {
            when (ch) {
                '"' -> inQuotes = !inQuotes
                ',', '\t', '|', ';' -> {
                    if (inQuotes) {
                        current.append(ch)
                    } else {
                        result.add(current.toString().trim())
                        current = StringBuilder()
                    }
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    // Keeps digits, the decimal point and a sign; drops grouping commas, symbols and Dr/Cr
    private fun cleanAmount(raw: String): Double {
        val clean = raw.replace(Regex("""[^0-9.\-]"""), "")
        return abs(clean.toDoubleOrNull() ?: 0.0)
    }

    private fun parseDateStrToMillis(dateStr: String): Long {
        val normalized = dateStr.trim().replace(Regex("""(?i)\bsept\b"""), "Sep").replace(Regex("""[.,]"""), " ")
            .replace(Regex("""\s+"""), " ")
        val formats = listOf(
            "dd/MM/yyyy", "dd-MM-yyyy", "dd/MM/yy", "dd-MM-yy",
            "d/M/yyyy", "d-M-yyyy", "d/M/yy", "d-M-yy",
            "dd MM yyyy", "dd MM yy",
            "dd MMM yyyy", "d MMM yyyy", "dd MMM yy", "d MMM yy",
            "dd-MMM-yyyy", "d-MMM-yyyy", "dd-MMM-yy", "d-MMM-yy",
            "dd MMMM yyyy", "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                val date = sdf.parse(normalized)
                // "yyyy" happily accepts "26" as year 26 AD; a two-digit year must fall through to "yy"
                if (date != null && date.time >= YEAR_2000_MS) return date.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

}
