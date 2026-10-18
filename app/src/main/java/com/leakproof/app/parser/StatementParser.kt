package com.leakproof.app.parser

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

interface StatementParser {
    fun parseStatementLines(lines: List<String>): List<RawTransactionLine>
    fun parseCsv(csvContent: String): List<RawTransactionLine>
    fun getSampleStatementLines(): List<String>
    fun getBankSampleLines(bankName: String): List<String>
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

        for (line in joinQuotedLines(lines)) {
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

        return result
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

    override fun getSampleStatementLines(): List<String> {
        return getBankSampleLines("ALL")
    }

    override fun getBankSampleLines(bankName: String): List<String> {
        return when (bankName.uppercase()) {
            "SBI" -> listOf(
                "Txn Date, Narration, Ref No, Debit, Credit, Balance",
                "01/08/2026, TRANSFER TO 48219012345/UPI/NETFLIX/429182910/UPI, 429182910, 649.00, , 24500.00",
                "01/09/2026, TRANSFER TO 48219012345/UPI/NETFLIX/581920191/UPI, 581920191, 649.00, , 23851.00",
                "01/10/2026, TRANSFER TO 48219012345/UPI/NETFLIX/691028491/UPI, 691028491, 649.00, , 23202.00",
                "10/08/2026, ACH D-ZERODHA BROKING LTD-482109, ACH90124, 5000.00, , 18202.00",
                "10/09/2026, ACH D-ZERODHA BROKING LTD-482109, ACH98412, 5000.00, , 13202.00",
                "10/10/2026, ACH D-ZERODHA BROKING LTD-482109, ACH10294, 5000.00, , 8202.00"
            )
            "HDFC" -> listOf(
                "Date, Narration, Chq/Ref No, Value Dt, Withdrawal Amt., Deposit Amt., Closing Balance",
                "15/08/26, UPI-SWIGGY-swiggy@icici-HDFC000123-429182910-UPI, 429182910, 15/08/26, 420.00, , 15000.00",
                "10/08/26, ACH D-GROWW INVEST TECH-481921, 9812401, 10/08/26, 2000.00, , 13000.00",
                "10/09/26, ACH D-GROWW INVEST TECH-481921, 9841092, 10/09/26, 2000.00, , 11000.00",
                "10/10/26, ACH D-GROWW INVEST TECH-481921, 9918294, 10/10/26, 2000.00, , 9000.00",
                "02/08/26, POS 482190 ZOMATO GOLD MUMBAI IN, POS9812, 02/08/26, 999.00, , 8001.00"
            )
            "ICICI" -> listOf(
                "Transaction Date, Value Date, Cheque Number, Transaction Remarks, Withdrawal Amount (INR ), Deposit Amount (INR ), Balance (INR )",
                "20-08-2026, 20-08-2026, -, UPI/SPOTIFY/spotify@paytm/Payment from ICICI/5928192, 119.00, 0.00, 50000.00",
                "20-09-2026, 20-09-2026, -, UPI/SPOTIFY/spotify@paytm/Payment from ICICI/6829102, 119.00, 0.00, 49881.00",
                "20-10-2026, 20-10-2026, -, UPI/SPOTIFY/spotify@paytm/Payment from ICICI/7918201, 119.00, 0.00, 49762.00",
                "05-08-2026, 05-08-2026, -, SI-BESCOM ELECTRICITY AUTO DEBIT-4821, 1850.00, 0.00, 47912.00",
                "05-09-2026, 05-09-2026, -, SI-BESCOM ELECTRICITY AUTO DEBIT-4821, 1910.00, 0.00, 46002.00",
                "05-10-2026, 05-10-2026, -, SI-BESCOM ELECTRICITY AUTO DEBIT-4821, 1880.00, 0.00, 44122.00"
            )
            "AXIS" -> listOf(
                "Tran Date, Value Date, Transaction Details, Chq No, Debit, Credit, Balance",
                "12-08-2026, 12-08-2026, ACH/CULT FIT HEALTHCARE/UTIR0000123/98124, -, 1499.00, , 32000.00",
                "12-09-2026, 12-09-2026, ACH/CULT FIT HEALTHCARE/UTIR0000123/98912, -, 1499.00, , 30501.00",
                "12-10-2026, 12-10-2026, ACH/CULT FIT HEALTHCARE/UTIR0000123/99481, -, 1499.00, , 29002.00",
                "08-10-2026, 08-10-2026, UPI-MANDATE-HOTSTAR-hotstar@axis/9281948291, -, 1499.00, , 27503.00"
            )
            else -> listOf(
                "10/08/2026, ACH D-ZERODHA BROKING LTD-482109, 5000.00, DR, ACH90124",
                "10/09/2026, ACH D-ZERODHA BROKING LTD-482109, 5000.00, DR, ACH98412",
                "10/10/2026, ACH D-ZERODHA BROKING LTD-482109, 5000.00, DR, ACH10294",
                "02/08/2026, SI-BESCOM ELECTRICITY BILL-AUTO, 1850.00, DR, SI48291",
                "02/09/2026, SI-BESCOM ELECTRICITY BILL-AUTO, 1920.00, DR, SI59281",
                "02/10/2026, SI-BESCOM ELECTRICITY BILL-AUTO, 1890.00, DR, SI70129",
                "15/08/2026, UPI-MANDATE-DISNEY HOTSTAR-MND981, 1499.00, DR, UPI2849"
            )
        }
    }
}
