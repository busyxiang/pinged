package my.pinged.charts.model

import my.pinged.charts.chartsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The collecting gate (#81, Collecting; #66): captured days from the history
 * start to yesterday, open at fourteen.
 */
class CollectingTest {
    private val today = LocalDate.of(2026, 9, 20)

    /** Captured days ending yesterday, as many as [n]. */
    private fun daysBefore(day: LocalDate, n: Int) = (1..n).map { day.minusDays(it.toLong()) }.toSet()

    @Test fun installDayIsZeroOfFourteenAndShut() {
        val gate = collecting(historyStart = today, today = today, captured = emptySet())

        assertEquals(0, gate.days)
        assertFalse(gate.open)
        assertEquals("COLLECTING · 0 OF 14 DAYS", gate.label)
        assertEquals("Category bars and top merchants appear after two weeks of capture.", gate.line)
    }

    @Test fun thirteenCapturedDaysAreStillShut() {
        val gate = collecting(today.minusDays(13), today, daysBefore(today, 13))

        assertEquals(13, gate.days)
        assertFalse(gate.open)
        assertEquals("COLLECTING · 13 OF 14 DAYS", gate.label)
    }

    @Test fun fourteenCapturedDaysOpenIt() {
        val gate = collecting(today.minusDays(14), today, daysBefore(today, 14))

        assertEquals(14, gate.days)
        assertTrue(gate.open)
    }

    @Test fun fourteenCalendarDaysWithFewerCapturedStayShut() {
        // Thirty days since the history start, a listener dead for twenty of them.
        val start = today.minusDays(30)
        val gate = collecting(start, today, daysBefore(today, 10))

        assertEquals(10, gate.days)
        assertFalse(gate.open)
    }

    @Test fun aRestoredHistoryOverFourteenOpensOnTheFirstDay() {
        // An old phone's year, restored this morning: nothing captured here yet.
        val start = LocalDate.of(2025, 9, 1)
        val restored = (0L until 200L).map { start.plusDays(it) }.toSet()
        val gate = collecting(start, today, restored)

        assertEquals(200, gate.days)
        assertTrue(gate.open)
    }

    @Test fun todayIsNeverCounted() {
        val captured = daysBefore(today, 13) + today
        val gate = collecting(today.minusDays(13), today, captured)

        assertEquals("Today has evidence and still is not a captured day until it ends", 13, gate.days)
        assertFalse(gate.open)
    }

    @Test fun nothingBeforeTheHistoryStartOrAfterTodayIsCounted() {
        val start = today.minusDays(5)
        val captured = daysBefore(today, 5) + daysBefore(start, 20) + today.plusDays(3)
        assertEquals(5, collecting(start, today, captured).days)
    }

    @Test fun theScreenTakesTheGateFromTheHistoryNotFromTheSelectedMonth() {
        // August, selected on 20 September: the month's own evidence is all
        // of August, but the history since the start is what counts.
        val august = YearMonth.of(2026, 8)
        val read = chartsRead(
            month = august,
            today = today,
            historyStart = august.atDay(1),
            captured = (1..31).map { august.atDay(it) }.toSet(),
            monthTotals = emptyList(),
            monthSplit = emptyList(),
            previousMonthTotals = emptyList(),
            notInTotal = emptyList(),
            capturedSinceStart = daysBefore(today, 9),
        )

        assertEquals(Collecting(9), monthView(read).collecting)
    }

    @Test fun noHistoryStartIsZero() {
        assertEquals(0, collecting(null, today, daysBefore(today, 30)).days)
    }
}
