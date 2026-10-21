package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shapes taken from real bank PDF exports (ICICI, SBI loan account, HDFC), with invented names and numbers.
 */
class RealBankLayoutsTest {
    private val parser = DefaultStatementParser()
    private val detector = DefaultRecurringDetector()

    // ICICI: serial number, value date and transaction date, narration wrapped over lines,
    // "0.0" in the column that is empty
    @Test
    fun iciciStyleWrappedRowsAndZeroColumns() {
        val lines = listOf(
            "S No. Value Date Transaction Date Cheque Number Transaction Remarks Withdrawal Amount",
            "(INR )", "Deposit Amount", "(INR )", "Balance (INR )",
            "1 03/10/2023 03/10/2023 - AS PER SHEET 0.0 2500.00 2885.84",
            "2", "07/10/2023 07/10/2023 - NFS/CASH", "WDL/328009020022/06094066/ALWA", "R /07-10", "2500.00 0.0 385.84",
            "3 09/10/2023 09/10/2023 - TRANSFER FROM SALARY 0.0 25000.00 25385.84",
            "4", "09/10/2023 09/10/2023 - UPI/WATER", "SUPPLIER/328219139980", "240.00 0.0 25145.84",
            "99 10/11/2023 10/11/2023 - UPI/WATER SUPPLIER/328219139981 240.00 0.0 24905.84",
            "100 10/12/2023 10/12/2023 - UPI/WATER SUPPLIER/328219139982 240.00 0.0 24665.84"
        )
        val txs = parser.parseStatementLines(lines)
        assertEquals(6, txs.size)
        assertEquals(2500.0, txs[0].creditAmount, 0.001)
        assertEquals(2500.0, txs[1].debitAmount, 0.001)
        assertEquals(25000.0, txs[2].creditAmount, 0.001)
        assertEquals(240.0, txs.last().debitAmount, 0.001)
        // Cash withdrawals are not subscriptions
        assertFalse(detector.detectRecurring(txs).any { it.merchant.contains("Cash", true) })
    }

    // SBI loan account: newest entry first, value date in brackets on its own line, balances negative
    @Test
    fun sbiLoanStatementNewestFirstWithNegativeBalances() {
        val lines = listOf(
            "Date (Value Date) Narration Ref/Cheque No. Debit Credit Balance",
            "01-Mar-23", "(01-Mar-2023)", "DEPOSIT TRANSFER", "TRANSFER FROM 30297061482 Mrs. A", "BANDI", "20,340.00 -9,65,249.50",
            "28-Feb-23", "(28-Feb-2023)", "INTEREST 8,414.00 -9,85,589.50",
            "20-Feb-23", "(20-Feb-2023)", "DEPOSIT TRANSFER", "TRANSFER FROM 30297061482 Mrs. A", "BANDI", "20,340.00 -9,77,175.50",
            "31-Jan-23", "(31-Jan-2023)", "INTEREST 9,476.00 -9,97,515.50",
            "01-Jan-23", "(01-Jan-2023)", "DEPOSIT TRANSFER", "TRANSFER FROM 30297061482 Mrs. A", "BANDI", "20,340.00 -9,88,039.50",
            "31-Dec-22", "(31-Dec-2022)", "INTEREST 9,586.00 -10,08,379.50"
        )
        val txs = parser.parseStatementLines(lines)
        assertEquals(6, txs.size)
        val interest = txs.filter { it.description.contains("INTEREST") }
        assertEquals(3, interest.size)
        assertTrue(interest.all { it.isDebit })
        // The deposits are credits, even though the balance is negative
        assertEquals(3, txs.count { !it.isDebit && it.creditAmount == 20340.0 })
        assertTrue(txs.none { it.description.startsWith(")") })
    }

    // HDFC: one line per row, a value date after the narration, cheque-paid EMI, bank charges
    @Test
    fun hdfcStyleChequeEmiAndBankCharges() {
        val lines = mutableListOf("Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance")
        var bal = 20000.0
        listOf("05/05/16" to "05/05/16", "06/06/16" to "06/06/16", "05/07/16" to "05/07/16").forEachIndexed { i, (d, v) ->
            bal -= 3334.0
            lines += "$d EMI 38810601 CHQ S38810601/1-${i + 1} 0${i + 5}/16 000000000000000 $v 3,334.00 ${"%,.2f".format(bal)}"
            bal -= 114.5
            lines += "${d.take(3)}${(i + 1) * 3}/16 3RD PARTY CASH TXN ST & EC 000000000000000 $v 114.50 ${"%,.2f".format(bal)}"
        }
        val found = detector.detectRecurring(parser.parseStatementLines(lines)).map { it.merchant }
        assertTrue(found.toString(), found.any { it.contains("EMI", true) })
        assertFalse(found.toString(), found.any { it.contains("Cash", true) })
    }

    @Test
    fun aNameThatContainsSiSlashIsNotAStandingInstruction() {
        val lines = listOf(
            "01/05/2024 UPI/SHIVDEEP SI/DHAR/411 500.00 10,000.00",
            "01/06/2024 UPI/SHIVDEEP SI/DHAR/412 640.00 9,360.00",
            "01/07/2024 UPI/SHIVDEEP SI/DHAR/413 910.00 8,450.00"
        )
        assertTrue(detector.detectRecurring(parser.parseStatementLines(lines)).isEmpty())
    }
}
