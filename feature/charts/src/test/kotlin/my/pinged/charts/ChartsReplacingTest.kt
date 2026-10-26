package my.pinged.charts

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * Charts while a ledger replacement turns its read away (#328, decision 2):
 * [REPLACING_YOUR_DATA] where [CANNOT_READ_YOUR_DATA] would go, from the read
 * state alone, and none of the month it held, which describes the ledger
 * going away. `:feature:ledger`'s screens are `ReplacingNoticeTest`.
 */
@RunWith(RobolectricTestRunner::class)
class ChartsReplacingTest {
    @get:Rule val compose = createComposeRule()

    private val october = YearMonth.of(2026, 10)
    private val read = chartsRead(
        month = october,
        today = october.atDay(14),
        historyStart = october.minusMonths(1).atDay(1),
        captured = generateSequence(october.minusMonths(1).atDay(1)) { it.plusDays(1) }.takeWhile { it < october.atDay(14) }.toSet(),
        monthTotals = listOf(CurrencyTotal("MYR", 110_140L)),
    )

    private val replacing = MutableStateFlow(false)

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test fun aReplacementDrawsItsNoticeAndNoneOfTheMonthHeld() {
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    month = october,
                    today = october.atDay(14),
                    state = ChartsState.Month(monthView(read)),
                    unavailable = false,
                    replacing = replacing.collectAsState().value,
                )
            }
        }
        assertEquals("precondition: the month's total was not drawn before the replacement", true, count("1,101") > 0)

        replacing.value = true
        compose.waitForIdle()

        assertEquals("Charts refused by a replacement did not say so", 1, count(REPLACING_YOUR_DATA))
        assertEquals("Charts refused by a replacement said its data cannot be read", 0, count(CANNOT_READ_YOUR_DATA))
        assertEquals("Charts refused by a replacement still drew the month it held", 0, count("1,101"))
    }
}
