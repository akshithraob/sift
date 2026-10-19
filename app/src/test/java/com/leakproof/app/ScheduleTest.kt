package com.leakproof.app

import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Schedule
import com.leakproof.app.parser.RecurringKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleTest {

    private fun ms(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0, 0) }.timeInMillis

    // "Today" is 10 Jan 2027 in every test
    private val now = ms(2027, Calendar.JANUARY, 10)

    private fun sub(
        name: String,
        amount: Double,
        next: Long,
        frequency: String = "MONTHLY",
        kind: String = "SUBSCRIPTION",
        status: String = "ACTIVE",
        total: Int? = null,
        paid: Int? = null,
        end: Long? = null
    ) = SubscriptionEntity(
        merchant = name, amount = amount, frequency = frequency, nextDate = "x", nextDateEpochMs = next,
        rail = "UPI", source = "MANUAL", last4 = null, status = status, kind = kind,
        installmentsTotal = total, installmentsPaid = paid, endEpochMs = end
    )

    @Test
    fun monthlyItemChargesEveryMonthAndKeepsItsDayOfMonth() {
        val occ = Schedule.occurrences(sub("Netflix", 649.0, ms(2027, Calendar.JANUARY, 31)), now, ms(2027, Calendar.JUNE, 1))

        assertEquals(5, occ.size)
        val days = occ.map { Calendar.getInstance().apply { timeInMillis = it.dateMs }.get(Calendar.DAY_OF_MONTH) }
        assertEquals(listOf(31, 28, 28, 28, 28), days) // February has no 31st; stepping from the clamped date is acceptable
    }

    @Test
    fun yearlyItemAppearsOnceInTheYear() {
        val forecast = Schedule.forecast(listOf(sub("Domain", 1200.0, ms(2027, Calendar.MARCH, 5), "YEARLY")), now)

        assertEquals(12, forecast.size)
        assertEquals(listOf(Calendar.MARCH), forecast.filter { it.total > 0 }.map { it.month })
        assertEquals(1200.0, forecast.sumOf { it.total }, 0.0)
    }

    @Test
    fun quarterlyAndWeeklyItemsFollowTheirCycle() {
        val quarterly = Schedule.occurrences(sub("Q", 300.0, ms(2027, Calendar.JANUARY, 20), "QUARTERLY"), now, ms(2028, Calendar.JANUARY, 1))
        val weekly = Schedule.occurrences(sub("W", 50.0, ms(2027, Calendar.JANUARY, 12), "WEEKLY"), now, ms(2027, Calendar.FEBRUARY, 10))

        assertEquals(4, quarterly.size)
        assertEquals(5, weekly.size)
    }

    @Test
    fun emiStopsAfterItsRemainingInstalments() {
        val emi = sub("Phone EMI", 3000.0, ms(2027, Calendar.JANUARY, 15), kind = "EMI", total = 6, paid = 3)

        val occ = Schedule.occurrences(emi, now, ms(2028, Calendar.JANUARY, 1))

        assertEquals(3, occ.size)
    }

    @Test
    fun endDateStopsTheSchedule() {
        val occ = Schedule.occurrences(sub("Gym", 999.0, ms(2027, Calendar.JANUARY, 15), end = ms(2027, Calendar.MARCH, 20)), now, ms(2028, Calendar.JANUARY, 1))

        assertEquals(3, occ.size) // 15 Jan, 15 Feb, 15 Mar
    }

    @Test
    fun cancelledPausedAndOpenEndedItemsAreLeftOut() {
        val subs = listOf(
            sub("Cancelled", 100.0, ms(2027, Calendar.JANUARY, 15), status = "CANCELLED"),
            sub("Paused", 100.0, ms(2027, Calendar.JANUARY, 15), status = "PAUSED"),
            sub("Open ended", 5000.0, ms(2027, Calendar.JANUARY, 15), frequency = "AS_PRESENTED")
        )

        assertTrue(Schedule.occurrences(subs, now, ms(2028, Calendar.JANUARY, 1)).isEmpty())
    }

    @Test
    fun pastDatesAreSkippedAndCurrentMonthOnlyCountsWhatIsStillToCome() {
        val subs = listOf(
            sub("Already paid this month", 500.0, ms(2027, Calendar.JANUARY, 3)),
            sub("Still due this month", 700.0, ms(2027, Calendar.JANUARY, 25))
        )

        val january = Schedule.forecast(subs, now).first()

        assertEquals(700.0, january.total, 0.0)
        assertEquals(Calendar.JANUARY, january.month)
    }

    @Test
    fun forecastSplitsEachMonthByKind() {
        val subs = listOf(
            sub("Netflix", 649.0, ms(2027, Calendar.JANUARY, 20)),
            sub("Loan", 8000.0, ms(2027, Calendar.JANUARY, 21), kind = "EMI", total = 24, paid = 1),
            sub("SIP", 5000.0, ms(2027, Calendar.JANUARY, 22), kind = "SIP")
        )

        val january = Schedule.forecast(subs, now).first()

        assertEquals(13649.0, january.total, 0.0)
        assertEquals(649.0, january.byKind.getValue(RecurringKind.SUBSCRIPTION), 0.0)
        assertEquals(8000.0, january.byKind.getValue(RecurringKind.EMI), 0.0)
        assertEquals(5000.0, january.byKind.getValue(RecurringKind.SIP), 0.0)
    }

    @Test
    fun forecastRollsIntoTheNextYear() {
        val forecast = Schedule.forecast(emptyList(), ms(2027, Calendar.NOVEMBER, 10))

        assertEquals(listOf(2027, 2027, 2028, 2028, 2028), forecast.take(5).map { it.year })
        assertEquals(listOf(Calendar.NOVEMBER, Calendar.DECEMBER, Calendar.JANUARY), forecast.take(3).map { it.month })
    }

    @Test
    fun dueByDayGroupsChargesForTheCalendar() {
        val subs = listOf(
            sub("A", 100.0, ms(2027, Calendar.FEBRUARY, 5)),
            sub("B", 200.0, ms(2027, Calendar.FEBRUARY, 5)),
            sub("C", 300.0, ms(2027, Calendar.FEBRUARY, 27))
        )

        val byDay = Schedule.dueByDay(subs, 2027, Calendar.FEBRUARY)

        assertEquals(setOf(5, 27), byDay.keys)
        assertEquals(300.0, byDay.getValue(5).sumOf { it.subscription.amount }, 0.0)
    }
}
