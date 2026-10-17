package com.example.parser

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

interface StatementParser {
    fun parseStatementLines(lines: List<String>): List<RawTransactionLine>
    fun parseCsv(csvContent: String): List<RawTransactionLine>
    fun getSampleStatementLines(): List<String>
    fun getBankSampleLines(bankName: String): List<String>
}

class DefaultStatementParser : StatementParser {

    override fun parseStatementLines(lines: List<String>): List<RawTransactionLine> {
        val result = mutableListOf<RawTransactionLine>()
        var dateCol = -1
        var descCol = -1
        var debitCol = -1
        var creditCol = -1
        var refCol = -1

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            val tokens = splitCsvLine(trimmed)
            if (tokens.isEmpty()) continue

            // Check if this row is a header row
            if (dateCol == -1 && isHeaderRow(tokens)) {
                for (i in tokens.indices) {
                    val lower = tokens[i].lowercase()
                    when {
                        dateCol == -1 && (lower.contains("date") || lower.contains("txn date") || lower.contains("tran date") || lower.contains("value dt")) -> dateCol = i
                        descCol == -1 && (lower.contains("narration") || lower.contains("description") || lower.contains("particular") || lower.contains("remark") || lower.contains("details")) -> descCol = i
                        debitCol == -1 && (lower.contains("debit") || lower.contains("withdrawal") || lower.contains("dr")) -> debitCol = i
                        creditCol == -1 && (lower.contains("credit") || lower.contains("deposit") || lower.contains("cr")) -> creditCol = i
                        refCol == -1 && (lower.contains("chq") || lower.contains("ref") || lower.contains("cheque")) -> refCol = i
                    }
                }
                continue
            }

            // Parse transaction row
            val tx = if (dateCol != -1 && descCol != -1) {
                parseStructuredRow(tokens, dateCol, descCol, debitCol, creditCol, refCol)
            } else {
                parseUnstructuredLine(trimmed, tokens)
            }

            if (tx != null) {
                result.add(tx)
            }
        }

