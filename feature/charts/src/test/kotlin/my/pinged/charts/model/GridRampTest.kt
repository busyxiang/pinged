package my.pinged.charts.model

import my.pinged.data.LocalDates
import my.pinged.data.dao.DayTotal
import my.pinged.ui.RINGGIT
import my.pinged.ui.theme.GridRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The grid's ramp and legend (#81, Grid ramp; #68): as many steps as there are
 * distinct positive amounts, up to four, the darkest k of `GridRamp`, bucketed
 * by rank; and the legend's ringgit range.
 *
 * Steps are `GridRamp` indexes, palest first: 3 is the darkest. The expected
 * steps are worked by hand from the prototype's rule, quoted in #88.
 */
class GridRampTest {
    private val september = YearMonth.of(2026, 9)

    /**
     * September, complete and every day captured, with [sen] on the 1st, 2nd,
     * ...; the history start is in August, so there is no footer.
     */
    private fun grid(vararg sen: Long): RhythmGrid {
        val totals = sen.mapIndexed { i, s -> DayTotal(LocalDates.of(september.atDay(i + 1)), RINGGIT, s) }
        return rhythmGrid(
            september,
            today = LocalDate.of(2026, 10, 5),
            historyStart = LocalDate.of(2026, 8, 1),
            captured = (1..30).map(september::atDay).toSet(),
            dayTotals = totals,
        )
    }

    /** The step each of the first [n] days drew in, or null for a day not spent. */
    private fun RhythmGrid.steps(n: Int): List<Int?> = days.take(n).map { (it.state as? CellState.Spent)?.step }

    @Test fun theStepsAreGridRamps() {
        assertEquals(GridRamp.size, GRID_STEPS)
    }

    @Test fun oneDistinctAmountTakesTheDarkestStepAndSaysEveryDay() {
        val g = grid(1_200, 1_200, 1_200, 1_200, 1_200, 1_200, 1_200, 1_200)
        assertEquals(List(8) { 3 }, g.steps(8))
        assertEquals(listOf(3), g.legend.steps)
        assertEquals("RM12 EVERY DAY YOU SPENT", g.legend.range)
    }

    @Test fun twoDistinctAmountsTakeTheDarkestTwoLargestDarkest() {
        val g = grid(4_500, 1_250, 4_500)
        assertEquals(listOf(3, 2, 3), g.steps(3))
        assertEquals(listOf(2, 3), g.legend.steps)
        assertEquals("RM13 → RM45 A DAY", g.legend.range)
    }

    /** The prototype's case: under quartiles the RM230 day never reached the darkest step. */
    @Test fun threeDistinctAmountsTakeTheDarkestThreeLargestDarkest() {
        val g = grid(1_250, 23_000, 4_500)
        assertEquals(listOf(1, 3, 2), g.steps(3))
        assertEquals(listOf(1, 2, 3), g.legend.steps)
        assertEquals("RM13 → RM230 A DAY", g.legend.range)
    }

    @Test fun exactlyFourDistinctAmountsTakeOneStepEach() {
        val g = grid(4_000, 1_000, 3_000, 2_000, 1_000)
        assertEquals(listOf(3, 0, 2, 1, 0), g.steps(5))
        assertEquals(listOf(0, 1, 2, 3), g.legend.steps)
        assertEquals("RM10 → RM40 A DAY", g.legend.range)
    }

