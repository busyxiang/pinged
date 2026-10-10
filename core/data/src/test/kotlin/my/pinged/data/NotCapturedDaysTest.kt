package my.pinged.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The one month-trust rule (#81, Capture evidence): the days in
 * `[max(month start, history start), min(month end, today - 1)]` that have no
 * capture evidence. Charts, `Months` and the Ledger all grey on it, so a case
 * that is wrong here is wrong on three screens at once.
 *
 * Every expected value is counted off a calendar by hand, not recomputed.
 */
class NotCapturedDaysTest {

    @Test fun noHistoryStartCountsNothing() {
        assertEquals(
            "With no history start there is no record for a day to be missing " +
                "from: a fresh install, or the moment after Delete everything, " +
                "would grey every month it opened on",
            0,
            notCapturedDays(SEPTEMBER, historyStart = null, today = OCT_10, captured = emptySet()),
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
            notCapturedDays(SEPTEMBER, SEPTEMBER.atDay(7), OCT_10, captured),
        )
    }

    @Test fun aHistoryStartAfterTheMonthCountsNothing() {
        assertEquals(
            "A month wholly before the history start is outside the record, not " +
                "a month of not-captured days",
            0,
            notCapturedDays(AUGUST, SEPTEMBER.atDay(7), OCT_10, emptySet()),
        )
    }

    @Test fun todayIsNeverCounted() {
        val october = YearMonth.of(2026, 10)
        assertEquals(
            "The 1st of the month, with no evidence yet for today, counted today " +
                "as not captured. That is the Ledger's morning greying",
            0,
            notCapturedDays(october, AUGUST.atDay(1), today = october.atDay(1), captured = emptySet()),
        )
    }

    @Test fun todayIsNotCountedLaterInTheMonthEither() {
        // 1..9 October captured, the 10th is today and has no evidence yet.
        val captured = (1..9).map { OCT_10.withDayOfMonth(it) }.toSet()
        assertEquals(
            "Today, with no evidence yet, was counted against a month whose every " +
                "earlier day is captured",
            0,
            notCapturedDays(YearMonth.of(2026, 10), AUGUST.atDay(1), OCT_10, captured),
        )
    }

    @Test fun aFutureMonthCountsNothing() {
        assertEquals(
            "Days that have not happened yet were counted as not captured",
            0,
            notCapturedDays(YearMonth.of(2026, 11), AUGUST.atDay(1), OCT_10, emptySet()),
        )
    }

    @Test fun oneMissingDayMakesTheMonthUntrusted() {
        val captured = (1..30).map { SEPTEMBER.atDay(it) }.toSet() - SEPTEMBER.atDay(17)
        assertEquals(
            "A finished month with one day nobody was watching was not counted. " +
                "One day is enough; there is no threshold",
            1,
            notCapturedDays(SEPTEMBER, AUGUST.atDay(1), OCT_10, captured),
        )
    }

    @Test fun evidenceOutsideTheMonthDoesNotCover() {
        // Captured every day of August and October, nothing in September.
        val captured = (1..31).map { AUGUST.atDay(it) }.toSet() +
            (1..9).map { OCT_10.withDayOfMonth(it) }
        assertEquals(
            "A neighbouring month's evidence covered this one",
            30,
            notCapturedDays(SEPTEMBER, AUGUST.atDay(1), OCT_10, captured),
        )
    }

    /**
     * One day by the same rule (#69's `Day` header): a past day on or after
     * the history start with no evidence. Today, a day before the start, and
     * any day with no start at all are not "not captured".
     */
    @Test fun oneDayIsNotCapturedByTheSameRule() {
        val start = SEPTEMBER.atDay(7)
        val captured = setOf(SEPTEMBER.atDay(9))
        assertEquals(
            "Only the 8th is a past day from the start with no evidence: the 6th " +
                "is before the record, the 9th has evidence, and today is never not captured",
            listOf(false, true, false, false),
            listOf(SEPTEMBER.atDay(6), SEPTEMBER.atDay(8), SEPTEMBER.atDay(9), OCT_10)
                .map { notCaptured(it, start, OCT_10, captured) },
        )
        assertEquals(
            "With no history start no day can be missing from the record",
            false,
            notCaptured(SEPTEMBER.atDay(8), historyStart = null, today = OCT_10, captured = emptySet()),
        )
    }

    private companion object {
        val AUGUST: YearMonth = YearMonth.of(2026, 8)
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCT_10: LocalDate = LocalDate.of(2026, 10, 10)
    }
}
