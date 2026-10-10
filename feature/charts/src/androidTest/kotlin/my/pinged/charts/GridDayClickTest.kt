package my.pinged.charts

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.charts.model.CellState
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.DayTotal
import my.pinged.ui.RINGGIT
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * A grid square's tap (#88, #69): every square but a blank one calls
 * `onDayClick` with its own date, and a blank one has no click semantics.
 *
 * Drawn through `ChartsScreenContent`, so the square is found in the screen's
 * scroll as a user reaches it; each tap scrolls to it first (CLAUDE.md).
 */
@RunWith(AndroidJUnit4::class)
class GridDayClickTest {

    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private fun sep(day: Int): LocalDate = september.atDay(day)

    @Test
    fun everySquareButABlankOpensItsOwnDate() {
        // Constructed: history start the 4th, today the 15th, the 12th and
        // 13th not captured, the 9th net negative, so every state is present.
        val totals = mapOf(4 to 3_250L, 5 to 11_840L, 8 to 21_480L, 9 to -26_400L, 15 to 6_000L)
        val view = monthView(
            ChartsRead(
                month = september,
                today = sep(15),
                historyStart = sep(4),
                captured = (4..14).filter { it != 12 && it != 13 }.map(::sep).toSet(),
                monthTotals = listOf(CurrencyTotal(RINGGIT, totals.values.sum())),
                monthSplit = emptyList(),
                previousMonthTotals = emptyList(),
                notInTotal = emptyList(),
                byCategory = emptyList(),
                categoryNames = emptyMap(),
                uncategorizedId = null,
                capturedSinceStart = emptySet(),
                merchantTotals = emptyList(),
                dayTotals = totals.map { (d, s) -> DayTotal(LocalDates.of(sep(d)), RINGGIT, s) },
            ),
        )
        val clicked = mutableListOf<LocalDate>()
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    monthName = "SEPTEMBER 2026",
                    state = ChartsState.Month(view),
                    unavailable = false,
                    onDayClick = { clicked += it },
                )
            }
        }

        val states = view.grid.days.map { it.state::class }.toSet()
        assertEquals("the fixture lost a state", 5, states.size)

        for (day in view.grid.days) {
            val square = compose.onNodeWithTag(dayTag(day.date))
            if (day.state == CellState.Blank) {
                square.assertHasNoClickAction()
            } else {
                square.performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals("the square of ${day.date} (${day.state})", day.date, clicked.last())
            }
        }
        val tappable = view.grid.days.filter { it.state != CellState.Blank }.map { it.date }
        assertEquals((4..15).map(::sep), tappable)
        assertEquals(tappable, clicked)
    }
}
