package com.example

import com.example.parser.Confidence
import com.example.parser.DefaultRecurringDetector
import com.example.parser.DefaultStatementParser
import com.example.parser.PaymentRail
import com.example.parser.RawTransactionLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic statements that mimic how real Indian bank exports look: quoted CSV amounts,
 * repeated headers, multi-line narrations, Dr/Cr suffixes, opening-balance rows and PDF text
 * where columns are only separated by whitespace. All names, numbers and amounts are made up.
 */
class StatementParserRealisticTest {

    private val parser = DefaultStatementParser()
    private val detector = DefaultRecurringDetector()

    private fun List<RawTransactionLine>.debits() = filter { it.isDebit }
    private fun List<RawTransactionLine>.credits() = filter { !it.isDebit }

    // HDFC-style CSV: preamble, quoted Indian-grouped amounts, dd/MM/yy dates, repeated header on page 2
    private val hdfcCsv = """
        HDFC BANK LTD.,,,,,,
        Statement of account for period 01/08/2026 to 31/10/2026,,,,,,
        Account No :50100XXXXXXX12,,,,,,
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/08/26,"UPI-NETFLIX-netflix@icici-ICIC0000001-421900011-UPI",0000421900011,01/08/26,"649.00",,"1,24,351.00"
        03/08/26,"NEFT CR-ACME TECH PRIVATE LIMITED-SALARY AUG",N2608001,03/08/26,,"85,000.00","2,09,351.00"
        10/08/26,"ACH D- GROWW INVEST TECH-481921",0000009812401,10/08/26,"2,000.00",,"2,07,351.00"
        Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
        01/09/26,"UPI-NETFLIX-netflix@icici-ICIC0000001-523900022-UPI",0000523900022,01/09/26,"649.00",,"2,06,702.00"
        10/09/26,"ACH D- GROWW INVEST TECH-481921",0000009841092,10/09/26,"2,000.00",,"2,04,702.00"
        01/10/26,"UPI-NETFLIX-netflix@icici-ICIC0000001-624900033-UPI",0000624900033,01/10/26,"649.00",,"2,04,053.00"
        10/10/26,"ACH D- GROWW INVEST TECH-481921",0000009918294,10/10/26,"2,000.00",,"2,02,053.00"
    """.trimIndent().lines()

    @Test
    fun hdfcCsv_parsesQuotedAmountsPreambleAndRepeatedHeader() {
        val txs = parser.parseStatementLines(hdfcCsv)

        assertEquals(7, txs.size)
        assertEquals(1, txs.credits().size)
        assertEquals(85000.00, txs.credits().first().creditAmount, 0.01)
        assertTrue(txs.all { it.dateEpochMs > 0 })
        assertEquals(6, txs.debits().size)
        // The ref number column must never be mistaken for the amount
        assertTrue(txs.debits().all { it.debitAmount < 5000 })
    }

    @Test
    fun hdfcCsv_detectsNetflixAndGroww_ignoresSalary() {
        val detected = detector.detectRecurring(parser.parseStatementLines(hdfcCsv))

        val netflix = detected.find { it.merchant == "Netflix" }
        assertNotNull(netflix)
        assertEquals(649.00, netflix!!.amount, 0.01)
        assertNotNull(detected.find { it.merchant.contains("Groww") })
        assertFalse(detected.any { it.merchant.contains("Acme", ignoreCase = true) })
    }

