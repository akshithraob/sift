package com.leakproof.app

import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.DefaultStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Narration styles from bank exports: slash-separated, month-tagged EMIs, wrapped words, ACH prefixes. Synthetic. */
class BankNarrationStylesTest {
    private val parser = DefaultStatementParser()
    private val detector = DefaultRecurringDetector()

    @Test
    fun monthTaggedEmisAreOneLoanNotThree() {
        val lines = listOf(
            "03/01/2026 03/01/2026 - ZENFIN CONSUMER", "LOAN/3695275087/EMI JAN26", "3449.00 0.0 40000.00",
            "03/02/2026 03/02/2026 - ZENFIN CONSUMER", "LOAN/9674230435/EMI FEB26", "3449.00 0.0 36551.00",
            "03/03/2026 03/03/2026 - ZENFIN CONSUMER", "LOAN/1122334455/EMI MAR26", "3449.00 0.0 33102.00"
        )
        val found = detector.detectRecurring(parser.parseStatementLines(lines))
        assertEquals(found.toString(), 1, found.size)
        assertEquals("Zenfin Consumer Loan Emi", found.single().merchant)
        assertEquals(3449.0, found.single().amount, 0.001)
    }

    @Test
    fun wrappedNarrationWordsAreKept() {
        val lines = mutableListOf<String>()
        listOf("18/01/2026", "18/02/2026", "18/03/2026").forEachIndexed { i, d ->
            lines += "$d $d - SI/20260118791${i}/79158075573${i}/YOUTUBE"
            lines += "PREMIUM"
            lines += "149.00 0.0 ${10000 - i * 149}.00"
        }
        // Other wrapped lines that end in the same word, under different narrations
        listOf("20/01/2026" to "LINKEDIN", "20/02/2026" to "SPOTIFY", "20/03/2026" to "DUOLINGO").forEach { (d, n) ->
            lines += "$d $d - SI/20260120${n.length}/12345678${n.length}/$n"
            lines += "PREMIUM"
            lines += "99.00 0.0 5000.00"
        }
        val txs = parser.parseStatementLines(lines)
        assertTrue(txs.all { it.description.contains("PREMIUM") })
        assertTrue(detector.detectRecurring(txs).any { it.merchant == "Youtube Premium" })
    }

    @Test
    fun achAndMandateWordsAroundTheNameAreTrimmed() {
        val detector = DefaultRecurringDetector()
        assertEquals("Netflix", detector.normalizeMerchantName("DEBIT-ACHDr 099405 NETFLIX SI MANDATE"))
        assertEquals("Youtube Premium", detector.normalizeMerchantName("ACH D- YOUTUBE PREMIUM-3516051069"))
        assertEquals("Amazon Prime Lite", detector.normalizeMerchantName("- DEBIT-ACHDr 787389 AMAZON PRIME LITE SI MANDATE"))
        assertEquals("Kestrel Bike Loan Emi", detector.normalizeMerchantName("- ACH/KESTREL BIKE LOAN/5876965106/EMI"))
    }

    @Test
    fun emiPaidByChequeKeepsItsMeaning() {
        val name = DefaultRecurringDetector().normalizeMerchantName("EMI 80493237 CHQ S804932370126 ZENFIN")
        assertTrue(name, name.contains("Zenfin") && name.contains("EMI", ignoreCase = true))
    }

    @Test
    fun atmWithdrawalCodedNwdIsCashNotASubscription() {
        val lines = listOf(
            "05/01/26 NWD-S1MTB07 KOLKATA 000000000000000 05/01/26 1,000.00 9,000.00",
            "05/02/26 NWD-S1MTB07 KOLKATA 000000000000000 05/02/26 1,000.00 8,000.00",
            "05/03/26 NWD-S1MTB07 KOLKATA 000000000000000 05/03/26 1,000.00 7,000.00"
        )
        assertFalse(detector.detectRecurring(parser.parseStatementLines(lines)).any())
    }
}
