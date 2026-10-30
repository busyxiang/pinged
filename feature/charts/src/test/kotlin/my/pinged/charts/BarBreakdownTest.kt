package my.pinged.charts

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.NO_SHARE
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * Every drawn bar on Charts opens what it stands for (#342): a category's
 * bar, net-negative ones included, its Breakdown, each with its own click
 * label for TalkBack, and `Everything else (N)` a sheet of exactly the
 * categories it folds, whose lines open their own Breakdowns. The suppressed
 * sentence is not a bar and opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
class BarBreakdownTest {
    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 20)
    private val breakdowns = mutableListOf<Long>()

    private val names = mapOf(
        1L to "Food & Drinks", 2L to "Groceries", 3L to "Bills & utilities", 4L to "Transport",
        5L to "Petrol & tolls", 6L to "Shopping", 7L to "Health", 8L to "Entertainment", 9L to "Family",
    )

    private fun draw(byCategory: List<CategoryTotal>) {
        val read = chartsRead(
            month = september,
            today = today,
            historyStart = september.atDay(1),
            captured = (1..19).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", byCategory.sumOf { it.netSen })),
            byCategory = byCategory,
            categoryNames = names,
        )
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    september,
                    ChartsState.Month(monthView(read)),
                    unavailable = false,
                    onOpenBreakdown = { breakdowns += it },
                )
            }
        }
    }

    private fun myr(vararg totals: Pair<Long, Long>) = totals.map { (id, sen) -> CategoryTotal(id, "MYR", sen) }

    /** Seven categories: the top five drawn, two folded, and a net-negative one after them. */
    private val folding = myr(
        1L to 84_250L, 2L to 62_180L, 3L to 46_560L, 4L to 38_840L, 5L to 31_000L,
        7L to 12_640L, 9L to 4_000L, 6L to -4_500L,
    )

    @Test fun aCategorysBarOpensItsBreakdownUnderItsOwnLabel() {
        draw(folding)

        val bar = compose.onNode(hasText("Food & Drinks") and hasClickAction())
        assertEquals(
            breakdownLabel("Food & Drinks"),
            bar.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
        bar.performScrollTo().performClick()
        compose.onNode(hasText("Shopping") and hasClickAction()).performScrollTo().performClick()

        assertEquals("A bar opened the wrong Breakdown, or a net-negative one opened none", listOf(1L, 6L), breakdowns)
    }

    @Test fun everythingElseOpensASheetOfExactlyWhatItFoldsAndALineOpensItsBreakdown() {
        draw(folding)

        compose.onNode(hasText("Everything else (2)") and hasClickAction()).performScrollTo().performClick()

        compose.onNodeWithText(EVERYTHING_ELSE).assertExists()
        compose.onNodeWithText("SEPTEMBER 2026 · 2 CATEGORIES · 166.40").assertExists()
        // Each folded name is on the sheet and nowhere else; a drawn bar is not.
        assertEquals(1, compose.onAllNodesWithText("Health").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Family").fetchSemanticsNodes().size)
        assertEquals("A drawn bar is on the sheet", 1, compose.onAllNodesWithText("Groceries").fetchSemanticsNodes().size)
        assertEquals("A net-negative category is folded", 1, compose.onAllNodesWithText("Shopping").fetchSemanticsNodes().size)
        compose.onNodeWithText("126.40").assertExists()
        compose.onNodeWithText("40.00").assertExists()

        compose.onNodeWithText("Family").performClick()

        assertEquals(listOf(9L), breakdowns)
        compose.onNodeWithText(EVERYTHING_ELSE).assertDoesNotExist()
    }

    @Test fun theSuppressedSentenceOpensNothing() {
        draw(myr(1L to -1_000L, 2L to -500L))

        compose.onNodeWithText(NO_SHARE).performScrollTo().performClick()
        compose.onNodeWithText("FOOD & DRINKS −RM10.00", substring = true).performScrollTo().performClick()

        assertEquals(emptyList<Long>(), breakdowns)
        assertEquals(0, compose.onAllNodes(hasClickAction() and hasText("FOOD", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }
}
