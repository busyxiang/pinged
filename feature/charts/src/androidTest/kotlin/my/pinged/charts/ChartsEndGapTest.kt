package my.pinged.charts

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * Scrolled to its end, Charts leaves [CHARTS_END_GAP] under its last line,
 * whichever section that line is in (#389). The screen sits on the tab bar,
 * so a line that ends flush with the scroll's end has the bar's rule and its
 * selected tab's accent drawn against it.
 *
 * Both cases are held to the gap exactly, so the gap `NotInTotalBlock`
 * already carries is not doubled by the screen's.
 */
@RunWith(AndroidJUnit4::class)
class ChartsEndGapTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)

    private fun myr(key: String, name: String, sen: Long) = MerchantTotal(key, name, "MYR", sen, 1)

    private fun draw(notInTotal: List<NotInTotal>) {
        val read = chartsRead(
            month = september,
            today = LocalDate.of(2026, 9, 20),
            historyStart = september.atDay(1),
            captured = (1..19).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 100_000L)),
            notInTotal = notInTotal,
            byCategory = listOf(CategoryTotal(1L, "MYR", 100_000L)),
            categoryNames = mapOf(1L to "Food & Drinks"),
            // A net-negative merchant last, as the month the bug was seen in.
            merchantTotals = listOf(myr("GRAB", "Grab", 60_000L), myr("SHELL", "Shell", 44_500L), myr("SHOPEE", "Shopee", -4_500L)),
        )
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    september,
                    ChartsState.Month(monthView(read)),
                    unavailable = false,
                    // Shorter than the month at CI's 640dp too, so the screen scrolls.
                    modifier = Modifier.height(300.dp).testTag(SCREEN),
                    onOpenReview = {},
                    onOpenMerchant = {},
                )
            }
        }
    }

    /** The space between [lastLine]'s bottom and the screen's, scrolled as far as it goes. */
    private fun gapUnder(lastLine: String): Float {
        val screen = compose.onNodeWithTag(SCREEN)
        screen.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val line = compose.onNode(hasText(lastLine) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        return screen.fetchSemanticsNode().boundsInRoot.bottom - line.bottom
    }

    private fun expected(): Float = with(compose.density) { CHARTS_END_GAP.toPx() }

    @Test fun aMonthWithNothingLeftOutEndsTheGapBelowItsLastMerchant() {
        draw(notInTotal = emptyList())

        assertEquals("Px between the last WHO GOT IT row and the screen's end", expected(), gapUnder("Shopee"), 1f)
    }

    @Test fun aMonthWithLinesLeftOutEndsTheSameGapBelowTheirLast() {
        draw(notInTotal = listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 4, 21_240L)))

        assertEquals("Px between the last NOT IN THE TOTAL line and the screen's end", expected(), gapUnder("4 awaiting review"), 1f)
    }

    private companion object {
        const val SCREEN = "charts-screen"
    }
}
