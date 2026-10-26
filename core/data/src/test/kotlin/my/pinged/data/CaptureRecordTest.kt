package my.pinged.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The capture record's rules (#81, Capture evidence; #305): which days are
 * captured, which month is untrusted or comparable, and the collecting count.
 * Charts, `Months`, the Ledger and the Day header all ask it, so a case that
 * is wrong here is wrong on four screens at once.
 *
 * Every expected value is counted off a calendar by hand, not recomputed.
 * What the evidence is, is `CaptureRecordReadTest`'s, against the database.
 */
class CaptureRecordTest {

    /** A record whose span is the whole of 2026, so nothing here throws by accident. */
    private fun record(
        historyStart: LocalDate?,
        captured: Set<LocalDate>,
        today: LocalDate = OCT_10,
    ) = CaptureRecord.of(historyStart, today, LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 12, 31), captured)

    private fun notCaptured(month: YearMonth, historyStart: LocalDate?, captured: Set<LocalDate>, today: LocalDate = OCT_10) =
        record(historyStart, captured, today).month(month).notCaptured

    // ---- month ----------------------------------------------------------

    @Test fun noHistoryStartCountsNothing() {
        assertEquals(
            "With no history start there is no record for a day to be missing " +
                "from: a fresh install, or the moment after Delete everything, " +
                "would grey every month it opened on",
            0,
            notCaptured(SEPTEMBER, historyStart = null, captured = emptySet()),
        )
    }

    @Test fun aHistoryStartMidMonthCountsOnlyTheDaysFromIt() {
        // 7..30 September is 24 days; 20 of them captured leaves 4.
        val captured = (7..30).map { SEPTEMBER.atDay(it) }.toSet() -
            setOf(SEPTEMBER.atDay(8), SEPTEMBER.atDay(15), SEPTEMBER.atDay(22), SEPTEMBER.atDay(30))

        assertEquals(
            "The days before the history start were counted as not captured, so " +
                "the month Pinged was installed in greys for the days before it " +
                "existed. Or a missing day after the start was not counted",
            4,
            notCaptured(SEPTEMBER, SEPTEMBER.atDay(7), captured),
        )
    }

    @Test fun aHistoryStartAfterTheMonthCountsNothing() {
        assertEquals(
            "A month wholly before the history start is outside the record, not " +
                "a month of not-captured days",
            0,
            notCaptured(AUGUST, SEPTEMBER.atDay(7), emptySet()),
        )
    }

    @Test fun todayIsNeverCounted() {
        val october = YearMonth.of(2026, 10)
        assertEquals(
            "The 1st of the month, with no evidence yet for today, counted today " +
                "as not captured. That is the Ledger's morning greying",
            0,
            notCaptured(october, AUGUST.atDay(1), emptySet(), today = october.atDay(1)),
        )
    }

    @Test fun todayIsNotCountedLaterInTheMonthEither() {
        // 1..9 October captured, the 10th is today and has no evidence yet.
        val captured = (1..9).map { OCT_10.withDayOfMonth(it) }.toSet()
        assertEquals(
            "Today, with no evidence yet, was counted against a month whose every " +
                "earlier day is captured",
            0,
            notCaptured(YearMonth.of(2026, 10), AUGUST.atDay(1), captured),
        )
    }

    @Test fun aFutureMonthCountsNothing() {
        assertEquals(
            "Days that have not happened yet were counted as not captured",
            0,
            notCaptured(YearMonth.of(2026, 11), AUGUST.atDay(1), emptySet()),
        )
    }

    @Test fun oneMissingDayMakesTheMonthUntrusted() {
        val captured = (1..30).map { SEPTEMBER.atDay(it) }.toSet() - SEPTEMBER.atDay(17)
        val month = record(AUGUST.atDay(1), captured).month(SEPTEMBER)

        assertEquals(
            "A finished month with one day nobody was watching was not counted. " +
                "One day is enough; there is no threshold",
            1,
            month.notCaptured,
        )
        assertTrue(month.untrusted)
    }

    @Test fun evidenceOutsideTheMonthDoesNotCover() {
        // Captured every day of August and October, nothing in September.
        val captured = (1..31).map { AUGUST.atDay(it) }.toSet() +
            (1..9).map { OCT_10.withDayOfMonth(it) }
        assertEquals(
            "A neighbouring month's evidence covered this one",
            30,
            notCaptured(SEPTEMBER, AUGUST.atDay(1), captured),
        )
    }

    // ---- comparable -----------------------------------------------------

    private val wholeSeptember = (1..30).map { SEPTEMBER.atDay(it) }.toSet()

    @Test fun anEndedWholeMonthOnOrAfterTheHistoryStartIsComparable() {
        assertTrue(record(SEPTEMBER.atDay(1), wholeSeptember).month(SEPTEMBER).comparable)
    }

    @Test fun aMonthWithANotCapturedDayIsNotComparable() {
        val month = record(SEPTEMBER.atDay(1), wholeSeptember - SEPTEMBER.atDay(12)).month(SEPTEMBER)
        assertFalse("An untrusted month was compared", month.comparable)
    }

    @Test fun aRunningMonthIsNotComparableYet() {
        val captured = (1..9).map { OCT_10.withDayOfMonth(it) }.toSet()
        val month = record(OCT_10.withDayOfMonth(1), captured).month(YearMonth.of(2026, 10))
        assertFalse("A whole-so-far month still running was compared", month.comparable)
        assertEquals(0, month.notCaptured)
    }

    @Test fun theMonthTheHistoryStartFallsPartWayThroughIsNotComparable() {
        val captured = (2..30).map { SEPTEMBER.atDay(it) }.toSet()
        val month = record(SEPTEMBER.atDay(2), captured).month(SEPTEMBER)
        assertEquals("Not untrusted: its 1st is outside the record", 0, month.notCaptured)
        assertFalse("A month the record does not wholly cover was compared", month.comparable)
    }

    @Test fun noHistoryStartMakesNothingComparable() {
        assertFalse(record(null, emptySet()).month(SEPTEMBER).comparable)
    }

    // ---- day ------------------------------------------------------------

    /** One day by the same rule (#69's `Day` header). */
    @Test fun aDayIsOneOfFourThings() {
        val start = SEPTEMBER.atDay(7)
        val record = record(start, captured = setOf(SEPTEMBER.atDay(9)))

        assertEquals(
            "The 6th is before the record, the 8th is a past day with no evidence, " +
                "the 9th has evidence, and today is never captured or not",
            listOf(
                DayStatus.OUTSIDE_RECORD,
                DayStatus.NOT_CAPTURED,
                DayStatus.CAPTURED,
                DayStatus.NOT_ENDED,
                DayStatus.NOT_ENDED,
            ),
            listOf(SEPTEMBER.atDay(6), SEPTEMBER.atDay(8), SEPTEMBER.atDay(9), OCT_10, OCT_10.plusDays(1))
                .map(record::day),
        )
    }

    @Test fun todayWithEvidenceIsStillNotEnded() {
        assertEquals(
            DayStatus.NOT_ENDED,
            record(SEPTEMBER.atDay(7), setOf(OCT_10)).day(OCT_10),
        )
    }

    @Test fun withNoHistoryStartNoDayIsCapturedOrNot() {
        assertEquals(DayStatus.OUTSIDE_RECORD, record(null, emptySet()).day(SEPTEMBER.atDay(8)))
    }

    // ---- collecting -----------------------------------------------------

    private fun daysBefore(day: LocalDate, n: Int) = (1..n).map { day.minusDays(it.toLong()) }.toSet()

    @Test fun installDayIsZeroAndShut() {
        val gate = record(OCT_10, emptySet()).collecting

        assertEquals(0, gate.days)
        assertFalse(gate.open)
    }

    @Test fun thirteenCapturedDaysAreStillShut() {
        val gate = record(OCT_10.minusDays(13), daysBefore(OCT_10, 13)).collecting

        assertEquals(13, gate.days)
        assertFalse(gate.open)
    }

    @Test fun fourteenCapturedDaysOpenIt() {
        val gate = record(OCT_10.minusDays(14), daysBefore(OCT_10, 14)).collecting

        assertEquals(14, gate.days)
        assertTrue(gate.open)
    }

    @Test fun fourteenCalendarDaysWithFewerCapturedStayShut() {
        // Thirty days since the history start, a listener dead for twenty of them.
        val gate = record(OCT_10.minusDays(30), daysBefore(OCT_10, 10)).collecting

        assertEquals(10, gate.days)
        assertFalse(gate.open)
    }

    @Test fun aRestoredHistoryOverFourteenOpensOnTheFirstDay() {
        // An old phone's year, restored this morning: nothing captured here yet.
        val start = LocalDate.of(2026, 1, 1)
        val gate = record(start, (0L until 200L).map { start.plusDays(it) }.toSet()).collecting

        assertEquals(200, gate.days)
        assertTrue(gate.open)
    }

    @Test fun collectingNeverCountsToday() {
        val gate = record(OCT_10.minusDays(13), daysBefore(OCT_10, 13) + OCT_10).collecting

        assertEquals("Today has evidence and still is not a captured day until it ends", 13, gate.days)
        assertFalse(gate.open)
    }

    @Test fun nothingBeforeTheHistoryStartOrAfterTodayIsCounted() {
        val start = OCT_10.minusDays(5)
        val captured = daysBefore(OCT_10, 5) + daysBefore(start, 20) + OCT_10.plusDays(3)
        assertEquals(5, record(start, captured).collecting.days)
    }

    @Test fun noHistoryStartIsZero() {
        assertEquals(0, record(null, daysBefore(OCT_10, 30)).collecting.days)
    }

    // ---- the span -------------------------------------------------------

    private val septemberOnly = CaptureRecord.of(
        SEPTEMBER.atDay(1), OCT_10, SEPTEMBER.atDay(1)..SEPTEMBER.atEndOfMonth(), wholeSeptember,
    )

    @Test fun aDayOutsideTheSpanThrowsRatherThanAnsweringNotCaptured() {
        assertThrows(IllegalArgumentException::class.java) { septemberOnly.day(AUGUST.atDay(31)) }
        assertThrows(IllegalArgumentException::class.java) { septemberOnly.day(OCT_10) }
    }

    @Test fun aMonthNotWhollyInTheSpanThrows() {
        assertThrows(IllegalArgumentException::class.java) { septemberOnly.month(AUGUST) }
        assertThrows(IllegalArgumentException::class.java) { septemberOnly.month(YearMonth.of(2026, 10)) }
    }

    @Test fun theSpanItselfAnswers() {
        assertEquals(DayStatus.CAPTURED, septemberOnly.day(SEPTEMBER.atDay(30)))
        assertEquals(0, septemberOnly.month(SEPTEMBER).notCaptured)
    }

    private companion object {
        val AUGUST: YearMonth = YearMonth.of(2026, 8)
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCT_10: LocalDate = LocalDate.of(2026, 10, 10)
    }
}
