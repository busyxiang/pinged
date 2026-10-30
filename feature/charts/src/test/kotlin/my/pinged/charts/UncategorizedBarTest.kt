package my.pinged.charts

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import my.pinged.charts.model.ChartsState
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
 * Charts' Uncategorized bar opens Search over the month's rows to sort
 * (#273), with a click label for TalkBack as the "not in the total" lines
 * carry, and nothing else does: every other bar opens its Breakdown (#342),
 * which `BarBreakdownTest` covers.
 */
@RunWith(RobolectricTestRunner::class)
class UncategorizedBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 20)
    private var opened = 0
    private val breakdowns = mutableListOf<Long>()

    private fun draw() {
        val read = chartsRead(
            month = september,
            today = today,
            historyStart = september.atDay(1),
            captured = (1..19).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 119_273L)),
            byCategory = listOf(CategoryTotal(1L, "MYR", 58_049L), CategoryTotal(16L, "MYR", 61_224L)),
            categoryNames = mapOf(1L to "Food & Drinks", 16L to "Uncategorized"),
            uncategorizedId = 16L,
        )
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    YearMonth.of(2026, 9),
                    ChartsState.Month(monthView(read)),
                    unavailable = false,
                    onOpenUncategorized = { opened++ },
                    onOpenBreakdown = { breakdowns += it },
                )
            }
        }
    }

    @Test fun theUncategorizedBarOpensItsSearchAndSaysSo() {
        draw()

        val bar = compose.onNode(hasText("Uncategorized") and hasClickAction())
        assertEquals(
            "The Uncategorized bar carries no click label for TalkBack",
            SORT_UNCATEGORIZED,
            bar.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
        bar.performScrollTo().performClick()

        assertEquals("The Uncategorized bar did not open its search", 1, opened)
        assertEquals("The Uncategorized bar opened a Breakdown", emptyList<Long>(), breakdowns)
    }

    @Test fun aNamedBarDoesNotOpenTheSearch() {
        draw()

        compose.onNodeWithText("Food & Drinks").performScrollTo().performClick()

        assertEquals("A named category's bar opened the Uncategorized search", 0, opened)
    }
}
