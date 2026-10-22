package my.pinged.ledger.home

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Spending's `N TO SORT` (#273): under the top three, the month's count of
 * transactions to sort, one button, and nothing at zero.
 */
@RunWith(AndroidJUnit4::class)
class ToSortLineTest {
    @get:Rule val compose = createComposeRule()

    private var opened = 0

    private fun draw(toSort: Int) = compose.setContent {
        PingedTheme {
            MonthSummaryBlock(
                summary = MonthSummary(
                    month = YearMonth.of(2026, 10),
                    totals = listOf(CurrencyTotal("MYR", 119_273L)),
                    top = emptyList(),
                    trustworthy = true,
                    toSort = toSort,
                ),
                categoriesById = emptyMap(),
                onHeroBottom = {},
                onOpenToSort = { opened++ },
                heroNotReadYetShown = false,
            )
        }
    }

    @Test fun theLineCountsWhatIsToSortAndOpensIt() {
        draw(toSort = 14)

        compose.onNodeWithText("14 TO SORT")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        assertEquals("\"14 TO SORT\" did not open its search", 1, opened)
    }

    @Test fun oneIsOneToSort() {
        draw(toSort = 1)

        compose.onNodeWithText("1 TO SORT").assertExists()
    }

    @Test fun nothingToSortDrawsNoLine() {
        draw(toSort = 0)

        assertEquals(
            "A month with nothing to sort drew the line",
            0,
            compose.onAllNodesWithText("TO SORT", substring = true).fetchSemanticsNodes().size,
        )
    }
}
