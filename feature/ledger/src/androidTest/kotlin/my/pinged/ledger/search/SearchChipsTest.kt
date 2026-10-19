package my.pinged.ledger.search

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.YearMonth
import my.pinged.ui.monthName
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's four chips at a Pixel's 411dp (#274), measured in the bundled
 * mono: unset, they fit on one line, beside the month chip either unset or
 * at its longest name. At CI's 320dp they may wrap, so the width is forced.
 */
@RunWith(AndroidJUnit4::class)
class SearchChipsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theUnsetChipsFitOnOneLineBesideSeptember() =
        assertOneLine(SearchChoice(month = YearMonth.of(2026, 9)), monthName(YearMonth.of(2026, 9)))

    @Test fun theUnsetChipsFitOnOneLineWithNoMonth() = assertOneLine(SearchChoice(month = null), MONTH_CHIP)

    private fun assertOneLine(choice: SearchChoice, month: String) {
        compose.setContent {
            PingedTheme {
                SearchScreenContent(
                    query = "",
                    onQuery = {},
                    choice = choice,
                    read = null,
                    storageUnavailable = false,
                    onBack = {},
                    onOpenSheet = {},
                    modifier = Modifier.requiredWidth(411.dp),
                )
            }
        }
        val tops = listOf(month, CATEGORY_CHIP, APP_CHIP, AMOUNT_CHIP).associateWith {
            compose.onNodeWithTag(CHIP + it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("The chips wrapped at 411dp: tops $tops", 1, tops.values.distinct().size)
    }
}
