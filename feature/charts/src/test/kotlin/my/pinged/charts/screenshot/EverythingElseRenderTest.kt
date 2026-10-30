package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.EverythingElseContent
import my.pinged.charts.model.BarOpens
import my.pinged.charts.model.Bars
import my.pinged.charts.model.MonthTrust
import my.pinged.charts.model.bars
import my.pinged.data.dao.CategoryTotal
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `Everything else` sheet (#342, `design/EverythingElse.dc.html`) at CI's
 * 320dp: the board's August, four categories past the top five summing to
 * RM328.90, and the same month untrusted. Drawn without the modal window,
 * whose frame `SheetChromeTest` already holds, inside the frame's padding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-mdpi")
class EverythingElseRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val names = mapOf(
        1L to "Food & Drinks", 2L to "Groceries", 3L to "Bills & utilities", 4L to "Transport",
        5L to "Petrol & tolls", 7L to "Health", 11L to "Telco & internet", 12L to "Entertainment", 13L to "Family",
    )

    private val august = listOf(
        1L to 80_120L, 2L to 60_400L, 3L to 44_870L, 4L to 36_200L, 5L to 29_850L,
        7L to 12_640L, 11L to 9_800L, 12L to 6_450L, 13L to 4_000L,
    ).map { (id, sen) -> CategoryTotal(id, "MYR", sen) }

    @Test fun theSheet() = capture("everything_else", notCaptured = 0)

    @Test fun theSheetInAnUntrustedMonth() = capture("everything_else_untrusted", notCaptured = 1)

    private fun capture(name: String, notCaptured: Int) {
        val drawn = bars(august, names, uncategorizedId = null, MonthTrust(notCaptured)) as Bars.Drawn
        val fold = drawn.bars.single { it.opens is BarOpens.Folded }
        compose.setContent {
            PingedTheme {
                Box(Modifier.testTag(TAG).fillMaxWidth().background(Paper).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)) {
                    EverythingElseContent("AUGUST 2026", (fold.opens as BarOpens.Folded).categories, fold.amount, drawn.greyed, onOpen = {}, onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag(TAG).captureRoboImage(golden(name))
    }

    private companion object {
        const val TAG = "sheet"
    }
}
