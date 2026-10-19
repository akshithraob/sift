package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header and value conventions seen across bank and app CSV exports. Synthetic rows. */
class CsvVariantsTest {
    private val parser = DefaultStatementParser()
    private val detector = DefaultRecurringDetector()

    @Test
    fun yearFirstSlashDatesWithWithdrawalAndDepositColumns() {
        val csv = """
            # SAMPLE header comment line
            Posting Date,Transaction Details,Reference No,Withdrawals,Deposits,Closing Balance,Budget Bucket
            2026/08/01,PENSION CREDIT - FUND,NEF26080105735,0.00,53700.00,150100.00,Income
            2026/08/05,AUTOPAY SUN NXT,SI26080505800,50.00,0.00,150050.00,Wants/Other
            2026/09/05,AUTOPAY SUN NXT,SI26090506281,50.00,0.00,150000.00,Wants/Other
            2026/10/05,AUTOPAY SUN NXT,SI26100506957,50.00,0.00,149950.00,Wants/Other
        """.trimIndent()
        val txs = parser.parseCsv(csv)
        assertEquals(4, txs.size)
        assertEquals(53700.0, txs.first().creditAmount, 0.001)
        assertTrue(detector.detectRecurring(txs).any { it.merchant == "Sun Nxt" })
    }

    @Test
    fun payeeTypeAndCategoryColumns() {
        val csv = """
            Date,Day,Month,Payee / Source,Payment Mode,Category,Type,Amount,Balance After
            2025-11-02,Sun,2025-11,AUTOPAY NETFLIX PREMIUM,SI,Subscriptions,Expense,649.00,511351.00
            2025-11-05,Wed,2025-11,RENT CR - SHOP,NEFT,Rental Income,Income,28000.00,539351.00
            2025-12-02,Tue,2025-12,AUTOPAY NETFLIX PREMIUM,SI,Subscriptions,Expense,649.00,538702.00
            2026-01-02,Fri,2026-01,AUTOPAY NETFLIX PREMIUM,SI,Subscriptions,Expense,649.00,538053.00
        """.trimIndent()
        val txs = parser.parseCsv(csv)
        assertEquals(4, txs.size)
        assertEquals(1, txs.count { !it.isDebit })
        assertEquals("Subscriptions", txs.first().category)
        assertTrue(detector.detectRecurring(txs).any { it.merchant == "Netflix" })
    }

    @Test
    fun signedAmountColumnMinusIsMoneyOut() {
        val csv = """
            txn_id,txn_date,value_date,narration,mode,category,amount,running_balance
            1,01/04/2026,01/04/2026,SALARY - ZENITH (FICTIONAL),NEFT,Salary,111800.00,356800.00
            2,02/04/2026,02/04/2026,UPI - EATSURE,UPI,Food Delivery,-645.34,356154.66
            3,03/04/2026,03/04/2026,AUTOPAY YOUTUBE PREMIUM,SI,Subscriptions,-149.00,356005.66
            4,03/05/2026,03/05/2026,AUTOPAY YOUTUBE PREMIUM,SI,Subscriptions,-149.00,355856.66
            5,03/06/2026,03/06/2026,AUTOPAY YOUTUBE PREMIUM,SI,Subscriptions,-149.00,355707.66
        """.trimIndent()
        val txs = parser.parseCsv(csv)
        assertEquals(5, txs.size)
        assertEquals(1, txs.count { !it.isDebit })
        assertEquals(645.34, txs[1].debitAmount, 0.001)
        assertTrue(detector.detectRecurring(txs).any { it.merchant == "Youtube Premium" })
    }

    @Test
    fun statementCategoryAndCashKeepOrdinarySpendingOut() {
        val csv = """
            Date,Description,Category,Reference,Debit (INR),Credit (INR),Balance (INR)
            2026-03-07,ATM WDL - BANER (DEMO),Cash,ATM1,5000.00,,95000.00
            2026-04-07,ATM WDL - BANER (DEMO),Cash,ATM2,5000.00,,90000.00
            2026-05-07,ATM WDL - BANER (DEMO),Cash,ATM3,5000.00,,85000.00
            2026-03-14,POS - UDUPI CAFE,Dining,POS1,820.00,,84180.00
            2026-04-14,POS - UDUPI CAFE,Dining,POS2,820.00,,83360.00
            2026-05-14,POS - UDUPI CAFE,Dining,POS3,820.00,,82540.00
            2026-03-14,AUTOPAY HEADSPACE,Subscriptions,SI1,399.00,,82141.00
            2026-04-14,AUTOPAY HEADSPACE,Subscriptions,SI2,399.00,,81742.00
        """.trimIndent()
        val found = detector.detectRecurring(parser.parseCsv(csv)).map { it.merchant }
        assertEquals(listOf("Headspace"), found)
    }

    @Test
    fun changingAmountsCountOnlyForBills() {
        val rows = listOf(
            "2026-03-08" to 953.0, "2026-04-07" to 1460.0, "2026-05-09" to 1236.0
        ).map { (d, a) -> "$d,MSEDCL ELECTRICITY (SAMPLE),Utilities,BBP,$a,,1000.00" } +
            listOf("2026-03-10" to 1125.0, "2026-04-09" to 1700.0, "2026-05-11" to 1450.0)
                .map { (d, a) -> "$d,UPI P2P - VEGETABLE VENDOR,Transfers,UPI,$a,,1000.00" }
        val csv = "Date,Description,Category,Reference,Debit (INR),Credit (INR),Balance (INR)\n" + rows.joinToString("\n")
        val found = detector.detectRecurring(parser.parseCsv(csv)).map { it.merchant }
        assertTrue(found.toString(), found.contains("Msedcl Electricity"))
        assertFalse(found.toString(), found.any { it.contains("Vegetable") })
    }
}
