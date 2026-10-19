package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three more PDF text shapes, synthetic: date-heading groups, card rows without a year, and inline category/mode columns. */
class MoreStatementLayoutsTest {
    private val months = listOf("May", "Jun", "Jul")

    // ---- rows under a date heading, signed amounts with a Unicode minus
    private fun dateHeadingStatement(): List<String> {
        val out = mutableListOf("Activity 01 May – 21 Jul 2026", "DESCRIPTION CATEGORY AMOUNT (₹) BALANCE (₹)", "Starting balance 3,75,000.00")
        var bal = 375000.0
        fun row(text: String, amount: Double, credit: Boolean = false) {
            bal += if (credit) amount else -amount
            out += "$text ${if (credit) "+" else "−"}${"%.2f".format(amount)} ${"%.2f".format(bal)}"
        }
        for ((i, m) in months.withIndex()) {
            out += "Friday, 01 $m 2026"
            row("SALARY - ORBITAL LTD Salary NEFT", 103500.0, credit = true)
            out += "Saturday, 02 $m 2026"
            row("AUTOPAY AMAZON PRIME Subscriptions SI", 299.0)
            out += "Sunday, 0${3 + i} $m 2026"
            row("POS - MEESHO Shopping POS", listOf(3552.37, 810.0, 2210.0)[i])
            out += "Tuesday, 05 $m 2026"
            row("HOME LOAN EMI (SAMPLE) Loan EMI ACH", 48500.0)
        }
        return out
    }

    @Test
    fun dateHeadingGroupsAndSignedAmounts() {
        val txs = DefaultStatementParser().parseStatementLines(dateHeadingStatement())
        assertEquals(12, txs.size)
        assertEquals(3, txs.count { !it.isDebit })
        val found = DefaultRecurringDetector().detectRecurring(txs).associateBy { it.merchant }
        assertEquals(299.0, found.getValue("Amazon Prime").amount, 0.001)
        assertEquals(48500.0, found.getValue("Home Loan Emi").amount, 0.001)
        assertFalse(found.keys.any { it.contains("Meesho") })
    }

    // ---- card rows: narration, "Mon 01 Jun · Category · Mode · Ref", signed rupee amount, "bal" line
    private fun cardStatement(): List<String> {
        val out = mutableListOf("Siddharth (sample) · 01 Jun 2026 – 31 Aug 2026", "Transactions 01 Jun – 27 Jun 2026", "Starting balance ₹1,58,900.00")
        var bal = 158900.0
        var n = 100
        fun card(narration: String, date: String, category: String, mode: String, amount: Double, credit: Boolean = false) {
            bal += if (credit) amount else -amount
            out += "S $narration"
            out += "$date · $category · $mode · ${mode}2606${n++}"
            out += (if (credit) "+" else "−") + "₹" + "%.2f".format(amount)
            out += "bal ₹" + "%.2f".format(bal)
        }
        listOf("Jun" to listOf("Mon 03", "Wed 08"), "Jul" to listOf("Fri 03", "Sun 08"), "Aug" to listOf("Mon 03", "Mon 17")).forEach { (m, d) ->
            card("RECURRING · YOUTUBE PREMIUM", "${d[0]} $m", "Subscriptions", "SI", 149.0)
            card("UPI - LOCAL KIRANA", "${d[1]} $m", "Groceries", "UPI", if (m == "Jun") 2296.63 else 2210.0)
        }
        return out
    }

    @Test
    fun cardRowsWithoutAYearTakeTheYearFromThePeriod() {
        val txs = DefaultStatementParser().parseStatementLines(cardStatement())
        assertEquals(6, txs.size)
        assertTrue(txs.all { it.date.endsWith("2026") })
        val found = DefaultRecurringDetector().detectRecurring(txs).associateBy { it.merchant }
        assertEquals(149.0, found.getValue("Youtube Premium").amount, 0.001)
        // The statement's own category marks groceries as ordinary spending, even with similar amounts
        assertFalse(found.keys.any { it.contains("Kirana") })
    }

    // ---- one line per row: serial, date, narration, category, mode, reference, amount, balance
    @Test
    fun inlineCategoryModeAndReferenceAreNotPartOfTheName() {
        val lines = mutableListOf("# Date Description Category Mode Reference Debit (₹) Credit (₹) Balance (₹)", "Balance brought forward 26,400.00")
        var bal = 26400.0
        listOf("03 Oct 25", "03 Nov 25", "03 Dec 25").forEachIndexed { i, d ->
            bal -= 179.0
            lines += "${i + 1} $d SI DR SPOTIFY FAMILY Subscriptions SI SI2510030${i}500 179.00 ${"%.2f".format(bal)}"
        }
        listOf("04 Oct 25", "04 Nov 25", "04 Dec 25").forEachIndexed { i, d ->
            bal -= 9500.0
            lines += "${i + 4} $d RENT SHARE - IMPS Rent IMPS IMP2510040${i}513 9,500.00 ${"%.2f".format(bal)}"
        }
        val found = DefaultRecurringDetector().detectRecurring(DefaultStatementParser().parseStatementLines(lines)).map { it.merchant }
        assertTrue(found.toString(), "Spotify Premium" in found)
        assertTrue(found.toString(), "Rent Share" in found)
    }
}