    /**
     * Eight spending days, seven distinct amounts. Ranked among the distinct
     * amounts (d = 7), RM40 is r = 3: floor(3 × 4 / 7) = 1, and both RM40
     * days take it. Ranked by position among the days, the second RM40 would
     * sit across the boundary at 4 of 8; equal amounts always share a step.
     */
    @Test fun moreThanFourRankTheDistinctAmountsSoATieShares() {
        val g = grid(1_000, 2_000, 3_000, 4_000, 4_000, 6_000, 7_000, 8_000)
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 2, 3), g.steps(8))
        assertEquals(listOf(0, 1, 2, 3), g.legend.steps)
        assertEquals("RM10 → RM80 A DAY", g.legend.range)
    }

    /**
     * #68's property: the largest day is always the darkest. Ranked by
     * position among the 12 days, RM5's first occurrence is 4 and
     * floor(4 × 4 / 12) = 1, which put all eight RM5 days on the second
     * palest step. Among the d = 5 distinct amounts RM5 is r = 4: step 3.
     */
    @Test fun aTieAtTheTopIsStillTheDarkest() {
        val g = grid(100, 200, 300, 400, 500, 500, 500, 500, 500, 500, 500, 500)
        assertEquals(listOf(0, 0, 1, 2) + List(8) { 3 }, g.steps(12))
        assertEquals(listOf(0, 1, 2, 3), g.legend.steps)
    }

    /**
     * Eight RM1 days, then RM2 to RM5. By position RM2 is 8 of 12, step 2,
     * and RM3 to RM5 share the darkest; among the five distinct amounts each
     * of RM3, RM4 and RM5 has its own step.
     */
    @Test fun aTieAtTheBottomDoesNotCrowdTheRestIntoTheTop() {
        val g = grid(100, 100, 100, 100, 100, 100, 100, 100, 200, 300, 400, 500)
        assertEquals(List(8) { 0 } + listOf(0, 1, 2, 3), g.steps(12))
    }

    /**
     * Ranking, not scaling: one day ten times the rest takes the darkest step
     * and leaves the others spread across the ramp rather than washed pale.
     */
    @Test fun anOutlierDoesNotWashTheRestOut() {
        val g = grid(1_000, 1_100, 1_200, 1_300, 1_400, 1_500, 1_600, 16_000)
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), g.steps(8))
        assertEquals("RM10 → RM160 A DAY", g.legend.range)
    }

    /** Zero and negative days are never in the ramp set: here one amount is left, not three. */
    @Test fun zeroAndNegativeDaysAreKeptOutOfTheRamp() {
        val g = grid(500, 0, 500, -2_000, 0)
        assertEquals(listOf(3, null, 3, null, null), g.steps(5))
        assertEquals(listOf(3), g.legend.steps)
        assertEquals("RM5 EVERY DAY YOU SPENT", g.legend.range)
    }

    @Test fun moreCameBackIsListedOnlyWhenADayNetsNegative() {
        assertEquals(true, grid(500, -2_000).legend.moreCameBack)
        assertEquals(false, grid(500, 0).legend.moreCameBack)
    }

    @Test fun aMonthWithNoSpendingHasNoRamp() {
        val g = grid(0, -500)
        assertEquals(emptyList<Int>(), g.legend.steps)
        assertNull(g.legend.range)
    }

    /**
     * The range rounds to whole ringgit, as the artboard prints `RM33 → RM237`
     * for RM32.50 and RM236.50. Two amounts that round to one figure print in
     * sen, so the legend never reads as a range from a number to itself.
     */
    @Test fun theRangeRoundsHalfUpToWholeRinggitUnlessThatCollapsesIt() {
        assertEquals("RM33 → RM237 A DAY", grid(3_250, 23_650).legend.range)
        assertEquals("RM1,234 → RM2,000 A DAY", grid(123_400, 200_000).legend.range)
        assertEquals("RM12.10 → RM12.40 A DAY", grid(1_210, 1_240).legend.range)
    }

    @Test fun theHistoryStartsMonthSaysNothingBefore() {
        val g = rhythmGrid(
            september,
            today = LocalDate.of(2026, 10, 5),
            historyStart = september.atDay(7),
            captured = (7..30).map(september::atDay).toSet(),
            dayTotals = emptyList(),
        )
        assertEquals("Nothing before 7 September 2026", g.footer)
    }

    @Test fun anyOtherMonthHasNoFooter() {
        assertNull(grid(500).footer)
        val october = rhythmGrid(
            YearMonth.of(2026, 10),
            today = LocalDate.of(2026, 10, 5),
            historyStart = september.atDay(7),
            captured = emptySet(),
            dayTotals = emptyList(),
        )
        assertNull(october.footer)
    }
}
