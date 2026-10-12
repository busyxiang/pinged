package my.pinged.charts

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * The "not in the total" block's *N awaiting review* line opens the review
 * inbox (§9.2's ways in), and its other lines open nothing: there is no
 * screen for a left-out transfer to lead to.
 */
@RunWith(RobolectricTestRunner::class)
class NotInTotalReviewTest {

    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)

    @Test fun theAwaitingReviewLineOpensTheInboxAndTheOthersDoNot() {
        var opened = 0
        val read = chartsRead(
            month = september,
            today = LocalDate.of(2026, 9, 20),
            historyStart = september.atDay(1),
            captured = (1..19).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 84_250L)),
            monthSplit = emptyList(),
            previousMonthTotals = emptyList(),
            notInTotal = listOf(
                NotInTotal(NotInTotalLine.TRANSFERS, "MYR", 3, 45_000L),
                NotInTotal(NotInTotalLine.PENDING, "MYR", 4, 21_240L),
            ),
            byCategory = emptyList(),
            categoryNames = emptyMap(),
            merchantTotals = emptyList(),
            capturedSinceStart = emptySet(),
        )
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    "SEPTEMBER 2026",
                    ChartsState.Month(monthView(read)),
                    unavailable = false,
                    onOpenReview = { opened++ },
                )
            }
        }

        compose.onNodeWithText("Transfers, left out").performScrollTo().performClick()
        assertEquals("The transfers line opened the review inbox", 0, opened)

        compose.onNodeWithText("4 awaiting review").performScrollTo().performClick()
        assertEquals("Charts' '4 awaiting review' did not open the review inbox", 1, opened)
    }
}
