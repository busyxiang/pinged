package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.CategoryBars
import my.pinged.charts.CollectingBlock
import my.pinged.charts.model.Bars
import my.pinged.charts.model.MonthTrust
import my.pinged.charts.model.bars
import my.pinged.charts.model.collecting
import my.pinged.data.dao.CategoryTotal
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The category bars and the collecting block (#91), each drawn from
 * constructed totals at the artboard's 390dp on the lower ground.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The artboard's width; Robolectric's default screen is narrower than it.
@Config(qualifiers = "w390dp-h844dp")
class CategoryBarsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val names = mapOf(
        1L to "Food & Drinks", 2L to "Groceries", 3L to "Bills & utilities", 4L to "Transport",
        5L to "Petrol & tolls", 6L to "Shopping", 7L to "Health", 8L to "Entertainment",
        9L to "Religious & zakat", 10L to "Government & fees", UNCATEGORIZED to "Uncategorized",
    )

    private fun of(vararg totals: Pair<Long, Long>) = totals.map { (id, sen) -> CategoryTotal(id, "MYR", sen) }

    private fun bars(totals: List<CategoryTotal>, notCaptured: Int = 0): Bars =
        bars(totals, names, UNCATEGORIZED, MonthTrust(notCaptured))

    /** The artboard's September, with a seventh to tenth category folded into the remainder. */
    private val typical = of(
        1L to 84_250L, 2L to 62_180L, 3L to 46_560L, 4L to 38_840L, 5L to 31_000L,
        6L to 21_900L, 7L to 9_600L, 9L to 5_000L, 10L to 3_000L,
    )

    @Test fun typicalBars() = capture("bars_typical") { CategoryBars(bars(typical)) }

    @Test fun aNegativeCategoryAndUncategorized() = capture("bars_negative_uncategorized") {
        CategoryBars(
            bars(of(1L to 74_210L, 9L to 50_000L, 10L to 3_000L, 6L to -4_500L, UNCATEGORIZED to 54_000L)),
        )
    }

    @Test fun everyCategoryAtOrBelowZero() = capture("bars_suppressed") {
        CategoryBars(bars(of(6L to -149_900L, 1L to 0L, UNCATEGORIZED to -2_000L)))
    }

    @Test fun anUntrustedMonthGreysTheAmounts() = capture("bars_untrusted") {
        CategoryBars(bars(typical, notCaptured = 3))
    }

    @Test fun theCollectingBlock() = capture("collecting") {
        val today = LocalDate.of(2026, 9, 10)
        CollectingBlock(collecting(today.minusDays(9), today, (1L..9L).map { today.minusDays(it) }.toSet()))
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            PingedTheme {
                Box(Modifier.testTag(TAG).width(390.dp).background(Card).padding(bottom = 16.dp)) { content() }
            }
        }
        compose.onNodeWithTag(TAG).captureRoboImage(golden(name))
    }

    private companion object {
        const val TAG = "section"
        const val UNCATEGORIZED = 99L
    }
}