    // SBI-style PDF text: whitespace separated, multi-line narrations, CR/DR on the balance, opening row, footers
    private val sbiPdfText = """
        State Bank of India
        Account Statement
        Date Description Ref No./Cheque No. Debit Credit Balance
        01 Aug 2026 OPENING BALANCE 25,000.00 CR
        01 Aug 2026 TRANSFER TO 48219012345/UPI/NETFLIX/429182910/UPI 429182910 649.00 24,351.00 CR
        BY UPI NETFLIX INDIA
        05 Aug 2026 BY TRANSFER-NEFT*HDFC0000001*ACME TECH PRIVATE LIMITED SALARY 40,000.00 64,351.00 CR
        10 Aug 2026 ACH D-ZERODHA BROKING LTD-482109 ACH90124 5,000.00 59,351.00 CR
        Page 1 of 2
        Date Description Ref No./Cheque No. Debit Credit Balance
        01 Sep 2026 TRANSFER TO 48219012345/UPI/NETFLIX/581920191/UPI 581920191 649.00 58,702.00 CR
        BY UPI NETFLIX INDIA
        10 Sep 2026 ACH D-ZERODHA BROKING LTD-482109 ACH98412 5,000.00 53,702.00 CR
        01 Oct 2026 TRANSFER TO 48219012345/UPI/NETFLIX/691028491/UPI 691028491 649.00 53,053.00 CR
        BY UPI NETFLIX INDIA
        10 Oct 2026 ACH D-ZERODHA BROKING LTD-482109 ACH10294 5,000.00 48,053.00 CR
        CLOSING BALANCE 48,053.00 CR
        This is a computer generated statement and does not require signature.
    """.trimIndent().lines()

    @Test
    fun sbiPdfText_skipsOpeningClosingAndFooters() {
        val txs = parser.parseStatementLines(sbiPdfText)

        assertEquals(7, txs.size)
        assertFalse(txs.any { it.description.contains("BALANCE", ignoreCase = true) })
        assertFalse(txs.any { it.description.contains("computer generated", ignoreCase = true) })
    }

    @Test
    fun sbiPdfText_balanceSuffixDoesNotTurnDebitsIntoCredits() {
        val txs = parser.parseStatementLines(sbiPdfText)

        assertEquals(6, txs.debits().size)
        val credit = txs.credits().single()
        assertEquals(40000.00, credit.creditAmount, 0.01)
        assertTrue(credit.description.contains("SALARY"))
    }

    @Test
    fun sbiPdfText_joinsWrappedNarrationLines() {
        val txs = parser.parseStatementLines(sbiPdfText)

        val firstNetflix = txs.first { it.description.contains("NETFLIX", ignoreCase = true) }
        assertEquals(649.00, firstNetflix.debitAmount, 0.01)
        assertTrue(firstNetflix.description.contains("BY UPI NETFLIX INDIA"))
    }

    @Test
    fun sbiPdfText_detectsRecurringDebits() {
        val detected = detector.detectRecurring(parser.parseStatementLines(sbiPdfText))

        assertNotNull(detected.find { it.merchant == "Netflix" && it.amount == 649.00 })
        val zerodha = detected.find { it.merchant.contains("Zerodha") }
        assertNotNull(zerodha)
        assertEquals(PaymentRail.NACH, zerodha!!.rail)
    }

    // ICICI-style CSV: separate debit/credit columns with 0.00 placeholders, dd-Mon-yyyy dates
    private val iciciCsv = """
        Transaction Date,Value Date,Cheque Number,Transaction Remarks,Withdrawal Amount (INR ),Deposit Amount (INR ),Balance (INR )
        20-Aug-2026,20-Aug-2026,-,UPI/SPOTIFY/spotify@paytm/Payment from ICICI/5928192,119.00,0.00,50000.00
        22-Aug-2026,22-Aug-2026,-,NEFT-ACME TECH SALARY,0.00,60000.00,109881.00
        20-Sep-2026,20-Sep-2026,-,UPI/SPOTIFY/spotify@paytm/Payment from ICICI/6829102,119.00,0.00,109762.00
        20-Oct-2026,20-Oct-2026,-,UPI/SPOTIFY/spotify@paytm/Payment from ICICI/7918201,119.00,0.00,109643.00
    """.trimIndent().lines()

    @Test
    fun iciciCsv_parsesDdMonYyyyAndZeroPlaceholders() {
        val txs = parser.parseStatementLines(iciciCsv)

        assertEquals(4, txs.size)
        assertTrue(txs.all { it.dateEpochMs > 0 })
        assertEquals(1, txs.credits().size)
        assertEquals(60000.00, txs.credits().first().creditAmount, 0.01)
        assertEquals(listOf(119.0, 119.0, 119.0), txs.debits().map { it.debitAmount })
    }

