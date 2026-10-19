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
        """(?<![\d.,/\-])(?:₹|Rs\.?|INR)?\s*(\d{1,3}(?:,\d{2,3})*|\d+)\.(\d{2})(?![\d.])(?:[\s,|(]*(Dr|Cr)\b\)?)?""",
        RegexOption.IGNORE_CASE
    )
    private val datePattern = Regex(
        """(?i)\b(\d{1,2}[-/.]\d{1,2}[-/.](?:20)?\d{2}|\d{1,2}[-\s](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-\s,]+(?:20)?\d{2}|20\d{2}-\d{2}-\d{2})\b"""
    )
    private val markerPattern = Regex("""(?i)\b(dr|cr)\b""")

    override fun parseStatementLines(lines: List<String>): List<RawTransactionLine> {
        val result = mutableListOf<RawTransactionLine>()
        val cols = Columns()
        var headerFound = false
        var prevBalance: Double? = null

        // Wallet and fintech statements print each transaction as a block of short lines
        val source = normaliseBlockLayout(lines) ?: lines
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
        val dated = source.count { l -> datePattern.find(l.trim())?.let { it.range.first <= 3 } == true }
        if (dated >= 3 && result.size < dated / 2) {
            val records = parseRecordLayout(source)
            if (records.size > result.size) return records
        }

        return result
    }

    private val refTokenPattern = Regex("""\b(?:[A-Z]{2,8}[0-9]{6,}[A-Z0-9]*|[0-9]{6,})\b""")

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
        val repeated = body.groupingBy { it }.eachCount().filter { (line, n) -> n >= 3 && line.none { it.isDigit() } }.keys

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
            val date = datePattern.find(line)?.takeIf { it.range.first <= 3 }
            val isPeriodLine = date != null && datePattern.findAll(line).count() >= 2 && amountPattern.find(line) == null
            if (date != null && !isPeriodLine) {
                records.add(Record(date, mutableListOf(line.substring(date.range.last + 1).trim())))
            } else if (records.isNotEmpty()) {
                records.last().parts.add(line)
            }
        }

        val out = mutableListOf<RawTransactionLine>()
        for (rec in records) {
            val amountIdx = rec.parts.indexOfLast { amountPattern.find(it) != null }
            if (amountIdx < 0) continue
            val epochMs = parseDateStrToMillis(rec.date.value)
            if (epochMs == 0L) continue
            val amountLine = rec.parts[amountIdx]
            val amounts = amountPattern.findAll(amountLine).toList()
            val balance = if (amounts.size >= 2) amountOf(amounts.last()) else null
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
                t.replace(refTokenPattern, " ").replace(Regex("""\s+"""), " ").trim().ifBlank { null }
            }.joinToString(" ").ifBlank { "Bank Transaction" }

            val finalDirection = direction ?: resolveDirection(amount, balance, prevBalance, chosen.groups[3]?.value, text, null)
            out.add(
                RawTransactionLine(
                    date = rec.date.value,
                    description = text,
                    debitAmount = if (finalDirection == Direction.DEBIT) amount else 0.0,
                    creditAmount = if (finalDirection == Direction.CREDIT) amount else 0.0,
                    isDebit = finalDirection == Direction.DEBIT,
                    dateEpochMs = epochMs
                )
            )
            prevBalance = balance ?: prevBalance
        }
        return out
    }

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
            contains("remark") || contains("details")

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
        val typeMarker = cell(cols.type).lowercase().takeIf { it == "dr" || it == "cr" }

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
                    .mapNotNull { (_, t) -> singleAmount(t) }
                    .firstOrNull() ?: return null
                amount = found.first
                direction = resolveDirection(amount, balance, prevBalance, found.second ?: typeMarker, desc, null)
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
                dateEpochMs = epochMs
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
        val balance = if (amounts.size >= 2) amountOf(amounts.last()) else null
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
        datePattern.findAll(line).filter { it.range.first <= 40 }.forEach { remove.add(it.range) }
        val sb = StringBuilder(line)
        remove.sortedByDescending { it.first }.forEach { sb.replace(it.first, it.last + 1, " ") }
        val desc = sb.toString()
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
            "dd MMMM yyyy", "yyyy-MM-dd"
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
