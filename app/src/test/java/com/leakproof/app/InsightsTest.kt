package com.leakproof.app

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Budget
import com.leakproof.app.insights.BudgetLevel
import com.leakproof.app.insights.ImportDiff
import com.leakproof.app.insights.MandateRiskAnalyzer
import com.leakproof.app.insights.OverlapDetector
import com.leakproof.app.insights.OverlapKind
import com.leakproof.app.insights.RiskKind
import com.leakproof.app.insights.Trials
import com.leakproof.app.insights.monthlyCost
import com.leakproof.app.parser.Frequency
import com.leakproof.app.parser.ParsedMandateCandidate
import com.leakproof.app.parser.PaymentRail
import com.leakproof.app.parser.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {

    private val day = 24L * 3600 * 1000

    private fun sub(
        id: Long,
        merchant: String,
        amount: Double,
        frequency: String = "MONTHLY",
        status: String = "ACTIVE",
        rail: String = "UPI",
        source: String = "SCREENSHOT",
        maxAmount: Double? = null,
        trialEnd: Long? = null,
        nextDate: Long = 0L
    ) = SubscriptionEntity(
        id = id, merchant = merchant, amount = amount, frequency = frequency, nextDate = "x",
        nextDateEpochMs = nextDate, rail = rail, source = source, last4 = null, status = status,
        maxAmount = maxAmount, trialEndEpochMs = trialEnd
    )

    private fun candidate(
        merchant: String,
        amount: Double,
        sourceApp: String = "Google Pay",
        nextMs: Long? = null,
        source: SourceType = SourceType.SCREENSHOT
    ) = ParsedMandateCandidate(
        id = "c-$merchant", merchant = merchant, amount = amount, frequency = Frequency.MONTHLY,
        nextDebitDateEpochMs = nextMs, rail = PaymentRail.UPI, source = source, sourceApp = sourceApp
    )

    // --- Overlaps

    @Test
    fun overlap_flagsTwoMusicServices() {
        val insights = OverlapDetector.detect(
            listOf(sub(1, "Spotify Premium", 119.0), sub(2, "Apple Music", 99.0), sub(3, "Netflix", 649.0))
        )

        assertEquals(1, insights.size)
        val music = insights.single()
        assertEquals(OverlapKind.OVERLAP, music.kind)
        assertEquals("Music streaming", music.label)
        assertEquals(218.0, music.monthlyTotal, 0.01)
        assertEquals("You pay for 2 music streaming services", music.title)
    }

    @Test
    fun overlap_ignoresCancelledAndPausedSubscriptions() {
        val insights = OverlapDetector.detect(
            listOf(sub(1, "Spotify", 119.0), sub(2, "Apple Music", 99.0, status = "CANCELLED"))
        )

        assertTrue(insights.isEmpty())
    }

    @Test
    fun overlap_flagsExactDuplicatesOnceNotAsAnOverlapToo() {
        val insights = OverlapDetector.detect(listOf(sub(1, "Netflix India", 649.0), sub(2, "Netflix", 649.0)))

        assertEquals(1, insights.size)
        assertEquals(OverlapKind.DUPLICATE, insights.single().kind)
        assertEquals(1298.0, insights.single().monthlyTotal, 0.01)
    }

    @Test
    fun overlap_normalisesYearlyToMonthly() {
        assertEquals(125.0, monthlyCost(1500.0, "YEARLY"), 0.01)
        assertEquals(100.0, monthlyCost(300.0, "QUARTERLY"), 0.01)
        val insights = OverlapDetector.detect(
            listOf(sub(1, "Google One", 1300.0, "YEARLY"), sub(2, "iCloud", 75.0))
        )
        assertEquals(1, insights.size)
        assertEquals(75.0 + 1300.0 / 12, insights.single().monthlyTotal, 0.01)
    }

    @Test
    fun overlap_unrelatedServicesAreNotFlagged() {
        val insights = OverlapDetector.detect(
            listOf(sub(1, "Netflix", 649.0), sub(2, "Spotify", 119.0), sub(3, "BESCOM Electricity", 1850.0))
        )
        assertTrue(insights.isEmpty())
    }

    // --- Mandate risk

    @Test
    fun risk_flagsLimitFarAboveUsualCharge() {
        val risks = MandateRiskAnalyzer.analyze(listOf(sub(1, "Hungama", 119.0, maxAmount = 10000.0)))

        assertEquals(1, risks.size)
        assertEquals(RiskKind.HIGH_LIMIT, risks.single().kind)
    }

    @Test
    fun risk_doesNotFlagLimitEqualToChargeOrSmallHeadroom() {
        val risks = MandateRiskAnalyzer.analyze(
            listOf(
                sub(1, "Claude", 1999.0, maxAmount = 1999.0),
                sub(2, "Netflix", 649.0, maxAmount = 1000.0),
                sub(3, "Spotify", 119.0, maxAmount = 500.0)
            )
        )
        assertTrue(risks.isEmpty())
    }

    @Test
    fun risk_flagsOpenEndedBillingWithBigLimit() {
        val risks = MandateRiskAnalyzer.analyze(
            listOf(sub(1, "Utility Co", 1500.0, frequency = "AS_PRESENTED", maxAmount = 5000.0))
        )
        assertEquals(RiskKind.OPEN_ENDED, risks.single().kind)
    }

    // --- Re-import diff

    @Test
    fun diff_sameMerchantWithNewPriceIsAChangeNotANewEntry() {
        val diff = ImportDiff.compare(
            listOf(sub(1, "Netflix India", 649.0)),
            listOf(candidate("Netflix", 799.0))
        )

        assertTrue(diff.newCandidates.isEmpty())
        val match = diff.matches.single()
        assertEquals(1L, match.existing.id)
        assertTrue(match.amountChanged)
    }

    @Test
    fun diff_reportsNewMatchedAndMissing() {
        val existing = listOf(
            sub(1, "Netflix", 649.0, nextDate = 1000L),
            sub(2, "Hungama", 99.0),
            sub(3, "Manual Gym", 1000.0, source = "MANUAL", rail = "OTHER")
        )
        val diff = ImportDiff.compare(
            existing,
            listOf(candidate("Netflix", 649.0, nextMs = 1000L), candidate("Spotify", 119.0))
        )

        assertEquals(listOf("Spotify"), diff.newCandidates.map { it.merchant })
        assertFalse(diff.matches.single().hasChanges)
        // Manual entries are never reported as missing
        assertEquals(listOf(2L), diff.missing.map { it.id })
    }

    @Test
    fun diff_playOnlyImportNeverReportsMissing() {
        val diff = ImportDiff.compare(
            listOf(sub(1, "Netflix", 649.0), sub(2, "Hungama", 99.0)),
            listOf(candidate("Claude", 1999.0, sourceApp = "Google Play"))
        )
        assertTrue(diff.missing.isEmpty())
    }

    @Test
    fun diff_statementImportNeverReportsMissing() {
        val diff = ImportDiff.compare(
            listOf(sub(1, "Hungama", 99.0)),
            listOf(candidate("Netflix", 649.0, sourceApp = "Bank Statement", source = SourceType.STATEMENT))
        )
        assertTrue(diff.missing.isEmpty())
    }

    @Test
    fun diff_twoLookAlikesPickTheClosestAmountAndUseEachOnce() {
        val existing = listOf(sub(1, "Netflix", 649.0), sub(2, "Netflix", 199.0))
        val diff = ImportDiff.compare(existing, listOf(candidate("Netflix", 199.0), candidate("Netflix", 649.0)))

        assertTrue(diff.newCandidates.isEmpty())
        assertEquals(setOf(1L, 2L), diff.matches.map { it.existing.id }.toSet())
        assertEquals(2L, diff.matches.first { it.candidate.amount == 199.0 }.existing.id)
    }

    @Test
    fun diff_noMatchForUnrelatedName() {
        assertNull(ImportDiff.findMatch(listOf(sub(1, "Netflix", 649.0)), candidate("Spotify", 119.0)))
        assertNotNull(ImportDiff.findMatch(listOf(sub(1, "Netflix", 649.0)), candidate("Netflix India", 649.0)))
    }

    // --- Budget

    @Test
    fun budget_levels() {
        assertEquals(BudgetLevel.NONE, Budget.evaluate(5000.0, 0.0).level)
        assertEquals(BudgetLevel.OK, Budget.evaluate(1000.0, 5000.0).level)
        assertEquals(BudgetLevel.NEAR, Budget.evaluate(4300.0, 5000.0).level)
        assertEquals(BudgetLevel.OVER, Budget.evaluate(5001.0, 5000.0).level)
        assertEquals(-100.0, Budget.evaluate(5100.0, 5000.0).remaining, 0.01)
    }

    // --- Trials

    @Test
    fun trials_daysUntilAndOrdering() {
        val now = System.currentTimeMillis()
        assertEquals(0, Trials.daysUntil(now, now))
        assertEquals(1, Trials.daysUntil(now + day, now))
        assertEquals(7, Trials.daysUntil(now + 7 * day, now))
        assertEquals(-1, Trials.daysUntil(now - day, now))

        val trials = Trials.activeTrials(
            listOf(
                sub(1, "A", 100.0, trialEnd = now + 5 * day),
                sub(2, "B", 100.0, trialEnd = now + 1 * day),
                sub(3, "Ended", 100.0, trialEnd = now - 3 * day),
                sub(4, "Cancelled", 100.0, status = "CANCELLED", trialEnd = now + day),
                sub(5, "NoTrial", 100.0)
            ),
            now
        )
        assertEquals(listOf("B", "A"), trials.map { it.merchant })
        assertEquals("Trial ends tomorrow", Trials.label(1))
        assertEquals("Trial ends in 5 days", Trials.label(5))
    }

    // --- LeakFinder

    @Test
    fun leaks_collectsEverythingThatNeedsAttentionMostUrgentFirst() {
        val now = System.currentTimeMillis()
        val subs = listOf(
            sub(1, "Spotify", 119.0),
            sub(2, "Apple Music", 99.0),
            sub(3, "Hungama", 99.0, maxAmount = 10000.0),
            sub(4, "Claude", 1999.0, trialEnd = now + day)
        )
        val budget = Budget.evaluate(2316.0, 2000.0)

        val leaks = com.leakproof.app.insights.LeakFinder.find(subs, budget, now)

        assertEquals(
            listOf(
                com.leakproof.app.insights.LeakKind.OVER_BUDGET,
                com.leakproof.app.insights.LeakKind.TRIAL,
                com.leakproof.app.insights.LeakKind.OVERLAP,
                com.leakproof.app.insights.LeakKind.MANDATE_LIMIT
            ),
            leaks.map { it.kind }
        )
        // High severity (over budget, trial ending tomorrow) comes before medium
        assertTrue(leaks.take(2).all { it.severity == com.leakproof.app.insights.LeakSeverity.HIGH })
        assertTrue(leaks.first { it.kind == com.leakproof.app.insights.LeakKind.TRIAL }.title.contains("Claude"))
    }

    @Test
    fun leaks_nothingToReportWhenAllIsWell() {
        val subs = listOf(sub(1, "Netflix", 649.0), sub(2, "BESCOM", 1850.0, rail = "SI"))

        assertTrue(com.leakproof.app.insights.LeakFinder.find(subs, Budget.evaluate(2499.0, 10000.0)).isEmpty())
    }

    @Test
    fun leaks_nearBudgetIsAMediumWarningAndFarTrialsAreIgnored() {
        val now = System.currentTimeMillis()
        val subs = listOf(sub(1, "Claude", 1999.0, trialEnd = now + 30 * day))

        val leaks = com.leakproof.app.insights.LeakFinder.find(subs, Budget.evaluate(1999.0, 2200.0), now)

        assertEquals(listOf(com.leakproof.app.insights.LeakKind.NEAR_BUDGET), leaks.map { it.kind })
    }
}
