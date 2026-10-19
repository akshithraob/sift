package com.leakproof.app

import com.leakproof.app.parser.DefaultMandateScreenshotParser
import com.leakproof.app.parser.OcrBoundingBox
import com.leakproof.app.parser.OcrDocument
import com.leakproof.app.parser.OcrTextBlock
import com.leakproof.app.parser.OcrTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Google Pay "Autopay" Live tab where some rows show "Subscription for ..." and a truncated handle. */
class GooglePayAutopayLiveTabTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrTextLine(text, OcrBoundingBox(l, t, r, b))

    private fun screen(avatars: Boolean) = OcrDocument(
        listOf(
            OcrTextBlock(
                "", null,
                listOfNotNull(
                    line("Autopay", 148, 98, 300, 138),
                    line("How to use Autopay", 90, 235, 340, 265),
                    line("Live", 95, 365, 145, 395),
                    line("Pending", 308, 365, 412, 395),
                    line("Completed", 528, 365, 671, 395),
                    line("GOOGLE", 147, 475, 275, 507),
                    line("Up to ₹399", 527, 475, 675, 507),
                    line("playstore@axisbank", 147, 518, 390, 548),
                    line("As presented", 511, 518, 675, 548),
                    line("Autopay on • 1 payment completed", 192, 580, 566, 610),
                    if (avatars) line("C", 68, 730, 94, 765) else null,
                    line("Subscription for ...", 147, 717, 400, 750),
                    line("Up to ₹1,500", 500, 717, 675, 750),
                    line("paytm-00000001@pt...", 147, 760, 408, 790),
                    line("As presented", 511, 760, 675, 790),
                    line("Autopay on • 39 payments completed", 192, 822, 594, 852),
                    if (avatars) line("N", 68, 972, 94, 1005) else null,
                    line("Subscription for ...", 147, 959, 400, 992),
                    line("Up to ₹499", 527, 959, 675, 992),
                    line("paytm-00000002@p...", 147, 1002, 408, 1032),
                    line("As presented", 511, 1002, 675, 1032),
                    line("Autopay on • 5 payments completed", 192, 1064, 580, 1094)
                )
            )
        )
    )

    @Test
    fun allThreeRowsAreFound() {
        for (avatars in listOf(false, true)) {
            val result = DefaultMandateScreenshotParser().parseOcrDocument(screen(avatars))
            assertEquals("avatars=$avatars: ${result.map { it.merchant to it.amount }}", 3, result.size)
            assertEquals(listOf(399.0, 499.0, 1500.0), result.map { it.maxAmount ?: it.amount }.sorted())
            assertTrue(result.none { it.confidenceReason.contains('@') })
        }
    }

    /**
     * The text ML Kit actually returned for a real Google Pay Autopay screenshot (handles replaced with fake
     * digits): blocks in scrambled order, the rupee sign dropped or read as "R", and the name cut to "..".
     */
    @Test
    fun realMlKitOutput_allThreeMandatesFoundAndAttributedToGooglePay() {
        val lines = listOf(
            line("12:49 C p' •'", 56, 28, 258, 49),
            line("How to use Autopay", 89, 237, 335, 263),
            line("N", 70, 976, 91, 1004),
            line("Autopay", 146, 100, 299, 139),
            line("Live", 97, 368, 144, 388),
            line("GOOGLE", 148, 476, 271, 501),
            line("Pending", 310, 366, 409, 392),
            line("playstore@axisbank", 148, 518, 386, 546),
            line("Subscription for ..", 148, 717, 392, 750),
            line("paytm-00000001@pt..", 155, 762, 403, 790),
            line("Completed", 531, 368, 668, 393),
            line("Autopay on 1 payment completed", 201, 582, 564, 608),
            line("Subscription for", 148, 962, 370, 991),
            line("paytm-00000002@p..", 155, 1004, 405, 1032),
            line("Up to 399", 528, 474, 673, 507),
            line(".l", 629, 28, 652, 47),
            line("As presented", 511, 522, 674, 546),
            line("Up to 1,500", 503, 718, 672, 749),
            line("Autopay on . 39 payments completed", 192, 827, 592, 848),
            line("As presented", 511, 760, 672, 788),
            line("Up to R499", 527, 958, 673, 990),
            line("As presented", 511, 1006, 674, 1030),
            line("Autopay on 5 payments completed", 204, 1068, 579, 1091)
        )
        // One block per line, as ML Kit produced
        val doc = OcrDocument(lines.map { OcrTextBlock(it.text, it.boundingBox, listOf(it)) })
        val result = DefaultMandateScreenshotParser().parseOcrDocument(doc)

        assertEquals(result.map { it.merchant to it.amount }.toString(), 3, result.size)
        assertEquals(listOf(399.0, 499.0, 1500.0), result.map { it.amount }.sorted())
        assertTrue(result.all { it.sourceApp == "Google Pay" })
        assertEquals("Google Play", result.first { it.amount == 399.0 }.merchant)
        assertTrue(result.filter { it.amount != 399.0 }.all { it.merchant == "Subscription via Paytm" })
        assertTrue(result.none { it.confidenceReason.contains('@') })
    }

    @Test
    fun rupeeSignMisreadsStillGiveTheLimit() {
        for (text in listOf("Up to ₹399", "Up to 399", "Up to R399", "Up to F399", "Up to T 399", "Up to Rs.399", "Max Amount: 399")) {
            val doc = OcrDocument(listOf(OcrTextBlock("", null, listOf(
                line("NETFLIX", 148, 476, 271, 501),
                line(text, 528, 474, 673, 507),
                line("As presented", 511, 522, 674, 546)
            ))))
            val result = DefaultMandateScreenshotParser().parseOcrDocument(doc)
            assertEquals(text, 1, result.size)
            assertEquals(text, 399.0, result[0].amount, 0.01)
        }
    }
}
