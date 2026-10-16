package my.pinged.charts

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import my.pinged.charts.model.MonthsItem
import my.pinged.ui.theme.CLOSE
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import kotlin.math.abs

/**
 * #171: the `Months` sheet against `design/Months.dc.html`. Positions off
 * the semantics tree; [MonthsSheetHeaderTest] has the header itself.
 */
class MonthsArtboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The board's 18dp between the header and the list, which `SheetHeader`'s own 14 falls short of. */
    @Test fun theListStarts18dpUnderTheHeader() {
        months()
        val header = compose.onNodeWithContentDescription(CLOSE).fetchSemanticsNode().top()
            .let { it + compose.onNodeWithContentDescription(CLOSE).fetchSemanticsNode().size.height }
        val first = compose.onNodeWithText(NOW.name).fetchSemanticsNode().top()
        val gap = (first - header) / density
        assertTrue("The list starts ${gap}dp under the header, not 18", abs(first - header - 18 * density) <= 1.5f)
    }

    /** `align-items: flex-start` on the flagged row: its total sits level with its name, not centred on two lines. */
    @Test fun aFlaggedRowIsTopAligned() {
        months()
        val name = compose.onNodeWithText(MAY.name, useUnmergedTree = true).fetchSemanticsNode().top()
        val net = compose.onNodeWithText(MAY.net, useUnmergedTree = true).fetchSemanticsNode().top()
        assertEquals("The flagged row's total against its name, px", name, net, 1.5f)
    }

    /** And an unflagged row stays centred, as the board's others are. */
    @Test fun anUnflaggedRowIsCentred() {
        months()
        val name = compose.onNodeWithText(NOW.name, useUnmergedTree = true).fetchSemanticsNode()
        val net = compose.onNodeWithText(NOW.net, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("The row's total against its name's centre, px", name.middle(), net.middle(), 1.5f)
    }

    private fun months() {
        compose.setContent { PingedTheme { MonthsSheet(items = listOf(NOW, MAY), onPick = {}, onDismiss = {}) } }
        compose.waitForIdle()
    }

    private fun SemanticsNode.top() = positionOnScreen.y

    private fun SemanticsNode.middle() = positionOnScreen.y + size.height / 2f

    private val density get() = compose.activity.resources.displayMetrics.density

    private companion object {
        val NOW = MonthsItem.Row(YearMonth.of(2026, 9), "September 2026", "2,847.30", now = true, notCaptured = null, greyed = false)
        val MAY = MonthsItem.Row(YearMonth.of(2026, 5), "May 2026", "2,680.00", now = false, notCaptured = "3 DAYS NOT CAPTURED", greyed = true)
    }
}
