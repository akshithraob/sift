package com.leakproof.app

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Budget
import com.leakproof.app.insights.HealthLevel
import com.leakproof.app.insights.HealthScore
import com.leakproof.app.insights.ImportDiff
import com.leakproof.app.insights.LeakFinder
import com.leakproof.app.insights.LeakKind
import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.Frequency
import com.leakproof.app.parser.ParsedMandateCandidate
import com.leakproof.app.parser.RawTransactionLine
import com.leakproof.app.parser.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {

    private val day = 24L * 3600 * 1000
    private val now = 1_790_000_000_000L

    private fun sub(
        id: Long,
        merchant: String,
        amount: Double = 100.0,
        rail: String = "UPI",
        frequency: String = "MONTHLY",
        lastCharged: Long? = null,
        status: String = "ACTIVE"
    ) = SubscriptionEntity(
        id = id, merchant = merchant, amount = amount, frequency = frequency, nextDate = "x",
        nextDateEpochMs = now, rail = rail, source = "SCREENSHOT", last4 = null, status = status,
        lastChargedAtMs = lastCharged
    )

    private fun statementCandidate(merchant: String, amount: Double) = ParsedMandateCandidate(
        id = "c-$merchant", merchant = merchant, amount = amount, frequency = Frequency.MONTHLY,
        source = SourceType.STATEMENT, sourceApp = "Bank Statement"
    )

    // --- Stale charge leak

    @Test
    fun staleCharge_flagsAMandateThatStoppedDebiting() {
        val leaks = LeakFinder.find(listOf(sub(1, "Gym", lastCharged = now - 120 * day)), Budget.evaluate(100.0, 0.0), now)

        assertEquals(listOf(LeakKind.STALE_CHARGE), leaks.map { it.kind })
        assertTrue(leaks.single().title.contains("Gym"))
    }

    @Test
    fun staleCharge_ignoresRecentLateOrUnknownCharges() {
        val subs = listOf(
            sub(1, "Recent", lastCharged = now - 20 * day),
            sub(2, "A bit late", lastCharged = now - 45 * day),
            sub(3, "Never seen in a statement", lastCharged = null),
            sub(4, "Yearly plan", frequency = "YEARLY", lastCharged = now - 300 * day),
            sub(5, "Cancelled long ago", status = "CANCELLED", lastCharged = now - 400 * day)
        )

        assertTrue(LeakFinder.find(subs, Budget.evaluate(100.0, 0.0), now).isEmpty())
    }

    // --- Health score

    @Test
    fun score_dropsWithEachLeakAndNamesTheLevel() {
        val healthy = sub(1, "Netflix")
        assertEquals(HealthScore(100, HealthLevel.HEALTHY), HealthScore.compute(listOf(healthy), emptyList()))

        val stale = LeakFinder.find(listOf(sub(1, "Gym", lastCharged = now - 120 * day)), Budget.evaluate(100.0, 0.0), now)
        assertEquals(94, HealthScore.compute(listOf(sub(1, "Gym")), stale)!!.score)

        val many = LeakFinder.find(
            listOf(sub(1, "A", lastCharged = now - 120 * day), sub(2, "B", lastCharged = now - 120 * day)),
            Budget.evaluate(30000.0, 1000.0), now
        )
        val score = HealthScore.compute(listOf(sub(1, "A"), sub(2, "B")), many)!!
        assertEquals(HealthLevel.NEEDS_A_LOOK, score.level)
        assertTrue(score.score in 60..84)
    }

    @Test
    fun score_isNeverBelowZeroAndAbsentWithoutActiveItems() {
        val subs = (1L..20L).map { sub(it, "S$it", lastCharged = now - 400 * day) }
        val leaks = LeakFinder.find(subs, Budget.evaluate(99999.0, 100.0), now)

        assertEquals(0, HealthScore.compute(subs, leaks)!!.score)
        assertNull(HealthScore.compute(emptyList(), emptyList()))
        assertNull(HealthScore.compute(listOf(sub(1, "Gone", status = "CANCELLED")), emptyList()))
    }

    // --- Statement coverage: authorised but never debited

    @Test
    fun statement_reportsMandatesItNeverDebited() {
        val existing = listOf(sub(1, "Netflix", 649.0), sub(2, "Zombie Gym", 999.0), sub(3, "Card sub", rail = "CARD"))

        val diff = ImportDiff.compare(existing, listOf(statementCandidate("Netflix", 649.0)), statementCoverageDays = 90)

        assertEquals(listOf(2L), diff.unseen.map { it.id }) // card items are not mandates, Netflix was seen
    }

    @Test
    fun statement_tooShortOrNotAStatementReportsNothing() {
        val existing = listOf(sub(1, "Zombie Gym"))
        val candidates = listOf(statementCandidate("Netflix", 649.0))

        assertTrue(ImportDiff.compare(existing, candidates, statementCoverageDays = 30).unseen.isEmpty())
        assertTrue(ImportDiff.compare(existing, candidates, statementCoverageDays = null).unseen.isEmpty())
        val screenshot = candidates.map { it.copy(source = SourceType.SCREENSHOT) }
        assertTrue(ImportDiff.compare(existing, screenshot, statementCoverageDays = 120).unseen.isEmpty())
    }

    @Test
    fun statement_longCycleItemsCannotBeJudgedFromAShortStatement() {
        val existing = listOf(sub(1, "Yearly", frequency = "YEARLY"), sub(2, "Quarterly", frequency = "QUARTERLY"), sub(3, "Monthly"))

        val diff = ImportDiff.compare(existing, listOf(statementCandidate("Other", 10.0)), statementCoverageDays = 100)

        assertEquals(listOf(3L), diff.unseen.map { it.id }) // 91-day cycle needs >109 days of statement
    }

    // --- Detector records when the last debit was

    @Test
    fun detector_recordsTheLastDebitDate() {
        fun tx(date: Long) = RawTransactionLine(
            date = "d", description = "UPI/NETFLIX/1/UPI", debitAmount = 649.0, isDebit = true, dateEpochMs = date
        )
        val detected = DefaultRecurringDetector().detectRecurring(listOf(tx(now - 60 * day), tx(now - 30 * day), tx(now - 3 * day)))

        assertNotNull(detected.single().lastDebitEpochMs)
        assertEquals(now - 3 * day, detected.single().lastDebitEpochMs)
    }
}
