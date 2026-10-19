package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledger layout with a transaction date AND a value date at the start of each entry, channel words in the
 * narration (ECS/SI DR, NACH DR, SIP DR, EMI DR) and mixed letter-digit reference numbers. Synthetic.
 */
class ValueDateLayoutStatementTest {
    private fun lines(): List<String> {
        val out = mutableListOf(
            "Period 01 Apr 2026 - 30 Jun 2026",
            "ACCOUNT LEDGER Test Customer (sample) - 01 Apr - 19 Apr 2026",
            "Txn Date Value Dt Particulars Chq / Ref No. Withdrawal Deposit Balance",
            "Opening balance 48,250.00 Cr"
        )
        var bal = 148250.0
        var n = 7300
        fun tx(date: String, text: String, category: String, amount: Double, credit: Boolean = false) {
            bal += if (credit) amount else -amount
            out += "$date $date $text"
            out += category
            val ref = "04${n++}SI00${n}"
            out += "$ref ${"%.2f".format(amount)} ${"%.2f".format(bal)}"
        }
        val months = listOf("04", "05", "06")
        for ((i, mm) in months.withIndex()) {
            tx("01/$mm/26", "NEFT CR - EMPLOYER LLP SALARY", "SALARY", 92400.0, credit = true)
            tx("02/$mm/26", "ECS/SI DR - JIOHOTSTAR SUPER", "SUBSCRIPTIONS", 299.0)
            tx("09/$mm/26", "ECS/SI DR - MICROSOFT 365 PERSONAL", "SUBSCRIPTIONS", 619.0)
            tx("04/$mm/26", "EMI DR - HOME LOAN A/C", "LOAN EMI", 31800.0)
            tx("06/$mm/26", "SIP DR - FLEXI CAP FUND", "INVESTMENTS", 10000.0)
            tx("2${i}/$mm/26", "UPI DR - SOME SHOP", "SHOPPING", listOf(4953.0, 697.0, 5184.0)[i])
        }
        return out
    }

    @Test
    fun valueDateLinesAreTransactionsNotPeriodHeaders() {
        val txs = DefaultStatementParser().parseStatementLines(lines())
        assertEquals(18, txs.size)
        assertEquals(3, txs.count { !it.isDebit })
        assertTrue(txs.none { it.description.contains("SI00") || it.description.startsWith("0") })
    }

    @Test
    fun channelWordsAreStrippedAndRecurringFoundWithoutNameTraining() {
        val txs = DefaultStatementParser().parseStatementLines(lines())
        val found = DefaultRecurringDetector().detectRecurring(txs).associateBy { it.merchant }
        assertEquals(619.0, found.getValue("Microsoft Personal").amount, 0.001)
        assertEquals(31800.0, found.getValue("Home Loan A C").amount, 0.001)
        assertEquals(10000.0, found.getValue("Flexi Cap Fund").amount, 0.001)
        assertTrue(found.containsKey("Jiohotstar Super"))
        assertFalse(found.keys.any { it.contains("Shop", ignoreCase = true) })
    }
}
