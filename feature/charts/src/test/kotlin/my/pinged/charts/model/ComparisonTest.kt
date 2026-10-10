package my.pinged.charts.model

import my.pinged.charts.chartsRead
import my.pinged.data.dao.CurrencyTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The comparison line under the hero (#81, Comparison line; #67): shown only
 * when the selected month and the month directly before it are both
 * comparable, and then worded from the two signed nets.
 *
 * Every case goes through [line], which also asserts that the line and
 * `MAY BE INCOMPLETE` are never drawn together.
 */
class ComparisonTest {
    private val october = YearMonth.of(2026, 10)
    private val september = YearMonth.of(2026, 9)

    /** Every day from the 1st of September to the day before [today]. */
    private fun capturedUpTo(today: LocalDate): Set<LocalDate> =
        generateSequence(september.atDay(1)) { it.plusDays(1) }.takeWhile { it < today }.toSet()

    private fun line(
        netSen: Long = 290_000L,
        previousNetSen: Long = 276_010L,
        historyStart: LocalDate? = september.atDay(1),
        today: LocalDate = LocalDate.of(2026, 11, 5),
        captured: Set<LocalDate> = capturedUpTo(today),
        month: YearMonth = october,
    ): String? {
        val view = monthView(
            chartsRead(
                month = month,
                today = today,
                historyStart = historyStart,
                captured = captured,
                monthTotals = listOf(CurrencyTotal("MYR", netSen)),
                monthSplit = emptyList(),
                previousMonthTotals = listOf(CurrencyTotal("MYR", previousNetSen)),
                notInTotal = emptyList(),
            ),
        )
        assertEquals(
            "The comparison and MAY BE INCOMPLETE were drawn together",
            false,
            view.comparison != null && view.hero.incomplete != null,
        )
        return view.comparison
    }

    @Test fun aHigherMonthIsAboveTheMonthBefore() {
        assertEquals("RM139.90 ABOVE SEPTEMBER'S RM2,760.10", line(netSen = 290_000L, previousNetSen = 276_010L))
    }

    @Test fun aLowerMonthIsBelowWithTheDifferenceUnsigned() {
        assertEquals("RM87.20 BELOW SEPTEMBER'S RM2,760.10", line(netSen = 267_290L, previousNetSen = 276_010L))
    }

    @Test fun equalNetsToTheSenAreTheSame() {
        assertEquals("SAME AS SEPTEMBER'S RM2,760.10", line(netSen = 276_010L, previousNetSen = 276_010L))
    }

    @Test fun oneSenApartIsNotTheSame() {
        assertEquals("RM0.01 ABOVE SEPTEMBER'S RM2,760.10", line(netSen = 276_011L, previousNetSen = 276_010L))
    }

    @Test fun aNetNegativePreviousMonthIsComparedAndPrintedSigned() {
        assertEquals("RM50.00 ABOVE SEPTEMBER'S −RM20.00", line(netSen = 3_000L, previousNetSen = -2_000L))
    }

    @Test fun aNetNegativeSelectedMonthIsComparedOnItsSignedNet() {
        assertEquals("RM70.00 BELOW SEPTEMBER'S RM50.00", line(netSen = -2_000L, previousNetSen = 5_000L))
    }

    @Test fun aRunningMonthIsNotCompared() {
        // The 31st itself: the month has not ended until it has.
        assertNull(line(today = LocalDate.of(2026, 10, 31)))
    }

    @Test fun theMonthIsComparedOnTheDayAfterItEnds() {
        assertNotNull(line(today = LocalDate.of(2026, 11, 1)))
    }

    @Test fun anUntrustedSelectedMonthIsNotCompared() {
        val today = LocalDate.of(2026, 11, 5)
        assertNull(line(today = today, captured = capturedUpTo(today) - LocalDate.of(2026, 10, 14)))
    }

    @Test fun anUntrustedPreviousMonthIsNotCompared() {
        val today = LocalDate.of(2026, 11, 5)
        assertNull(line(today = today, captured = capturedUpTo(today) - LocalDate.of(2026, 9, 30)))
    }

    @Test fun aHistoryStartPartWayThroughThePreviousMonthHoldsTheLineBack() {
        val start = LocalDate.of(2026, 9, 2)
        val today = LocalDate.of(2026, 11, 5)
        // Both months are untrusted-free: September's count starts at the 2nd.
        assertNull(line(historyStart = start, today = today, captured = capturedUpTo(today) - september.atDay(1)))
    }

    @Test fun aHistoryStartOnThePreviousMonthsFirstDayIsShown() {
        assertNotNull(line(historyStart = september.atDay(1)))
    }

    @Test fun theFirstMonthOfHistoryHasNothingBeforeItToCompare() {
        val today = LocalDate.of(2026, 11, 5)
        assertNull(line(historyStart = october.atDay(1), today = today, captured = capturedUpTo(today)))
    }

    @Test fun aHistoryStartPartWayThroughTheSelectedMonthHoldsTheLineBack() {
        assertNull(line(historyStart = LocalDate.of(2026, 10, 2)))
    }

    @Test fun noHistoryStartIsNeverCompared() {
        assertNull(line(historyStart = null, captured = emptySet()))
    }
}
