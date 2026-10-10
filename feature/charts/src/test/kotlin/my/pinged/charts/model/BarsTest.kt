package my.pinged.charts.model

import my.pinged.data.dao.CategoryTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The category bars: their order, scale, fills and the suppressed sentence (#81, Bars; #68). */
class BarsTest {
    private val whole = MonthTrust(notCaptured = 0)

    private val names = mapOf(
        1L to "Food & Drinks", 2L to "Groceries", 3L to "Bills & utilities", 4L to "Transport",
        5L to "Petrol & tolls", 6L to "Shopping", 7L to "Health", 8L to "Entertainment",
        9L to "Religious & zakat", 10L to "Government & fees", UNCATEGORIZED to "Uncategorized",
    )

    private fun of(vararg totals: Pair<Long, Long>) = totals.map { (id, sen) -> CategoryTotal(id, "MYR", sen) }

    private fun drawn(totals: List<CategoryTotal>, trust: MonthTrust = whole): Bars.Drawn {
        val bars = bars(totals, names, UNCATEGORIZED, trust)
        check(bars is Bars.Drawn) { "Expected bars, got $bars" }
        return bars
    }

    @Test fun sixOrFewerCategoriesAreAllDrawnLargestFirstWithNoRemainder() {
        val bars = drawn(of(4L to 38_840L, 1L to 84_250L, 6L to 21_900L, 2L to 62_180L, 5L to 31_000L, 3L to 46_560L))

        assertEquals(
            listOf("Food & Drinks", "Groceries", "Bills & utilities", "Transport", "Petrol & tolls", "Shopping"),
            bars.bars.map { it.name },
        )
        assertEquals(
            listOf("842.50", "621.80", "465.60", "388.40", "310.00", "219.00"),
            bars.bars.map { it.amount },
        )
        assertEquals("Ranked bars take the ramp darkest first", listOf(0, 1, 2, 3, 4, 5), bars.bars.map { it.rampStep })
    }

    @Test fun moreThanSixDrawTheTopFiveThenEverythingElseSummingTheRest() {
        val bars = drawn(
            of(
                1L to 74_210L, 2L to 49_830L, 3L to 41_000L, 4L to 35_280L, 5L to 30_150L,
                6L to 13_390L, 7L to 9_600L, 8L to 6_800L, 9L to 5_000L, 10L to 3_000L,
            ),
        )

        assertEquals(
            listOf("Food & Drinks", "Groceries", "Bills & utilities", "Transport", "Petrol & tolls", "Everything else (5)"),
            bars.bars.map { it.name },
        )
        // 133.90 + 96.00 + 68.00 + 50.00 + 30.00
        assertEquals("377.90", bars.bars.last().amount)
        assertEquals("The remainder fills grey, not the next ramp step", null, bars.bars.last().rampStep)
        assertEquals("The remainder's name is quiet", true, bars.bars.last().quiet)
        assertEquals(false, bars.bars.first().quiet)
    }

    @Test fun exactlySevenFoldTheLastTwo() {
        val bars = drawn(of(1L to 700L, 2L to 600L, 3L to 500L, 4L to 400L, 5L to 300L, 6L to 200L, 7L to 100L))

        assertEquals("Everything else (2)", bars.bars.last().name)
        assertEquals("3.00", bars.bars.last().amount)
        assertEquals(6, bars.bars.size)
    }

    @Test fun aNetNegativeCategoryDrawsAfterTheRemainderAtZeroWidthAndIsNeverFolded() {
        val bars = drawn(
            of(
                1L to 70_000L, 2L to 60_000L, 3L to 50_000L, 4L to 40_000L, 5L to 30_000L,
                6L to -4_500L, 7L to 2_000L, 8L to 1_000L, 9L to -500L,
            ),
        )

        assertEquals(
            listOf(
                "Food & Drinks", "Groceries", "Bills & utilities", "Transport", "Petrol & tolls",
                "Everything else (2)", "Religious & zakat", "Shopping",
            ),
            bars.bars.map { it.name },
        )
        assertEquals("The refund is not netted into the remainder", "30.00", bars.bars[5].amount)
        assertEquals(listOf("−5.00", "−45.00"), bars.bars.drop(6).map { it.amount })
        assertEquals(listOf(0f, 0f), bars.bars.drop(6).map { it.fraction })
    }

    @Test fun sixPositiveAndOneNegativeDrawAllSixWithNoRemainder() {
        val bars = drawn(of(1L to 700L, 2L to 600L, 3L to 500L, 4L to 400L, 5L to 300L, 6L to 200L, 7L to -100L))

        assertEquals("Only categories ≥ 0 are ranked", 7, bars.bars.size)
        assertEquals("Health", bars.bars.last().name)
    }

    @Test fun uncategorizedIsLastAfterADottedRuleWithAQuietNameAndGreyFill() {
        val bars = drawn(of(UNCATEGORIZED to 54_000L, 1L to 74_210L, 2L to 49_830L, 6L to -4_500L))

        assertEquals(listOf("Food & Drinks", "Groceries", "Shopping", "Uncategorized"), bars.bars.map { it.name })
        val last = bars.bars.last()
        assertEquals("540.00", last.amount)
        assertEquals(null, last.rampStep)
        assertEquals(true, last.quiet)
        assertEquals(true, last.ruleBefore)
        assertEquals("Only Uncategorized is set apart", listOf(false, false, false), bars.bars.dropLast(1).map { it.ruleBefore })
        assertEquals("Uncategorized is never ranked: the next category keeps step 1", 1, bars.bars[1].rampStep)
    }

    @Test fun uncategorizedNeverTakesARankedSlotFromTheTopFive() {
        val bars = drawn(of(UNCATEGORIZED to 1_000_00L, 1L to 600L, 2L to 500L, 3L to 400L, 4L to 300L, 5L to 200L, 6L to 100L))

        assertEquals(
            "Six ranked categories plus Uncategorized: all six draw, with no remainder",
            listOf("Food & Drinks", "Groceries", "Bills & utilities", "Transport", "Petrol & tolls", "Shopping", "Uncategorized"),
            bars.bars.map { it.name },
        )
    }

    @Test fun theLargestDrawnBarIsTheFullTrackAndTheRestAreItsShare() {
        // The artboard: Groceries 621.80 / 842.50 = 74%.
        val bars = drawn(of(1L to 84_250L, 2L to 62_180L))

        assertEquals(1f, bars.bars[0].fraction)
        assertEquals(0.738, bars.bars[1].fraction.toDouble(), 0.001)
    }

    @Test fun uncategorizedLargerThanEveryCategoryIsTheFullTrack() {
        val bars = drawn(of(1L to 30_000L, UNCATEGORIZED to 60_000L))

        assertEquals(listOf(0.5f, 1f), bars.bars.map { it.fraction })
    }

    @Test fun theRemainderLargerThanEveryRankedBarIsTheFullTrack() {
        val small = (1L..5L).map { it to 1_000L } + (6L..10L).map { it to 900L }
        val bars = drawn(of(*small.toTypedArray()))

        assertEquals("Everything else (5)", bars.bars.last().name)
        assertEquals(1f, bars.bars.last().fraction)
        assertEquals(1_000f / 4_500f, bars.bars.first().fraction, 0.0001f)
    }

    @Test fun aCategoryLargerThanTheMonthTotalStillDrawsAtFullWidth() {
        // A laptop refund leaves the month at −RM12.40: 1,486.60 spent, 1,499.00 back.
        val bars = drawn(of(1L to 148_660L, 6L to -149_900L))

        assertEquals(listOf(1f, 0f), bars.bars.map { it.fraction })
        assertEquals("−1,499.00", bars.bars[1].amount)
    }

    @Test fun aZeroMonthTotalWithAPositiveCategoryStillDrawsTheBars() {
        val bars = drawn(of(1L to 4_500L, 6L to -4_500L))

        assertEquals(listOf("Food & Drinks", "Shopping"), bars.bars.map { it.name })
        assertEquals(1f, bars.bars[0].fraction)
    }

    @Test fun aTinyCategoryIsStillAboveZeroWidth() {
        val bars = drawn(of(1L to 100_000_000L, 2L to 1L))

        assertTrue("A positive bar vanished: ${bars.bars[1].fraction}", bars.bars[1].fraction > 0f)
    }

    @Test fun everyCategoryAtOrBelowZeroIsOneSentenceFollowedByTheNegatives() {
        val bars = bars(of(1L to 0L, 6L to -149_900L, UNCATEGORIZED to -2_000L), names, UNCATEGORIZED, whole)

        assertEquals(
            Bars.Suppressed(
                sentence = "MORE CAME BACK THAN WENT OUT THIS MONTH, SO THERE IS NO SHARE TO DRAW.",
                negatives = "SHOPPING −RM1,499.00 · UNCATEGORIZED −RM20.00",
                greyed = false,
            ),
            bars,
        )
    }

    @Test fun aMonthWithNoCategoriesDrawsNoBarsAndNoSentence() {
        assertEquals(Bars.Nothing, bars(emptyList(), names, UNCATEGORIZED, whole))
    }

    @Test fun aMonthWhoseCategoriesAllNetToZeroClaimsNothingCameBack() {
        assertEquals(Bars.Nothing, bars(of(1L to 0L, 2L to 0L), names, UNCATEGORIZED, whole))
    }

    @Test fun anotherCurrencyIsNeitherDrawnNorScaledAgainst() {
        val bars = drawn(listOf(CategoryTotal(1L, "MYR", 1_000L), CategoryTotal(2L, "SGD", 9_000L)))

        assertEquals(listOf("Food & Drinks"), bars.bars.map { it.name })
        assertEquals(1f, bars.bars[0].fraction)
    }

    @Test fun anUntrustedMonthGreysTheAmountsAndKeepsTheRamp() {
        val bars = drawn(of(1L to 1_000L, 2L to 500L), MonthTrust(notCaptured = 1))

        assertTrue(bars.greyed)
        assertEquals(listOf(0, 1), bars.bars.map { it.rampStep })
        val suppressed = bars(of(6L to -500L), names, UNCATEGORIZED, MonthTrust(notCaptured = 2))
        assertTrue((suppressed as Bars.Suppressed).greyed)
    }

    @Test fun aTrustedMonthIsNotGreyed() {
        assertFalse(drawn(of(1L to 1_000L)).greyed)
    }

    private companion object {
        const val UNCATEGORIZED = 99L
    }
}
