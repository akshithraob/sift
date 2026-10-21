package com.leakproof.app

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Budget
import com.leakproof.app.insights.LeakFinder
import com.leakproof.app.insights.LeakKind
import com.leakproof.app.insights.OverlapDetector
import com.leakproof.app.insights.OverlapKind
import com.leakproof.app.insights.RenewalRadar
import com.leakproof.app.insights.WidgetSummary
import com.leakproof.app.insights.YearReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarAndOverlapTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_790_000_000_000L // a fixed "today"

    private fun sub(
        id: Long, name: String, amount: Double, frequency: String = "MONTHLY", inDays: Int = 10,
        kind: String = "SUBSCRIPTION", status: String = "ACTIVE"
    ) = SubscriptionEntity(
        id = id, merchant = name, amount = amount, frequency = frequency, nextDate = "", nextDateEpochMs = now + inDays * day,
        rail = "UPI", source = "MANUAL", last4 = null, status = status, kind = kind
    )

    // ---- stronger overlap detection

    @Test
    fun bundleThatAlreadyIncludesOtherServicesIsFlagged() {
        val subs = listOf(sub(1, "Apple One Premier", 365.0), sub(2, "Apple Music Individual", 119.0), sub(3, "iCloud+ 200GB", 219.0), sub(4, "Netflix", 649.0))
        val found = OverlapDetector.detect(subs)
        val bundle = found.single { it.kind == OverlapKind.BUNDLE }
        assertEquals("Apple One Premier", bundle.bundleName)
        assertEquals(setOf("Apple Music Individual", "iCloud+ 200GB"), bundle.merchants.toSet())
        assertEquals(338.0, bundle.monthlyTotal, 0.001)
        assertTrue(bundle.title.startsWith("Apple One Premier already includes"))
        // The covered pair must not also be reported as a separate overlap
        assertEquals(1, found.size)
    }

    @Test
    fun differentPlansOfTheSameServiceAreFlagged() {
        val subs = listOf(sub(1, "Spotify Premium", 119.0), sub(2, "Spotify Family", 179.0), sub(3, "Notion", 830.0))
        val found = OverlapDetector.detect(subs)
        val same = found.single { it.kind == OverlapKind.SAME_SERVICE }
        assertEquals(setOf(1L, 2L), same.subscriptionIds.toSet())
        assertEquals(298.0, same.monthlyTotal, 0.001)
    }

    @Test
    fun aSlipInTheNameStillCountsAsADuplicate() {
        val subs = listOf(sub(1, "Netflix", 649.0), sub(2, "Netflx", 649.0))
        val dup = OverlapDetector.detect(subs).single()
        assertEquals(OverlapKind.DUPLICATE, dup.kind)
        assertEquals(2, dup.subscriptionIds.size)
    }

    @Test
    fun services_whoseNamesDifferButShareABrandFamily() {
        val subs = listOf(sub(1, "Amazon Prime", 299.0), sub(2, "Prime Video", 149.0))
        val found = OverlapDetector.detect(subs)
        assertTrue(found.any { it.kind == OverlapKind.BUNDLE || it.kind == OverlapKind.SAME_SERVICE })
    }

    @Test
    fun newGroupsCatchGamingVpnAndDesignOverlaps() {
        val gaming = OverlapDetector.detect(listOf(sub(1, "PlayStation Plus Deluxe", 849.0), sub(2, "Nintendo Switch Online", 270.0)))
        assertEquals("Gaming", gaming.single().label)
        val vpn = OverlapDetector.detect(listOf(sub(1, "NordVPN", 300.0), sub(2, "Windscribe Pro", 200.0)))
        assertEquals("VPN", vpn.single().label)
        val design = OverlapDetector.detect(listOf(sub(1, "Canva Pro", 499.0), sub(2, "Adobe Creative Cloud", 1675.0)))
        assertEquals("Design & creative", design.single().label)
    }

    @Test
    fun placeholderNamesAndVeryDifferentAmountsAreNotDuplicates() {
        val placeholders = listOf(sub(1, "Subscription via Paytm", 1500.0), sub(2, "Subscription via Paytm", 499.0))
        assertTrue(OverlapDetector.detect(placeholders).isEmpty())
        val differentPrices = listOf(sub(1, "Gym Pass", 500.0), sub(2, "Gym Pass", 4000.0))
        assertTrue(OverlapDetector.detect(differentPrices).isEmpty())
        // The same service at the same price, e.g. mandates made in two UPI apps, is still caught
        val real = listOf(sub(1, "Netflix", 649.0), sub(2, "Netflix", 649.0))
        assertEquals(OverlapKind.DUPLICATE, OverlapDetector.detect(real).single().kind)
    }

    @Test
    fun unrelatedServicesAndEmisAreLeftAlone() {
        val subs = listOf(
            sub(1, "Netflix", 649.0), sub(2, "Google One", 130.0), sub(3, "Audible", 199.0),
            sub(4, "Home Loan EMI", 30000.0, kind = "EMI"), sub(5, "Car Loan EMI", 18900.0, kind = "EMI")
        )
        assertTrue(OverlapDetector.detect(subs).isEmpty())
    }

    @Test
    fun leakFinderExplainsTheBundleSaving() {
        val subs = listOf(sub(1, "YouTube Premium", 149.0), sub(2, "YouTube Music", 99.0))
        val leak = LeakFinder.find(subs, Budget.evaluate(248.0, 0.0), now).single { it.kind == LeakKind.BUNDLE }
        assertTrue(leak.body.contains("separately"))
        assertEquals(listOf(1L, 2L), leak.subscriptionIds)
    }

    // ---- annual-renewal radar

    @Test
    fun radarListsYearlyPaymentsInTheNextNinetyDays() {
        val subs = listOf(
            sub(1, "Amazon Prime", 1499.0, "YEARLY", inDays = 20),
            sub(2, "Term insurance", 14260.0, "YEARLY", inDays = 75),
            sub(3, "Domain name", 900.0, "YEARLY", inDays = 200),
            sub(4, "Netflix", 649.0, "MONTHLY", inDays = 5),
            sub(5, "Old gym", 12000.0, "YEARLY", inDays = 10, status = "CANCELLED")
        )
        val items = RenewalRadar.upcoming(subs, now)
        assertEquals(listOf("Amazon Prime", "Term insurance"), items.map { it.subscription.merchant })
        assertEquals(20, items[0].daysAway)
        assertEquals(15759.0, RenewalRadar.total(items), 0.001)
    }

    @Test
    fun radarIsEmptyWithoutYearlyPayments() {
        assertTrue(RenewalRadar.upcoming(listOf(sub(1, "Netflix", 649.0)), now).isEmpty())
    }

    // ---- year in review

    @Test
    fun yearInReviewTotalsWhatIsActiveAndWhatWasCancelled() {
        val subs = listOf(
            sub(1, "Netflix", 649.0), sub(2, "Spotify", 119.0), sub(3, "Insurance", 12000.0, "YEARLY", kind = "INSURANCE"),
            sub(4, "Old gym", 1000.0, status = "CANCELLED"), sub(5, "Old news", 100.0, status = "CANCELLED")
        )
        val review = YearReview.compute(subs, emptyList(), now)
        assertEquals(3, review.activeCount)
        assertEquals((649.0 + 119.0) * 12 + 12000.0, review.yearlyTotal, 0.01)
        assertEquals("Netflix", review.priciest?.merchant)
        assertEquals(2, review.cancelledCount)
        assertEquals(1100.0 * 12, review.cancelledYearlySaved, 0.01)
        assertNotNull(review.busiestMonth)
    }

    @Test
    fun yearInReviewHandlesNothingTracked() {
        val review = YearReview.compute(emptyList(), emptyList(), now)
        assertEquals(0, review.activeCount)
        assertNull(review.priciest)
        assertNull(review.busiestMonth)
    }

    // ---- widget summary

    @Test
    fun widgetSummaryPicksTheNextRenewalAndTheWeek() {
        val subs = listOf(sub(1, "Netflix", 649.0, inDays = 3), sub(2, "Spotify", 119.0, inDays = 6), sub(3, "Gym", 999.0, inDays = 20))
        val w = WidgetSummary.build(subs, now)
        assertEquals("Netflix", w.next?.subscription?.merchant)
        assertEquals(3, w.nextDays)
        assertEquals(2, w.dueThisWeekCount)
        assertEquals(768.0, w.dueThisWeekTotal, 0.001)
        assertEquals(649.0 + 119.0 + 999.0, w.monthlyTotal, 0.001)
    }

    @Test
    fun widgetSummaryWithNothingTracked() {
        val w = WidgetSummary.build(emptyList(), now)
        assertEquals(0, w.activeCount)
        assertNull(w.next)
    }
}

