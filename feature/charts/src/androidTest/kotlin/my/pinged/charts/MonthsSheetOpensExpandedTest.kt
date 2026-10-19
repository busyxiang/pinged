package my.pinged.charts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import my.pinged.charts.model.MonthsItem
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth

/**
 * #213: `Jump to` opens all the way, down to the history-start divider, when
 * its rows land a frame after it opens.
 *
 * That is every first open: both hosts open the sheet on `READING` and read
 * the months as it composes. With the system's animations off -- the
 * accessibility setting, and every emulator this project tests on -- the
 * sheet's show animation runs in one frame, and a sheet whose content grew in
 * that frame stayed on its `READING` height: the title and October, September
 * cut off at the screen's edge. Measured on emulator-5554 at 1080x2424: the
 * title's top at 2112px against 1520px when expanded. Rows landing 50ms after
 * the open, or present from the start, were never cut.
 *
 * `MotionDurationScale` at 0 is what the setting gives a composition; the
 * test clock otherwise runs the animation at full length.
 */
class MonthsSheetOpensExpandedTest {
    @get:Rule val compose = createComposeRule(
        effectContext = object : MotionDurationScale {
            override val scaleFactor = 0f
        },
    )

    private val rows: List<MonthsItem> = (0L..3L).map {
        val month = YearMonth.of(2026, 10).minusMonths(it)
        MonthsItem.Row(month, "Month $it", "1,000.00", now = it == 0L, notCaptured = null, greyed = false)
    } + MonthsItem.Divider(DIVIDER)

    @Test fun rowsThatLandAFrameAfterTheOpenAreAllOnScreen() {
        var items by mutableStateOf<List<MonthsItem>?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent { PingedTheme { MonthsSheet(items, {}, {}) } }
        compose.mainClock.advanceTimeByFrame()
        items = rows
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithText("Month 3").assertIsDisplayed()
        compose.onNodeWithText(DIVIDER).assertIsDisplayed()
    }

    private companion object {
        const val DIVIDER = "NOTHING BEFORE 4 JULY 2026"
    }
}