    // Axis-style PDF text: Dr/Cr markers right after the amount, two-digit years, no balance delta needed
    private val axisPdfText = """
        Tran Date Particulars Chq No Amount Dr/Cr Balance
        12-08-26 ACH/CULT FIT HEALTHCARE/UTIR0000123/98124 1,499.00 Dr 32,000.00
        15-08-26 UPI/CR/523900011/ACME TECH/SALARY 70,000.00 Cr 1,02,000.00
        08-09-26 UPI-MANDATE-HOTSTAR-hotstar@axis/9281948291 1,499.00 Dr 1,00,501.00
        12-09-26 ACH/CULT FIT HEALTHCARE/UTIR0000123/98912 1,499.00 Dr 99,002.00
        12-10-26 ACH/CULT FIT HEALTHCARE/UTIR0000123/99481 1,499.00 Dr 97,503.00
    """.trimIndent().lines()

    @Test
    fun axisPdfText_usesDrCrMarkersAndTwoDigitYears() {
        val txs = parser.parseStatementLines(axisPdfText)

        assertEquals(5, txs.size)
        assertEquals(1, txs.credits().size)
        assertEquals(70000.00, txs.credits().first().creditAmount, 0.01)
        assertTrue(txs.all { it.dateEpochMs > 0 })
    }

    @Test
    fun axisPdfText_detectsCultFitAndHotstarMandate() {
        val detected = detector.detectRecurring(parser.parseStatementLines(axisPdfText))

        val cult = detected.find { it.merchant == "Cult.fit" }
        assertNotNull(cult)
        assertEquals(PaymentRail.NACH, cult!!.rail)
        val hotstar = detected.find { it.merchant.contains("Hotstar") }
        assertNotNull(hotstar)
        assertEquals(Confidence.HIGH, hotstar!!.confidence)
    }

    // Kotak-style CSV: one amount column with a (Dr)/(Cr) suffix, quoted multi-line narration
    private val kotakCsv = "Date,Narration,Chq/Ref No,Withdrawal(Dr)/Deposit(Cr),Balance\n" +
        "01-08-2026,\"UPI/NETFLIX/421900011/\nPayment via Kotak\",421900011,649.00(Dr),24351.00(Cr)\n" +
        "03-08-2026,NEFT-ACME TECH PRIVATE LIMITED,N2608001,40000.00(Cr),64351.00(Cr)\n" +
        "01-09-2026,\"UPI/NETFLIX/523900022/\nPayment via Kotak\",523900022,649.00(Dr),63702.00(Cr)\n"

    @Test
    fun kotakCsv_singleAmountColumnWithDrCrSuffixAndQuotedNewlines() {
        val txs = parser.parseCsv(kotakCsv)

        assertEquals(3, txs.size)
        assertEquals(2, txs.debits().size)
        assertEquals(1, txs.credits().size)
        assertEquals(40000.00, txs.credits().first().creditAmount, 0.01)
        assertTrue(txs.debits().all { it.description.contains("Payment via Kotak") })
    }

    @Test
    fun windowsLineEndingsAndBlankLinesAreTolerated() {
        val csv = hdfcCsv.joinToString("\r\n", postfix = "\r\n\r\n")
        val txs = parser.parseCsv(csv)

        assertEquals(7, txs.size)
    }

    @Test
    fun dateFormatsRoundTrip() {
        val lines = listOf(
            "05/08/26 UPI/SWIGGY/swiggy@icici/4821 420.00 10,000.00",
            "06-Aug-2026 UPI/ZOMATO/zomato@hdfc/4822 300.00 9,700.00",
            "07 Aug 26 UPI/BLINKIT/blinkit@axis/4823 150.00 9,550.00",
            "2026-08-08 UPI/ZEPTO/zepto@icici/4824 100.00 9,450.00"
        )
        val txs = parser.parseStatementLines(lines)

        assertEquals(4, txs.size)
        assertTrue(txs.all { it.dateEpochMs > 0 })
        assertEquals(listOf(420.0, 300.0, 150.0, 100.0), txs.map { it.debitAmount })
        // Balance-delta inference marks all of these as debits
        assertTrue(txs.all { it.isDebit })
    }
}
