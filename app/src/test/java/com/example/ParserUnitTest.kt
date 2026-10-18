package com.example

import com.example.parser.Confidence
import com.example.parser.DefaultMandateScreenshotParser
import com.example.parser.DefaultRecurringDetector
import com.example.parser.DefaultStatementParser
import com.example.parser.Frequency
import com.example.parser.OcrBoundingBox
import com.example.parser.OcrDocument
import com.example.parser.OcrTextBlock
import com.example.parser.OcrTextLine
import com.example.parser.ParsedMandateCandidate
import com.example.parser.PaymentRail
import com.example.parser.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserUnitTest {

    @Test
    fun testMandateScreenshotParser_googlePayOcr_spatialCards() {
        val parser = DefaultMandateScreenshotParser()

        // Construct realistic Google Pay Autopay screen with 2 vertically stacked cards
        val lines = listOf(
            // Header
            OcrTextLine("Google Pay", OcrBoundingBox(left = 40, top = 50, right = 300, bottom = 90)),
            OcrTextLine("Autopay", OcrBoundingBox(left = 40, top = 95, right = 200, bottom = 135)),

            // Card 1: Netflix
            OcrTextLine("Netflix India", OcrBoundingBox(left = 50, top = 180, right = 400, bottom = 220)),
            OcrTextLine("Up to ₹1,000", OcrBoundingBox(left = 50, top = 225, right = 250, bottom = 255)),
            OcrTextLine("Debited: ₹649.00", OcrBoundingBox(left = 50, top = 260, right = 300, bottom = 290)),
            OcrTextLine("Frequency: Monthly", OcrBoundingBox(left = 50, top = 295, right = 320, bottom = 325)),
            OcrTextLine("Next payment: 24 Oct 2026", OcrBoundingBox(left = 50, top = 330, right = 450, bottom = 360)),
            OcrTextLine("HDFC Bank ending in 4821", OcrBoundingBox(left = 50, top = 365, right = 480, bottom = 395)),

            // Large gap (Card 2 begins at Y = 500)
            OcrTextLine("Cult.fit", OcrBoundingBox(left = 50, top = 500, right = 300, bottom = 540)),
            OcrTextLine("Up to ₹2,500", OcrBoundingBox(left = 50, top = 545, right = 250, bottom = 575)),
            OcrTextLine("₹1,499.00 / month", OcrBoundingBox(left = 50, top = 580, right = 350, bottom = 610)),
            OcrTextLine("Next payment: 28-Oct-2026", OcrBoundingBox(left = 50, top = 615, right = 450, bottom = 645)),
            OcrTextLine("Debited from A/c XX4821", OcrBoundingBox(left = 50, top = 650, right = 420, bottom = 680))
        )

        val doc = OcrDocument(blocks = listOf(OcrTextBlock(text = "Google Pay Autopay", lines = lines)))
        val results = parser.parseOcrDocument(doc)

        assertEquals("Should extract 2 separate mandate cards", 2, results.size)

        val netflix = results.find { it.merchant.contains("Netflix", ignoreCase = true) }
        assertNotNull("Should detect Netflix mandate", netflix)
        assertEquals(649.00, netflix?.amount ?: 0.0, 0.01)
        assertEquals(1000.00, netflix?.maxAmount ?: 0.0, 0.01)
        assertEquals(Frequency.MONTHLY, netflix?.frequency)
        assertEquals("4821", netflix?.last4)
        assertTrue(netflix?.nextDebitDate?.contains("24 Oct 2026") == true)

        val cultfit = results.find { it.merchant.contains("Cult", ignoreCase = true) }
        assertNotNull("Should detect Cult.fit mandate", cultfit)
        assertEquals(1499.00, cultfit?.amount ?: 0.0, 0.01)
        assertEquals(Frequency.MONTHLY, cultfit?.frequency)
        assertEquals("4821", cultfit?.last4)
    }

    @Test
    fun testMandateScreenshotParser_phonePe_numericDatesAndCommas() {
        val parser = DefaultMandateScreenshotParser()

        val sampleText = """
            PhonePe
            AutoPay Settings
            Spotify Premium
            Auto-pay enabled
            ₹119
            Debited on 20th of every month
            Next payment on 20/10/2026
            Paid via Bank ending in 9012
            
            Amazon Prime
            ₹1,499
            Frequency: Yearly
            Next payment on 17/12/2026
            A/c ending in 4821
        """.trimIndent()

        val results = parser.parseOcrText(sampleText, "PhonePe")
        assertEquals(2, results.size)

        val spotify = results.find { it.merchant.contains("Spotify", ignoreCase = true) }
        assertNotNull("Should detect Spotify", spotify)
        assertEquals(119.00, spotify?.amount ?: 0.0, 0.01)
        assertEquals("9012", spotify?.last4)
        assertTrue(spotify?.nextDebitDate?.contains("20/10/2026") == true)

        val prime = results.find { it.merchant.contains("Amazon", ignoreCase = true) }
        assertNotNull("Should detect Amazon Prime", prime)
        assertEquals(1499.00, prime?.amount ?: 0.0, 0.01)
        assertEquals(Frequency.YEARLY, prime?.frequency)
        assertTrue(prime?.nextDebitDate?.contains("17/12/2026") == true)
    }

    @Test
    fun testMandateScreenshotParser_paytm_asPresentedAndLimits() {
        val parser = DefaultMandateScreenshotParser()

        val sampleText = """
            Paytm
            Manage Automatic Payments
            Disney+ Hotstar
            ₹1,499.00 / year
            Next debit: 15 Nov 2026
            Paytm Payments Bank ending in 3310
            
            Tata Play
            Up to ₹1,500
            Frequency: As presented
            Next debit: 05 Nov 2026
            Status: Active
        """.trimIndent()

        val results = parser.parseOcrText(sampleText, "Paytm")
        assertEquals(2, results.size)

        val hotstar = results.find { it.merchant.contains("Hotstar", ignoreCase = true) }
        assertNotNull(hotstar)
        assertEquals(1499.00, hotstar?.amount ?: 0.0, 0.01)
        assertEquals(Frequency.YEARLY, hotstar?.frequency)
        assertEquals("3310", hotstar?.last4)

        val tataPlay = results.find { it.merchant.contains("Tata Play", ignoreCase = true) }
        assertNotNull(tataPlay)
        assertEquals(1500.00, tataPlay?.amount ?: 0.0, 0.01)
        assertEquals(Frequency.AS_PRESENTED, tataPlay?.frequency)
    }

    @Test
    fun testMandateScreenshotParser_mergeDuplicates() {
        val parser = DefaultMandateScreenshotParser()

        // 2 candidates of the same mandate extracted from overlapping screenshots
        val cand1 = ParsedMandateCandidate(
            id = "1",
            merchant = "Netflix India",
            amount = 649.00,
            maxAmount = 1000.00,
            frequency = Frequency.MONTHLY,
            nextDebitDate = "24 Oct 2026",
            nextDebitDateEpochMs = 1792840000000L,
            rail = PaymentRail.UPI,
            source = SourceType.SCREENSHOT,
            sourceApp = "Google Pay",
            last4 = "4821",
            confidence = Confidence.HIGH
        )

        val cand2 = ParsedMandateCandidate(
            id = "2",
            merchant = "Netflix",
            amount = 649.00,
            frequency = Frequency.MONTHLY,
            startDate = "15 Jan 2024",
            endDate = "Until Revoked",
            rail = PaymentRail.UPI,
            source = SourceType.SCREENSHOT,
            sourceApp = "Google Pay",
            last4 = "4821",
            confidence = Confidence.MEDIUM
        )

        val merged = parser.mergeDuplicates(listOf(cand1, cand2))
        assertEquals("Should merge into 1 candidate", 1, merged.size)
        val single = merged.first()
        assertEquals("Netflix India", single.merchant)
        assertEquals(649.00, single.amount, 0.01)
        assertEquals(1000.00, single.maxAmount ?: 0.0, 0.01)
        assertEquals("24 Oct 2026", single.nextDebitDate)
        assertEquals("15 Jan 2024", single.startDate)
        assertEquals("Until Revoked", single.endDate)
        assertEquals(Confidence.HIGH, single.confidence)
    }

    @Test
    fun testStatementParser_sbiFormat() {
        val parser = DefaultStatementParser()
        val detector = DefaultRecurringDetector()

        val sbiLines = parser.getBankSampleLines("SBI")
        val txs = parser.parseStatementLines(sbiLines)

        // Verify extraction of date, debit, credit
        assertTrue("Extracted SBI transactions", txs.size >= 6)
        val netflixTx = txs.filter { it.description.contains("NETFLIX") }
        assertEquals(3, netflixTx.size)
        assertTrue(netflixTx.all { it.isDebit && it.debitAmount == 649.00 })

        // Detect recurring debits
        val detected = detector.detectRecurring(txs)
        val netflix = detected.find { it.merchant.contains("Netflix", ignoreCase = true) }
        assertNotNull("Should detect Netflix from SBI", netflix)
        assertEquals(649.00, netflix?.amount ?: 0.0, 0.01)
        assertEquals(Frequency.MONTHLY, netflix?.frequency)
        assertEquals(Confidence.HIGH, netflix?.confidence)

        val zerodha = detected.find { it.merchant.contains("Zerodha", ignoreCase = true) }
        assertNotNull("Should detect Zerodha from SBI ACH", zerodha)
        assertEquals(5000.00, zerodha?.amount ?: 0.0, 0.01)
        assertEquals(PaymentRail.NACH, zerodha?.rail)
    }

    @Test
    fun testStatementParser_hdfcFormat() {
        val parser = DefaultStatementParser()
        val detector = DefaultRecurringDetector()

        val hdfcLines = parser.getBankSampleLines("HDFC")
        val txs = parser.parseStatementLines(hdfcLines)

        assertTrue(txs.isNotEmpty())
        val detected = detector.detectRecurring(txs)

        val groww = detected.find { it.merchant.contains("Groww", ignoreCase = true) }
        assertNotNull("Should detect Groww SIP from HDFC ACH", groww)
        assertEquals(2000.00, groww?.amount ?: 0.0, 0.01)
        assertEquals(PaymentRail.NACH, groww?.rail)
        assertEquals(Confidence.HIGH, groww?.confidence)
    }

    @Test
    fun testStatementParser_iciciFormat_varianceAndIntervals() {
        val parser = DefaultStatementParser()
        val detector = DefaultRecurringDetector()

        val iciciLines = parser.getBankSampleLines("ICICI")
        val txs = parser.parseStatementLines(iciciLines)

        assertTrue(txs.isNotEmpty())
        val detected = detector.detectRecurring(txs)

        // Spotify recurring monthly
        val spotify = detected.find { it.merchant.contains("Spotify", ignoreCase = true) }
        assertNotNull("Should detect Spotify from ICICI UPI", spotify)
        assertEquals(119.00, spotify?.amount ?: 0.0, 0.01)
        assertEquals(Frequency.MONTHLY, spotify?.frequency)
        assertEquals(PaymentRail.UPI, spotify?.rail)

        // Bescom Electricity with ±10% variation (1850, 1910, 1880)
        val bescom = detected.find { it.merchant.contains("Bescom", ignoreCase = true) }
        assertNotNull("Should detect Bescom SI despite variable monthly amounts", bescom)
        assertEquals(PaymentRail.SI, bescom?.rail)
        assertEquals(Frequency.MONTHLY, bescom?.frequency)
        assertTrue(bescom!!.amount in 1850.0..1910.0)
    }

    @Test
    fun testStatementParser_axisFormat() {
        val parser = DefaultStatementParser()
        val detector = DefaultRecurringDetector()

        val axisLines = parser.getBankSampleLines("AXIS")
        val txs = parser.parseStatementLines(axisLines)

        assertTrue(txs.isNotEmpty())
        val detected = detector.detectRecurring(txs)

        // Cult.fit ACH monthly
        val cultfit = detected.find { it.merchant.contains("Cult", ignoreCase = true) }
        assertNotNull("Should detect Cult.fit from Axis ACH", cultfit)
        assertEquals(1499.00, cultfit?.amount ?: 0.0, 0.01)
        assertEquals(PaymentRail.NACH, cultfit?.rail)

        // Hotstar UPI-MANDATE
        val hotstar = detected.find { it.merchant.contains("Hotstar", ignoreCase = true) }
        assertNotNull("Should detect Hotstar mandate from Axis", hotstar)
        assertEquals(1499.00, hotstar?.amount ?: 0.0, 0.01)
        assertEquals(Confidence.HIGH, hotstar?.confidence)
    }

    @Test
    fun testMerchantNormalizer_cleansNoiseAndUpiIds() {
        val detector = DefaultRecurringDetector()

        assertEquals("Netflix", detector.normalizeMerchantName("TRANSFER TO 48219012345/UPI/NETFLIX/429182910/UPI"))
        assertEquals("Swiggy One", detector.normalizeMerchantName("UPI-SWIGGY-swiggy@icici-HDFC000123-429182910-UPI"))
        assertEquals("Spotify Premium", detector.normalizeMerchantName("UPI/SPOTIFY/spotify@paytm/Payment from ICICI/5928192"))
        assertEquals("Cult.fit", detector.normalizeMerchantName("ACH/CULT FIT HEALTHCARE/UTIR0000123/98124"))
        assertEquals("Zerodha Coin (SIP)", detector.normalizeMerchantName("ACH D-ZERODHA BROKING LTD-482109"))
        assertEquals("BESCOM Electricity", detector.normalizeMerchantName("SI-BESCOM ELECTRICITY AUTO DEBIT-4821"))
    }

    @Test
    fun testDetector_doesNotInventAccountLast4() {
        val parser = DefaultStatementParser()
        val detector = DefaultRecurringDetector()
        val txs = parser.parseStatementLines(parser.getBankSampleLines("SBI"))
        val detected = detector.detectRecurring(txs)

        assertTrue(detected.isNotEmpty())
        // SBI sample narrations carry no masked account digits, so none may be fabricated
        assertTrue(detected.all { it.last4 == null })
    }

    @Test
    fun testMerchantNormalizer_upiIdsWithHyphenatedPrefixes() {
        val detector = DefaultRecurringDetector()

        assertEquals("Disney+ Hotstar", detector.normalizeMerchantName("UPI-MANDATE-HOTSTAR-hotstar@axis/9281948291"))
        assertEquals("Netflix", detector.normalizeMerchantName("UPI-NETFLIX-netflix.billing@okicici-ICIC0000001-512345678-UPI"))
        assertEquals("Zomato Gold", detector.normalizeMerchantName("POS 482190 ZOMATO GOLD MUMBAI IN"))
    }

    @Test
    fun testScreenshotParser_dailyFrequencyIsNotWeekly() {
        val parser = DefaultMandateScreenshotParser()
        val result = parser.parseOcrText(
            """
            Autopay
            Blinkit
            ₹100
            Daily
            Next payment 25 Dec 2099
            """.trimIndent(),
            "PhonePe"
        )

        assertEquals(1, result.size)
        assertEquals(Frequency.AS_PRESENTED, result[0].frequency)
    }

    @Test
    fun testScreenshotParser_amountLimitLineIsNotMerchant() {
        val parser = DefaultMandateScreenshotParser()
        val result = parser.parseOcrText(
            """
            Autopay
            Up to 1,999
            Hungama Digital
            Every month
            """.trimIndent(),
            "Google Pay"
        )

        assertEquals(1, result.size)
        assertEquals("Hungama Digital", result[0].merchant)
        assertEquals(1999.0, result[0].amount, 0.01)
    }

    @Test
    fun testScreenshotParser_googlePayAutomaticPaymentsRealLayout() {
        fun line(text: String, l: Int, t: Int, r: Int, b: Int) =
            OcrTextLine(text, OcrBoundingBox(l, t, r, b))

        // Synthetic reproduction of the GPay "Automatic payments" (Live tab) layout, 720px wide
        val lines = listOf(
            line("Automatic payments", 45, 235, 536, 285),
            line("Set up once and never miss a deadline", 45, 310, 521, 345),
            line("How to use Autopay", 90, 420, 346, 452),
            line("Live", 95, 565, 145, 598),
            line("Pending", 308, 565, 412, 598),
            line("Completed", 528, 565, 671, 598),
            line("GOOGLE", 150, 680, 281, 712),
            line("Up to ₹1,999", 503, 680, 675, 712),
            line("playstore1.bd@axis...", 150, 722, 407, 756),
            line("As presented", 506, 722, 675, 756),
            line("Autopay on • 1 payment completed", 195, 786, 578, 820),
            line("Easily manage Autopay from", 182, 945, 588, 980),
            line("other apps on Google Pay", 182, 990, 549, 1025),
            line("Transfer now", 182, 1055, 347, 1088)
        )
        val result = DefaultMandateScreenshotParser()
            .parseOcrDocument(OcrDocument(listOf(OcrTextBlock("", null, lines))))

        assertEquals(1, result.size)
        val m = result[0]
        assertEquals("Google Play", m.merchant)
        assertEquals("Google Pay", m.sourceApp)
        assertEquals(1999.0, m.amount, 0.01)
        assertEquals(1999.0, m.maxAmount ?: 0.0, 0.01)
        assertEquals(Frequency.AS_PRESENTED, m.frequency)
        // The VPA must never be carried into the candidate
        assertTrue(m.confidenceReason.none { it == '@' })
    }

    private fun ocr(text: String, l: Int, t: Int, r: Int, b: Int) = OcrTextLine(text, OcrBoundingBox(l, t, r, b))

    private fun playSubscriptionsScreen() = OcrDocument(
        listOf(
            OcrTextBlock(
                "",
                null,
                listOf(
                    ocr("Subscriptions", 105, 90, 353, 135),
                    ocr("Google may share subscription data that doesn't", 45, 190, 617, 225),
                    ocr("Active", 45, 380, 160, 425),
                    ocr("Claude by Anthropic", 181, 478, 429, 510),
                    ocr("Claude Pro Subscription", 181, 514, 426, 542),
                    ocr("Next payment: ₹1,999.00 on 20 Oct 2026", 181, 545, 594, 572),
                    ocr("Plan benefits", 60, 645, 219, 675),
                    ocr("Higher usage for more conversations", 105, 695, 476, 722)
                )
            )
        )
    )

    private fun googlePayMandateScreen() = OcrDocument(
        listOf(
            OcrTextBlock(
                "",
                null,
                listOf(
                    ocr("Automatic payments", 45, 235, 536, 285),
                    ocr("Live", 95, 565, 145, 598),
                    ocr("GOOGLE", 150, 680, 281, 712),
                    ocr("Up to ₹1,999", 503, 680, 675, 712),
                    ocr("playstore1.bd@axis...", 150, 722, 407, 756),
                    ocr("As presented", 506, 722, 675, 756),
                    ocr("Autopay on • 1 payment completed", 195, 786, 578, 820)
                )
            )
        )
    )

    @Test
    fun testScreenshotParser_playStoreSubscriptionsScreen() {
        val result = DefaultMandateScreenshotParser().parseOcrDocument(playSubscriptionsScreen())

        assertEquals(1, result.size)
        assertEquals("Claude", result[0].merchant)
        assertEquals(1999.0, result[0].amount, 0.01)
        assertEquals("20 Oct 2026", result[0].nextDebitDate)
        assertEquals(Frequency.MONTHLY, result[0].frequency)
    }

    @Test
    fun testScreenshotParser_playStoreRupeeSymbolMisreadOrMissing() {
        for (payment in listOf("Next payment: 1,999.00 on 20 Oct 2026", "Next payment: ?1,999.00 on 20 Oct 2026")) {
            val doc = OcrDocument(
                listOf(
                    OcrTextBlock(
                        "",
                        null,
                        listOf(
                            ocr("Active", 45, 380, 160, 425),
                            ocr("Claude by Anthropic", 181, 478, 429, 510),
                            ocr("Claude Pro Subscription", 181, 514, 426, 542),
                            ocr(payment, 181, 545, 594, 572)
                        )
                    )
                )
            )
            val result = DefaultMandateScreenshotParser().parseOcrDocument(doc)

            assertEquals(payment, 1, result.size)
            assertEquals(1999.0, result[0].amount, 0.01)
        }
    }

    @Test
    fun testScreenshotParser_playStoreExpiredSectionIgnored() {
        val doc = OcrDocument(
            listOf(
                OcrTextBlock(
                    "",
                    null,
                    listOf(
                        ocr("Active", 45, 380, 160, 425),
                        ocr("Claude by Anthropic", 181, 478, 429, 510),
                        ocr("Claude Pro Subscription", 181, 514, 426, 542),
                        ocr("Next payment: ₹1,999.00 on 20 Oct 2026", 181, 545, 594, 572),
                        ocr("Expired", 45, 800, 200, 845),
                        ocr("Old App", 181, 900, 429, 932),
                        ocr("Old Plan", 181, 936, 426, 964),
                        ocr("Next payment: ₹199.00 on 01 Jan 2027", 181, 967, 594, 994)
                    )
                )
            )
        )
        val result = DefaultMandateScreenshotParser().parseOcrDocument(doc)

        assertEquals(listOf("Claude"), result.map { it.merchant })
    }

    @Test
    fun testScreenshotParser_linksGooglePayMandateToPlaySubscription() {
        val parser = DefaultMandateScreenshotParser()
        val merged = parser.mergeDuplicates(
            parser.parseOcrDocument(googlePayMandateScreen(), "Google Pay") +
                parser.parseOcrDocument(playSubscriptionsScreen())
        )

        assertEquals(1, merged.size)
        val m = merged[0]
        assertEquals("Claude", m.merchant)
        assertEquals(PaymentRail.UPI, m.rail)
        assertEquals(1999.0, m.amount, 0.01)
        assertEquals("20 Oct 2026", m.nextDebitDate)
        assertTrue(m.confidenceReason.none { it == '@' })
    }

    @Test
    fun testScreenshotParser_unlinkedGooglePayMandateAsksForName() {
        val result = DefaultMandateScreenshotParser().parseOcrDocument(googlePayMandateScreen(), "Google Pay")

        assertEquals(1, result.size)
        assertEquals("Google Play", result[0].merchant)
        assertTrue(result[0].confidenceReason.contains("Edit"))
    }
}