class WidgetAndShareTextTest {
    private val day = 24L * 3600 * 1000
    private val now = 1_790_000_000_000L

    private fun sub(name: String, amount: Double, inDays: Int) = SubscriptionEntity(
        id = 1, merchant = name, amount = amount, frequency = "MONTHLY", nextDate = "", nextDateEpochMs = now + inDays * day,
        rail = "UPI", source = "MANUAL", last4 = null, status = "ACTIVE"
    )

    @Test
    fun widgetHidesEverythingUntilTheUserOptsIn() {
        val summary = WidgetSummary.build(listOf(sub("Netflix", 649.0, 2)), now)
        val hidden = com.leakproof.app.widget.LeakproofWidgetProvider.describe(summary, showDetails = false, unavailable = false)
        val text = "${hidden.main} ${hidden.line1} ${hidden.line2}"
        assertTrue(text, !text.contains("Netflix") && !text.contains("649"))
    }

    @Test
    fun widgetShowsTheNextRenewalWhenAllowed() {
        val summary = WidgetSummary.build(listOf(sub("Netflix", 649.0, 2)), now)
        val shown = com.leakproof.app.widget.LeakproofWidgetProvider.describe(summary, showDetails = true, unavailable = false)
        assertTrue(shown.main.contains("649"))
        assertTrue(shown.line1.contains("Netflix") && shown.line1.contains("in 2 days"))
    }

    @Test
    fun widgetWithNothingTrackedInvitesTheFirstOne() {
        val shown = com.leakproof.app.widget.LeakproofWidgetProvider.describe(WidgetSummary.build(emptyList(), now), true, false)
        assertEquals("Nothing tracked yet", shown.main)
    }

    @Test
    fun sharedSummaryHasTotalsOnly() {
        val subs = listOf(sub("Netflix", 649.0, 2))
        val text = com.leakproof.app.ui.screens.summaryText(YearReview.compute(subs, emptyList(), now))
        assertTrue(text.contains("7,788"))
        assertTrue(!text.contains("Netflix"))
    }
}
