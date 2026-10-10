package my.pinged.charts.model

import my.pinged.charts.chartsRead
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.ringgit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * The drawn bars add up to the MYR hero (#81, Bars: "This property is
 * tested"). Every category is drawn once, in its own bar or in `Everything
 * else`, and nothing else is drawn.
 *
 * Each fixture's hero is written out by hand from the prototype's scenarios,
 * not summed here, and goes through `monthView` as the screen's would.
 */
class BarsSumTest {
    private val september = YearMonth.of(2026, 9)

    private class Fixture(val name: String, val heroSen: Long, val totals: List<CategoryTotal>)

    private fun myr(vararg sen: Long) = sen.mapIndexed { i, s -> CategoryTotal(i + 1L, "MYR", s) }

    private val fixtures = listOf(
        // The artboard's September.
        Fixture("artboard", 284_730L, myr(84_250L, 62_180L, 46_560L, 38_840L, 31_000L, 21_900L)),
        // The prototype's busy month: all fourteen seeded categories, Uncategorized (id 2) second.
        Fixture(
            "busy",
            341_560L,
            myr(74_210L, 54_000L, 49_830L, 41_000L, 35_280L, 30_150L, 14_800L, 13_390L, 9_600L, 6_800L, 5_000L, 3_000L, 2_500L, 2_000L),
        ),
        // The laptop refund: the month nets −RM12.40.
        Fixture("refund", -1_240L, myr(148_660L, -149_900L)),
        // A remainder, a negative category and Uncategorized at once, with another currency beside them.
        Fixture(
            "everything",
            62_000L,
            myr(20_000L, 15_000L, 10_000L, 8_000L, 6_000L, 3_000L, 2_000L, -4_500L, 2_500L) +
                CategoryTotal(5L, "SGD", 99_999L),
        ),
        // Nets to zero with a positive category.
        Fixture("zero", 0L, myr(4_500L, -4_500L)),
    )

    @Test fun theDrawnBarsSumToTheHeroInEveryFixture() {
        for (fixture in fixtures) {
            val view = monthView(read(fixture.totals, fixture.heroSen))
            assertEquals(fixture.name, ringgit(fixture.heroSen), view.hero.figure)
            val drawn = view.bars as Bars.Drawn
            assertEquals(fixture.name, fixture.heroSen, drawn.bars.sumOf { it.sen })
        }
    }

    @Test fun theDrawnBarsSumToTheNetInGeneratedMonths() {
        val random = Random(81)
        repeat(2_000) { case ->
            val count = random.nextInt(1, 15)
            val totals = (1..count).map { id ->
                CategoryTotal(id.toLong(), "MYR", random.nextLong(-50_000L, 200_000L))
            }
            val bars = bars(totals, emptyMap(), uncategorizedId = random.nextLong(1L, 16L), trust = MonthTrust(0))
            if (bars is Bars.Drawn) {
                assertEquals("case $case: $totals", totals.sumOf { it.netSen }, bars.bars.sumOf { it.sen })
            }
        }
    }

    private fun read(totals: List<CategoryTotal>, heroSen: Long) = chartsRead(
        month = september,
        today = LocalDate.of(2026, 10, 10),
        historyStart = LocalDate.of(2026, 1, 1),
        captured = emptySet(),
        monthTotals = listOf(CurrencyTotal("MYR", heroSen)),
        monthSplit = emptyList(),
        previousMonthTotals = emptyList(),
        notInTotal = emptyList(),
        byCategory = totals,
        uncategorizedId = 2L,
    )
}