        return result
    }

    override fun parseCsv(csvContent: String): List<RawTransactionLine> {
        return parseStatementLines(csvContent.lines())
    }

    private fun isHeaderRow(tokens: List<String>): Boolean {
        val lowerTokens = tokens.map { it.lowercase() }
        val hasDate = lowerTokens.any { it.contains("date") || it.contains("txn date") || it.contains("tran date") }
        val hasDesc = lowerTokens.any { it.contains("narration") || it.contains("description") || it.contains("particular") || it.contains("remark") || it.contains("details") }
        val hasAmount = lowerTokens.any { it.contains("debit") || it.contains("withdrawal") || it.contains("amount") || it.contains("credit") || it.contains("deposit") }
        return (hasDate && (hasDesc || hasAmount))
    }

    private fun parseStructuredRow(
        tokens: List<String>,
        dateCol: Int,
        descCol: Int,
        debitCol: Int,
        creditCol: Int,
        refCol: Int
    ): RawTransactionLine? {
        if (tokens.size <= dateCol || tokens.size <= descCol) return null

        val rawDate = tokens[dateCol].trim()
        val desc = tokens[descCol].trim()
        if (rawDate.isBlank() || desc.isBlank()) return null

        val epochMs = parseDateStrToMillis(rawDate)
        if (epochMs == 0L) return null // Must have valid transaction date

        var debitAmt = 0.0
        var creditAmt = 0.0

        if (debitCol != -1 && tokens.size > debitCol) {
            debitAmt = cleanAmount(tokens[debitCol])
        }
        if (creditCol != -1 && tokens.size > creditCol) {
            creditAmt = cleanAmount(tokens[creditCol])
        }

        // If amount was not in separate debit/credit column, check for DR/CR indicator in adjacent cell
        var isDebit = debitAmt > 0.0
        if (debitAmt == 0.0 && creditAmt == 0.0) {
            // Check any token with numeric amount
            for (token in tokens) {
                val amt = cleanAmount(token)
                if (amt > 0.0) {
                    if (token.contains("CR", ignoreCase = true) || tokens.any { it.equals("CR", ignoreCase = true) }) {
                        creditAmt = amt
                        isDebit = false
                    } else {
                        debitAmt = amt
                        isDebit = true
                    }
                    break
                }
            }
        }

        if (debitAmt == 0.0 && creditAmt == 0.0) return null

        val ref = if (refCol != -1 && tokens.size > refCol) tokens[refCol] else null

        return RawTransactionLine(
            date = rawDate,
            description = desc,
            debitAmount = debitAmt,
            creditAmount = creditAmt,
            isDebit = isDebit,
            reference = ref,
            dateEpochMs = epochMs
        )
    }

    private fun parseUnstructuredLine(line: String, tokens: List<String>): RawTransactionLine? {
        // Look for date at start or inside
        val datePattern = Pattern.compile("\\b([0-3]?[0-9][\\-/][0-1]?[0-9][\\-/]20[2-3][0-9]|[0-3]?[0-9][\\-/][0-1]?[0-9][\\-/][2-3][0-9]|[0-3]?[0-9]\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+20[2-3][0-9])\\b", Pattern.CASE_INSENSITIVE)
        val dateMatcher = datePattern.matcher(line)
        if (!dateMatcher.find()) return null

        val rawDate = dateMatcher.group(1) ?: return null
        val epochMs = parseDateStrToMillis(rawDate)

        // Find numbers in line
        val numPattern = Pattern.compile("(?:[₹]|Rs\\.?|INR)?\\s*([0-9,]+\\.[0-9]{2})\\b")
        val numMatcher = numPattern.matcher(line)
        val foundNumbers = mutableListOf<Double>()
        while (numMatcher.find()) {
            val num = cleanAmount(numMatcher.group(1) ?: "")
            if (num > 0.0) foundNumbers.add(num)
        }

        if (foundNumbers.isEmpty()) return null

        val isCredit = line.contains("CR", ignoreCase = true) || line.contains("Credit", ignoreCase = true) || line.contains("Deposit", ignoreCase = true)
        val isDebit = !isCredit

        val debitAmt = if (isDebit) foundNumbers.first() else 0.0
        val creditAmt = if (isCredit) foundNumbers.first() else 0.0

        // Description is everything between date and the numbers
        var desc = line
        desc = desc.replace(rawDate, "")
        for (num in foundNumbers) {
            desc = desc.replace(num.toString(), "").replace(String.format(Locale.ENGLISH, "%.2f", num), "")
        }
        desc = desc.replace(Regex("[,|;\t]"), " ")
            .replace(Regex("\\b(DR|CR|Debit|Credit)\\b", RegexOption.IGNORE_CASE), "")
            .trim()
            .replace(Regex("\\s+"), " ")

        if (desc.isBlank()) desc = "Bank Transaction"

        return RawTransactionLine(
            date = rawDate,
            description = desc,
            debitAmount = debitAmt,
            creditAmount = creditAmt,
            isDebit = isDebit,
            dateEpochMs = epochMs
        )
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
        return result.map { it.removePrefix("\"").removeSuffix("\"").trim() }
    }

    private fun cleanAmount(raw: String): Double {
        val clean = raw.replace("₹", "")
            .replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true)
            .replace("INR", "", ignoreCase = true)
            .replace(",", "")
            .replace("DR", "", ignoreCase = true)
            .replace("CR", "", ignoreCase = true)
            .trim()
        return clean.toDoubleOrNull() ?: 0.0
    }

    private fun parseDateStrToMillis(dateStr: String): Long {
        val formats = listOf(
            "dd/MM/yyyy", "dd-MM-yyyy", "dd/MM/yy", "dd-MM-yy",
            "d/M/yyyy", "d-M-yyyy", "d/M/yy", "d-M-yy",
            "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "d-MMM-yyyy",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                val date = sdf.parse(dateStr.trim())
                if (date != null) return date.time
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
