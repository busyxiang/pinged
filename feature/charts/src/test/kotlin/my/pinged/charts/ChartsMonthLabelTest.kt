package my.pinged.charts

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.MONTH_CHEVRON
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * Charts' month label as `Charts.dc.html` and `LoadingCharts.dc.html` both
 * draw it, and as Spending's is: the month in [Muted] and the board's SVG
 * chevron beside it, not an Ink `⌄` glyph. On the loaded path and while the
 * first read is in flight, since the month is the selection, not a read.
 */
@RunWith(RobolectricTestRunner::class)
class ChartsMonthLabelTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val clock = HeldClock(compose)

    private fun draw(state: ChartsState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(september, state, unavailable = false, today = september.atEndOfMonth().plusDays(22))
            }
        }
        clock.at(READING_DELAY_MILLIS + 40)
    }

    @Test fun whileTheReadIsInFlightTheMonthIsTheBoardsLabel() {
        draw(ChartsState.Reading)
        assertTheBoardsLabel()
    }

    @Test fun onceTheMonthIsReadItIsTheBoardsLabel() {
        val read = chartsRead(
            month = september,
            today = september.atEndOfMonth().plusDays(22),
            historyStart = september.atDay(1),
            captured = (1..30).map(september::atDay).toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 284_730L)),
        )
        draw(ChartsState.Month(monthView(read)))
        assertTheBoardsLabel()
    }

    private fun assertTheBoardsLabel() {
        assertEquals(
            "A text glyph stands in for the board's chevron: ${clock.texts()}",
            emptyList<String>(),
            clock.texts().filter { it.contains('⌄') },
        )
        val chevrons = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MONTH_CHEVRON), useUnmergedTree = true)
            .fetchSemanticsNodes().size
        assertEquals("The board's chevron was not drawn beside the month", 1, chevrons)
        val node = compose.onNodeWithText("SEPTEMBER 2026", useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals("The month is not in the boards' Muted", Muted, layouts.single().layoutInput.style.color)
    }
}
