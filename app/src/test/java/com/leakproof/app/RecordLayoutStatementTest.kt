package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A statement where each transaction is a date and narration line, a category line, then a line with a
 * reference number, the amount and the running balance (Indian digit grouping). Synthetic data in the
 * shape of a real multi-page PDF export, including repeated page headers.
 */
class RecordLayoutStatementTest {
    private val pageHeader = listOf(
        "Northwind Savings Bank",
        "Statement of Account (Sample)",
        "Page 2 of 5",
        "Date Description / Category Ref. No. Debit (₹) Credit (₹) Balance (₹)"
    )

    private fun lines(): List<String> {
        val out = mutableListOf(
            "Account Summary", "Opening Balance", "1,35,000.00", "Total Credits", "2,44,711.40",
            "01 Jul 2026 – 30 Sep 2026"
        )
        out += pageHeader
        out += "BALANCE BROUGHT FORWARD 1,35,000.00"
        var balance = 135000.0
        fun fmt(v: Double): String {
            val n = "%.2f".format(v)
            val (whole, frac) = n.split(".")
            val last3 = whole.takeLast(3)
            val rest = whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            return (if (rest.isEmpty()) last3 else "$rest,$last3") + "." + frac
        }
        var seq = 1000
        fun tx(date: String, text: String, category: String, amount: Double, credit: Boolean = false) {
            balance += if (credit) amount else -amount
            out += "$date $text"
            out += category
            out += "REF26${seq++} ${fmt(amount)} ${fmt(balance)}"
        }
        for ((i, m) in listOf("Jul", "Aug", "Sep").withIndex()) {
            tx("01-$m-26", "SALARY CREDIT - ACME PVT LTD", "Income", 68500.0, credit = true)
            tx("03-$m-26", "AUTOPAY - YOUTUBE PREMIUM", "Subscriptions", 149.0)
            tx("05-$m-26", "RENT TRANSFER - LANDLORD", "Housing", 24000.0)
            tx("07-$m-26", "AUTOPAY - SPOTIFY PREMIUM", "Subscriptions", 119.0)
            tx("0${8 - i}-$m-26", "ELECTRICITY BILL - TGSPDCL (SAMPLE)", "Utilities", listOf(1552.0, 2514.0, 1797.0)[i])
            tx("1${i}-$m-26", "POS - DECATHLON", "Shopping", listOf(2042.97, 6613.4, 1999.5)[i])
            tx(listOf("20-Jul-26", "02-Aug-26", "29-Aug-26")[i], "UPI - SWIGGY", "Food & Groceries", listOf(571.13, 1178.0, 723.5)[i] + i * 7)
            tx("2${i + 3}-$m-26", "ATM CASH WITHDRAWAL - HITECH CITY", "ATM Withdrawals", listOf(3000.0, 2000.0, 5000.0)[i])
            // A shopping purchase and a Prime fee under the same merchant name
            tx("1${i + 2}-$m-26", "POS - AMAZON", "Shopping", listOf(5067.31, 8102.0, 10081.0)[i])
            tx("2${i + 4}-$m-26", "AUTOPAY - AMAZON PRIME", "Subscriptions", 299.0)
            if (i == 0) out += pageHeader // a page break in the middle
        }
        return out
    }

    @Test
    fun everyTransactionBecomesARecordWithTheRightDirection() {
        val txs = DefaultStatementParser().parseStatementLines(lines())
        assertEquals(3 * 10, txs.size)
        assertEquals(3, txs.count { !it.isDebit })
        val salary = txs.first { !it.isDebit }
        assertEquals(68500.0, salary.creditAmount, 0.001)
        assertTrue(txs.none { it.description.contains("REF26") || it.description.contains("Northwind") })
    }

    @Test
    fun recurringPaymentsAreFoundWithoutTrainingOnTheirNames() {
        val txs = DefaultStatementParser().parseStatementLines(lines())
        val found = DefaultRecurringDetector().detectRecurring(txs).associateBy { it.merchant }

        assertEquals(149.0, found.getValue("Youtube Premium").amount, 0.001)
        assertEquals(119.0, found.getValue("Spotify Premium").amount, 0.001)
        assertEquals(24000.0, found.getValue("Rent Transfer Landlord").amount, 0.001)
        // The Prime fee is picked out from the shopping under the same merchant name
        assertEquals(299.0, found.getValue("Amazon Prime").amount, 0.001)
        // A bill whose amount changes but which arrives on a steady cycle
        assertTrue(found.getValue("Electricity Tgspdcl").confidence.name == "MEDIUM")

        assertFalse(found.keys.any { it.contains("Atm", ignoreCase = true) })
        assertFalse(found.containsKey("Decathlon"))
        assertFalse(found.containsKey("Swiggy One"))
    }
}
