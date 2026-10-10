package my.pinged.charts.model

import my.pinged.data.LocalDates
import my.pinged.data.dao.DayTotal
import my.pinged.ui.RINGGIT
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The grid's cell classifier (#81, Cell classifier; #64, #65, #68): what each
 * day of the month draws, from the MYR day totals, the captured set, the
 * history start and today.
 *
 * Every case reads one day out of [rhythmGrid], the function the screen draws
 * from. The totals are constructed, as #70 says the charts fixtures are.
 */
class GridCellTest {
    private val september = YearMonth.of(2026, 9)
    private fun sep(day: Int): LocalDate = september.atDay(day)

    /** A September seen from 5 October, its history start the 1st, every day captured. */
    private fun cell(
        day: LocalDate,
        totals: Map<LocalDate, Long> = emptyMap(),
        captured: Set<LocalDate> = (1..30).map(::sep).toSet(),
        historyStart: LocalDate? = sep(1),
        today: LocalDate = LocalDate.of(2026, 10, 5),
        otherCurrency: Map<LocalDate, Long> = emptyMap(),
    ): GridDay {
        val dayTotals = totals.map { (d, sen) -> DayTotal(LocalDates.of(d), RINGGIT, sen) } +
            otherCurrency.map { (d, sen) -> DayTotal(LocalDates.of(d), "SGD", sen) }
        val grid = rhythmGrid(september, today, historyStart, captured, dayTotals)
        return grid.days.single { it.date == day }
    }

    @Test fun aCapturedDayWithNoTransactionsIsNothingSpent() {
        assertEquals(CellState.NothingSpent, cell(sep(3)).state)
    }

    @Test fun aPastDayWithNoEvidenceAndNoTransactionsIsNotCaptured() {
        assertEquals(CellState.NotCaptured, cell(sep(3), captured = setOf(sep(2), sep(4))).state)
    }

    /**
     * Excluded rows are not in `dayTotals` (the counted predicate), so the day
     * has no total; their capture still made it captured (#64), and it reads
     * as nothing spent, the `Day` screen showing what moved (#69).
     */
    @Test fun aDayWithOnlyExcludedTransactionsIsNothingSpent() {
        assertEquals(CellState.NothingSpent, cell(sep(3), totals = emptyMap(), captured = setOf(sep(3))).state)
    }

    /**
     * Spending with no `capture_day` row (#64): the money is drawn, never
     * hatched. The read's captured set already holds the day, since a
     * capture-backed transaction is evidence; it draws spent even without.
     */
    @Test fun spendingWithNoCaptureDayRowIsSpent() {
        val noRow = cell(sep(3), totals = mapOf(sep(3) to 4_520L), captured = emptySet())
        assertEquals(4_520L, (noRow.state as CellState.Spent).netSen)
        val evidenceFromTheTxn = cell(sep(3), totals = mapOf(sep(3) to 4_520L), captured = setOf(sep(3)))
        assertEquals(4_520L, (evidenceFromTheTxn.state as CellState.Spent).netSen)
    }

    @Test fun aDayThatNetsNegativeIsNetNegative() {
        assertEquals(CellState.NetNegative(-26_400L), cell(sep(13), totals = mapOf(sep(13) to -26_400L)).state)
    }

    /** The ramp means magnitude spent, so a day that nets exactly zero spent nothing (#81). */
    @Test fun aDayThatNetsZeroWithTransactionsIsNothingSpent() {
        assertEquals(CellState.NothingSpent, cell(sep(3), totals = mapOf(sep(3) to 0L)).state)
    }

    /** Today's row may not exist yet (#64): never hatched, and marked. */
    @Test fun todayWithNothingIsNothingSpentAndMarked() {
        val today = cell(sep(10), today = sep(10), captured = (1..9).map(::sep).toSet())
        assertEquals(CellState.NothingSpent, today.state)
        assertEquals(true, today.today)
        assertEquals(true, today.opensDay)
        assertEquals(false, cell(sep(9), today = sep(10)).today)
    }

    @Test fun todayWithSpendingIsSpentAndMarked() {
        val today = cell(sep(10), today = sep(10), totals = mapOf(sep(10) to 1_200L), captured = emptySet())
        assertEquals(1_200L, (today.state as CellState.Spent).netSen)
        assertEquals(true, today.today)
    }

    @Test fun aDayBeforeTheHistoryStartIsBlank() {
        val before = cell(sep(3), historyStart = sep(7), captured = (7..30).map(::sep).toSet())
        assertEquals(CellState.Blank, before.state)
        assertEquals(false, before.opensDay)
    }

    /**
     * A hand-entered total before the history start still draws (#65). It is
     * never counted as captured or not; that is `notCapturedDays`', which
     * starts at the history start.
     */
    @Test fun aHandEnteredTotalBeforeTheHistoryStartStillDraws() {
        val spent = cell(sep(3), historyStart = sep(7), totals = mapOf(sep(3) to 8_000L), captured = emptySet())
        assertEquals(8_000L, (spent.state as CellState.Spent).netSen)
        val back = cell(sep(3), historyStart = sep(7), totals = mapOf(sep(3) to -8_000L), captured = emptySet())
        assertEquals(CellState.NetNegative(-8_000L), back.state)
    }

    @Test fun aDayAfterTodayIsBlankWhateverItHolds() {
        assertEquals(CellState.Blank, cell(sep(20), today = sep(10)).state)
        assertEquals(CellState.Blank, cell(sep(20), today = sep(10), totals = mapOf(sep(20) to 5_000L)).state)
        assertEquals(false, cell(sep(20), today = sep(10)).opensDay)
    }

    /** Only MYR is drawn (#81, Out of scope): another currency's total leaves the day as it was. */
    @Test fun anotherCurrencysTotalIsNotDrawn() {
        assertEquals(CellState.NothingSpent, cell(sep(3), otherCurrency = mapOf(sep(3) to 5_000L)).state)
    }

    @Test fun theFirstSitsUnderItsWeekdayMondayFirst() {
        // 1 September 2026 is a Tuesday; 1 June 2026 a Monday.
        assertEquals(1, rhythmGrid(september, sep(30), sep(1), emptySet(), emptyList()).leadingBlanks)
        val june = YearMonth.of(2026, 6)
        assertEquals(0, rhythmGrid(june, june.atDay(30), june.atDay(1), emptySet(), emptyList()).leadingBlanks)
        assertEquals(30, rhythmGrid(june, june.atDay(30), june.atDay(1), emptySet(), emptyList()).days.size)
    }
}
