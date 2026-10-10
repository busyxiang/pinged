package my.pinged.charts

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.MerchantTotal
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * The screen holds the bars and the merchant ranking back behind the
 * collecting gate (#66), and only them: the hero still draws.
 */
@RunWith(RobolectricTestRunner::class)
class CollectingGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val today = LocalDate.of(2026, 9, 20)

    private fun draw(capturedDays: Int) {
        val read = chartsRead(
            month = september,
            today = today,
            historyStart = september.atDay(1),
            captured = (1..19).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 84_250L)),
            monthSplit = emptyList(),
            previousMonthTotals = emptyList(),
            notInTotal = emptyList(),
            byCategory = listOf(CategoryTotal(1L, "MYR", 84_250L)),
            categoryNames = mapOf(1L to "Food & Drinks"),
            merchantTotals = listOf(MerchantTotal("GRAB", "Grab", "MYR", 31_240L, 18)),
            capturedSinceStart = (1L..capturedDays).map { today.minusDays(it) }.toSet(),
        )
        compose.setContent {
            PingedTheme { ChartsScreenContent("SEPTEMBER 2026", ChartsState.Month(monthView(read)), unavailable = false) }
        }
    }

    @Test fun aShutGateDrawsTheCollectingBlockInPlaceOfTheBarsAndTheMerchants() {
        draw(capturedDays = 13)

        compose.onNodeWithText("COLLECTING · 13 OF 14 DAYS").assertExists()
        compose.onNodeWithText("WHERE IT WENT").assertDoesNotExist()
        compose.onNodeWithText("Food & Drinks").assertDoesNotExist()
        compose.onNodeWithText("WHO GOT IT").assertDoesNotExist()
        compose.onNodeWithText("Grab").assertDoesNotExist()
        compose.onNodeWithText("RM842.50").assertExists()
    }

    @Test fun anOpenGateDrawsTheBarsAndTheMerchants() {
        draw(capturedDays = 14)

        compose.onNodeWithText("WHERE IT WENT").assertExists()
        compose.onNodeWithText("Food & Drinks").assertExists()
        compose.onNodeWithText("WHO GOT IT").assertExists()
        compose.onNodeWithText("Grab").assertExists()
        compose.onNodeWithText("COLLECTING", substring = true).assertDoesNotExist()
    }
}
