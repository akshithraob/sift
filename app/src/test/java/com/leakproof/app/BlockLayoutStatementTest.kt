package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPI wallet / neo-bank PDFs print each transaction as a block of short lines (date, time, "To X",
 * reference lines, then "amount balance") rather than one line per row. Synthetic data in that shape.
 */
class BlockLayoutStatementTest {
    private fun block(date: String, time: String, who: String, amount: String, balance: String) = """
        $date
        $time
        $who
        UPI ID: someone@bank
        Txn ID: ABC123XYZ
        UTR: 123456789012
        $amount $balance
    """.trimIndent().lines()

    private val header = listOf("Date & Time Transaction Details Amount Closing balance")
    private val footer = listOf("Page 1 of 26")

    private fun statement(): List<String> {
        val lines = mutableListOf(
            "STATEMENT FOR", "Test User", "Opening Balance", "0", "Total Deposit", "+9,999.00",
            "Total Withdrawn", "-9,999.00", "Closing Balance", "0"
        )
        lines += header
        val monthly = listOf("24 Mar 2026", "24 Apr 2026", "24 May 2026", "23 Jun 2026", "24 Jul 2026", "24 Aug 2026", "23 Sep 2026", "10 Oct 2026")
        var bal = 5000.0
        for (d in monthly) {
            lines += block(d, "02:10 PM", "To Boost Up Fee", "39.00", "%.2f".format(bal))
            bal -= 39
            lines += block(d, "09:00 AM", "From Test Friend", "230.00", "%.2f".format(bal + 230))
        }
        lines += footer
        lines += header
        lines += block("12 Oct 2026", "07:45 PM", "To Local Cafe", "152.00", "100.00")
        return lines
    }

    @Test
    fun blocksBecomeOneTransactionEachWithDirectionFromToAndFrom() {
        val txs = DefaultStatementParser().parseStatementLines(statement())
        assertEquals(17, txs.size)
        assertEquals(8, txs.count { !it.isDebit })
        assertEquals(9, txs.count { it.isDebit })
        val fee = txs.first { it.description.contains("Boost Up Fee") }
        assertEquals(39.0, fee.debitAmount, 0.001)
        assertTrue(txs.none { it.description.contains("Txn") || it.description.contains("someone@") })
    }

    @Test
    fun monthlyFeeIsDetectedAsRecurring() {
        val txs = DefaultStatementParser().parseStatementLines(statement())
        val found = DefaultRecurringDetector().detectRecurring(txs)
        val fee = found.firstOrNull { it.merchant.contains("Boost Up Fee", ignoreCase = true) }
        assertTrue(found.map { it.merchant }.toString(), fee != null)
        assertEquals(39.0, fee!!.amount, 0.001)
    }

    @Test
    fun oneLinePerRowStatementsAreUntouched() {
        val lines = listOf(
            "01/05/2026 NETFLIX UPI-MANDATE 649.00 10,000.00",
            "01/06/2026 NETFLIX UPI-MANDATE 649.00 9,351.00"
        )
        assertEquals(2, DefaultStatementParser().parseStatementLines(lines).size)
    }

    @Test
    fun toPrefixIsDroppedAndMicroTransfersIgnored() {
        val detector = DefaultRecurringDetector()
        assertEquals("Boost Up Fee", detector.normalizeMerchantName("To Boost Up Fee"))
        val lines = mutableListOf<String>()
        listOf("17 Mar 2026", "10 Jun 2026", "10 Oct 2026").forEach {
            lines += block(it, "10:00 AM", "To Test Person", "1.00", "50.00")
        }
        val txs = DefaultStatementParser().parseStatementLines(lines)
        assertEquals(3, txs.size)
        assertTrue(detector.detectRecurring(txs).isEmpty())
    }

    @Test
    fun knownServiceWithChangedPriceIsStillFound() {
        val lines = mutableListOf<String>()
        lines += block("11 Jun 2026", "10:00 AM", "To Spotify", "139.00", "900.00")
        lines += block("14 Sep 2026", "10:00 AM", "To Spotify", "99.00", "800.00")
        lines += block("12 Dec 2026", "10:00 AM", "To Spotify", "99.00", "700.00")
        val found = DefaultRecurringDetector().detectRecurring(DefaultStatementParser().parseStatementLines(lines))
        assertTrue(found.map { it.merchant }.toString(), found.any { it.merchant == "Spotify Premium" })
    }

    @Test
    fun twoSimilarPurchasesNotOnACycleAreNotSubscriptions() {
        val lines = mutableListOf<String>()
        lines += block("10 Sep 2026", "10:00 AM", "To Mad Over Donuts", "139.00", "900.00")
        lines += block("23 Sep 2026", "10:00 AM", "To Mad Over Donuts", "129.00", "800.00")
        assertTrue(DefaultRecurringDetector().detectRecurring(DefaultStatementParser().parseStatementLines(lines)).isEmpty())
    }
}
