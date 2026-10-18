package com.leakproof.app

import com.leakproof.app.backup.BackupCodec
import com.leakproof.app.backup.CsvExport
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Budget
import com.leakproof.app.insights.EmiMath
import com.leakproof.app.insights.KindBreakdown
import com.leakproof.app.insights.LeakFinder
import com.leakproof.app.insights.LeakKind
import com.leakproof.app.insights.OverlapDetector
import com.leakproof.app.parser.DefaultRecurringDetector
import com.leakproof.app.parser.KindClassifier
import com.leakproof.app.parser.RawTransactionLine
import com.leakproof.app.parser.RecurringKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmiTest {

    private val day = 24L * 3600 * 1000
    private val month = (30.4375 * day).toLong()
    private val now = 1_790_000_000_000L

    private fun item(
        merchant: String,
        amount: Double,
        kind: String = "EMI",
        total: Int? = null,
        paid: Int? = null,
        next: Long = now + 5 * day,
        end: Long? = null,
        rate: Double? = null,
        outstanding: Double? = null,
        id: Long = 0
    ) = SubscriptionEntity(
        id = id, merchant = merchant, amount = amount, frequency = "MONTHLY", nextDate = "x",
        nextDateEpochMs = next, rail = "NACH", source = "STATEMENT", last4 = null, status = "ACTIVE",
        kind = kind, installmentsTotal = total, installmentsPaid = paid, endEpochMs = end,
        interestRatePct = rate, outstanding = outstanding
    )

    // --- Classifier

    @Test
    fun classifier_recognisesLoansSipsAndInsurance() {
        val expected = mapOf(
            "Bajaj Finance" to RecurringKind.EMI,
            "ACH D-BAJAJ FINANCE LTD-482109" to RecurringKind.EMI,
            "HDFC LTD HOME LOAN" to RecurringKind.EMI,
            "Amazon No Cost EMI" to RecurringKind.EMI,
            "Zerodha Coin (SIP)" to RecurringKind.SIP,
            "Groww SIP" to RecurringKind.SIP,
            "Nippon India Mutual Fund" to RecurringKind.SIP,
            "HDFC Life Insurance" to RecurringKind.INSURANCE,
            "LIC Premium" to RecurringKind.INSURANCE,
            "Star Health" to RecurringKind.INSURANCE,
            "Spotify Premium" to RecurringKind.SUBSCRIPTION,
            "Netflix" to RecurringKind.SUBSCRIPTION,
            "Cult.fit" to RecurringKind.SUBSCRIPTION,
            "Credit card annual fee" to RecurringKind.SUBSCRIPTION,
            "" to RecurringKind.SUBSCRIPTION
        )
        for ((text, kind) in expected) assertEquals(text, kind, KindClassifier.classify(text))
    }

    @Test
    fun detector_marksEmiAndSipFromStatementNarrations() {
        fun tx(date: Long, desc: String, amount: Double) = RawTransactionLine(
            date = "d", description = desc, debitAmount = amount, isDebit = true, dateEpochMs = date
        )
        val detected = DefaultRecurringDetector().detectRecurring(
            listOf(
                tx(now - 60 * day, "ACH D-BAJAJ FINANCE LTD-482109", 8000.0),
                tx(now - 30 * day, "ACH D-BAJAJ FINANCE LTD-482109", 8000.0),
                tx(now, "ACH D-BAJAJ FINANCE LTD-482109", 8000.0),
                tx(now - 60 * day, "ACH D-ZERODHA BROKING LTD-1", 5000.0),
                tx(now - 30 * day, "ACH D-ZERODHA BROKING LTD-1", 5000.0)
            )
        )

        assertEquals(RecurringKind.EMI, detected.first { it.merchant.contains("Bajaj", true) }.kind)
        assertEquals(RecurringKind.SIP, detected.first { it.merchant.contains("Zerodha", true) }.kind)
    }

    // --- Instalments and payoff

    @Test
    fun emi_remainingAndPayoffFromInstalmentCount() {
        val emi = item("Bajaj Finance", 8000.0, total = 24, paid = 7)
        val status = EmiMath.status(emi)

        assertEquals(17, status.remaining)
        assertEquals(136000.0, status.remainingPayable!!, 0.01)
        assertEquals(7f / 24f, status.progress!!, 0.001f)
        assertEquals((emi.nextDateEpochMs + (16 * month)).toDouble(), status.payoffMs!!.toDouble(), 2.0 * day)
    }

    @Test
    fun emi_remainingFromAnEndDate() {
        val emi = item("Car loan", 12000.0, end = now + 5 * day + (11.5 * month).toLong())

        assertEquals(12, EmiMath.remainingInstallments(emi))
        assertEquals(emi.endEpochMs, EmiMath.payoffMs(emi))
    }

    @Test
    fun emi_unknownTermGivesNoPayoffAndNoRemaining() {
        val emi = item("Mystery EMI", 3000.0)

        assertNull(EmiMath.remainingInstallments(emi))
        assertNull(EmiMath.payoffMs(emi))
        assertNull(EmiMath.debtFreeMs(listOf(emi), now))
    }

    @Test
    fun emi_finishedLoanHasNothingRemaining() {
        assertEquals(0, EmiMath.remainingInstallments(item("Done", 1000.0, total = 12, paid = 12)))
        assertEquals(0, EmiMath.remainingInstallments(item("Overpaid", 1000.0, total = 12, paid = 14)))
    }

    @Test
    fun emi_freedUpTimelineAndDebtFreeDate() {
        val a = item("Phone EMI", 3000.0, total = 6, paid = 2, id = 1)   // ends first
        val b = item("Home loan", 20000.0, total = 120, paid = 20, id = 2)
        val sip = item("Zerodha Coin", 5000.0, kind = "SIP", id = 3)   // not an EMI

        val freed = EmiMath.freedUp(listOf(a, b, sip), now)

        assertEquals(listOf("Phone EMI", "Home loan"), freed.map { it.merchant })
        assertEquals(20000.0, freed[0].monthlyAfter, 0.01) // after the phone EMI, only the home loan remains
        assertEquals(0.0, freed[1].monthlyAfter, 0.01)
        assertEquals(EmiMath.payoffMs(b), EmiMath.debtFreeMs(listOf(a, b, sip), now))
    }

    @Test
    fun emi_burdenIsTheShareOfRecurringOutgo() {
        val burden = EmiMath.burden(listOf(item("Loan", 3000.0), item("Netflix", 1000.0, kind = "SUBSCRIPTION")))

        assertEquals(0.75, burden!!, 0.001)
        assertNull(EmiMath.burden(emptyList()))
    }

    // --- Prepayment what-if

    @Test
    fun prepayment_halvingTheBalanceCutsAboutSixMonthsOffATwelveMonthLoan() {
        // 1,00,000 at 12% for 12 months has an instalment of about 8,884.88
        val loan = item("Personal loan", 8884.88, total = 12, paid = 0, rate = 12.0)

        val result = EmiMath.prepayment(loan, 50000.0)!!

        assertEquals(100000.0, result.principalUsed, 5.0)
        assertEquals(6, result.monthsSaved)
        assertTrue(result.interestSaved in 3200.0..3400.0)
    }

    @Test
    fun prepayment_zeroInterestStillShortensTheTerm() {
        val loan = item("Interest-free EMI", 1000.0, total = 10, paid = 0, rate = 0.0)

        val result = EmiMath.prepayment(loan, 3000.0)!!

        assertEquals(3, result.monthsSaved)
        assertEquals(0.0, result.interestSaved, 0.01)
    }

    @Test
    fun prepayment_needsTheRateAndAnExtraAmount() {
        assertNull(EmiMath.prepayment(item("No rate", 1000.0, total = 10, paid = 0), 500.0))
        assertNull(EmiMath.prepayment(item("Rate", 1000.0, total = 10, paid = 0, rate = 12.0), 0.0))
    }

    @Test
    fun prepayment_clearingTheWholeBalanceEndsTheLoan() {
        val loan = item("Loan", 1000.0, total = 10, paid = 0, rate = 12.0, outstanding = 5000.0)

        assertEquals(10, EmiMath.prepayment(loan, 5000.0)!!.monthsSaved)
    }

    // --- Insights that must ignore or include EMIs correctly

    @Test
    fun twoEmisFromOneLenderAreNotADuplicateSubscription() {
        val subs = listOf(item("Bajaj Finance", 3000.0, id = 1), item("Bajaj Finance", 5000.0, id = 2))

        assertTrue(OverlapDetector.detect(subs).isEmpty())
    }

    @Test
    fun highInterestLoanIsFlaggedAsALeak() {
        val subs = listOf(item("Quick Cash", 2000.0, rate = 36.0), item("Home loan", 20000.0, rate = 8.5))

        val leaks = LeakFinder.find(subs, Budget.evaluate(22000.0, 0.0), now)

        assertEquals(listOf(LeakKind.HIGH_INTEREST), leaks.map { it.kind })
        assertTrue(leaks.single().title.contains("Quick Cash"))
    }

    @Test
    fun breakdownSplitsMonthlyOutgoByPurpose() {
        val subs = listOf(
            item("Netflix", 649.0, kind = "SUBSCRIPTION"),
            item("Loan", 8000.0),
            item("Zerodha Coin", 5000.0, kind = "SIP"),
            item("LIC", 12000.0, kind = "INSURANCE").copy(frequency = "YEARLY")
        )

        val split = KindBreakdown.monthlyByKind(subs)

        assertEquals(649.0, split.getValue(RecurringKind.SUBSCRIPTION), 0.01)
        assertEquals(8000.0, split.getValue(RecurringKind.EMI), 0.01)
        assertEquals(5000.0, split.getValue(RecurringKind.SIP), 0.01)
        assertEquals(1000.0, split.getValue(RecurringKind.INSURANCE), 0.01)
    }

    // --- Backup and export carry the new fields

    @Test
    fun backupKeepsEmiDetails() {
        val original = listOf(item("Home loan", 20000.0, total = 120, paid = 20, end = now + 900 * day, rate = 8.5, outstanding = 1_800_000.0))

        val decoded = BackupCodec.decode(BackupCodec.encode(original, 0.0))

        assertEquals(original, decoded.subscriptions)
    }

    @Test
    fun oldBackupsWithoutEmiFieldsStillRestoreAsSubscriptions() {
        val oldJson = """{"format":"leakproof-backup","version":2,"createdAt":1,"monthlyBudget":0,"subscriptions":[
            {"merchant":"Netflix","amount":649,"frequency":"MONTHLY","nextDate":"x","nextDateEpochMs":1,"rail":"UPI","source":"MANUAL","status":"ACTIVE","userConfirmed":true}]}"""

        val restored = BackupCodec.decode(oldJson.toByteArray()).subscriptions.single()

        assertEquals("SUBSCRIPTION", restored.kind)
        assertNull(restored.installmentsTotal)
        assertNotNull(restored)
    }

    @Test
    fun csvListsTypeAndInstalments() {
        val csv = CsvExport.toCsv(listOf(item("Home loan", 20000.0, total = 120, paid = 20, rate = 8.5)))

        assertTrue(csv.lines()[0].endsWith("Type,Instalments paid,Instalments total,Ends,Interest rate (%)"))
        assertTrue(csv.contains(",EMI,20,120,,8.50"))
    }
}
