package my.pinged.charts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.DayTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * `design/ChartsMonth.dc.html`, "October, so far" (#232): Charts on 14 October,
 * a month still running, with September whole behind it.
 */
@RunWith(RobolectricTestRunner::class)
class ChartsMonthArtboardTest {

    @get:Rule
    val compose = createComposeRule()

    private val october = YearMonth.of(2026, 10)
    private val today = october.atDay(14)

    @Before fun draw() {
        val september = october.minusMonths(1)
        val read = chartsRead(
            month = october,
            today = today,
            historyStart = september.atDay(1),
            captured = generateSequence(september.atDay(1)) { it.plusDays(1) }.takeWhile { it < today }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 110_140L)),
            previousMonthTotals = listOf(CurrencyTotal("MYR", 276_010L)),
            dayTotals = listOf(DayTotal(LocalDates.of(october.atDay(13)), "MYR", 6_120L), DayTotal(LocalDates.of(today), "MYR", 2_250L)),
            notInTotal = listOf(
                NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 1, 10_000L),
                NotInTotal(NotInTotalLine.USER_EXCLUDED, "MYR", 1, 3_200L),
                NotInTotal(NotInTotalLine.PENDING, "MYR", 2, 14_500L),
            ),
        )
        compose.setContent {
            PingedTheme { ChartsScreenContent("OCTOBER 2026", ChartsState.Month(monthView(read)), unavailable = false) }
        }
    }

    @Test fun theChipCountsTheDays() {
        compose.onNodeWithText("DAY 14 OF 31").assertExists()
    }

    @Test fun theHeroSaysSpentSoFar() {
        compose.onNodeWithText("SPENT SO FAR").assertExists()
    }

    @Test fun aLineSaysWhenTheComparisonWillCome() {
        compose.onNodeWithText("COMPARED WITH SEPTEMBER ONCE OCTOBER ENDS").assertExists()
    }

    @Test fun theLegendSaysTodayIsStillGoing() {
        compose.onNodeWithText("TODAY, STILL GOING").performScrollTo()
        compose.onNodeWithText("TODAY").assertDoesNotExist()
    }

    @Test fun theLegendSaysTheDaysToComeAreBlank() {
        compose.onNodeWithText("DAYS STILL TO COME ARE LEFT BLANK.").performScrollTo()
    }

    @Test fun theUserExcludedLineSaysYouLeftOut() {
        compose.onNodeWithText("You left out").performScrollTo()
    }

    @Test fun theReviewLineIsInStampWithAChevron() {
        val line = compose.onNodeWithText("2 awaiting review").performScrollTo()
        assertEquals("'2 awaiting review' is not in Stamp", Stamp, colour(line))
        compose.onNodeWithTag(REVIEW_CHEVRON, useUnmergedTree = true).assertExists()
    }

    private fun colour(node: SemanticsNodeInteraction): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single().layoutInput.style.color
    }
}
