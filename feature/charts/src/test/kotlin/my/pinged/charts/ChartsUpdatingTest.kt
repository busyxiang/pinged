package my.pinged.charts

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.UPDATING
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * Charts after a month pick (#285), `design/LoadingSearch`'s treatment: until
 * November's read lands, October's stays, under `UPDATING` once the re-read
 * has run 200ms, the header names the month picked and its chip, a count
 * of the month not read yet, is a dash. The 45% dim is a pixel, asserted on
 * a device by `ChartsUpdatingDimTest`.
 */
@RunWith(RobolectricTestRunner::class)
class ChartsUpdatingTest {
    @get:Rule val compose = createComposeRule()

    private val october = YearMonth.of(2026, 10)
    private val read = chartsRead(
        month = october,
        today = october.atDay(14),
        historyStart = october.minusMonths(1).atDay(1),
        captured = generateSequence(october.minusMonths(1).atDay(1)) { it.plusDays(1) }.takeWhile { it < october.atDay(14) }.toSet(),
        monthTotals = listOf(CurrencyTotal("MYR", 110_140L)),
    )

    /** Whether November, picked, is still being read. */
    private val rereading = MutableStateFlow(false)
    private val selected = MutableStateFlow(october)

    private fun draw() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    month = selected.collectAsState().value,
                    today = october.atDay(14),
                    state = ChartsState.Month(monthView(read)),
                    unavailable = false,
                    rereading = rereading.collectAsState().value,
                )
            }
        }
        at(HeldClock.FRAME)
    }

    private fun pick() {
        selected.value = october.plusMonths(1)
        rereading.value = true
        clock.restart()
    }

    private val clock = HeldClock(compose) { now -> if (count(UPDATING) > 0) updatingAt += now }
    private val updatingAt = mutableListOf<Long>()

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    @Test fun aPickedMonthKeepsTheLastMonthAndSaysUpdatingOnceTheReReadHasRun200ms() {
        draw()
        assertEquals("The settled month drew a status line", 0, count(UPDATING))

        pick()
        at(READING_DELAY_MILLIS - 40)
        assertEquals("UPDATING was drawn before 200ms", 0, count(UPDATING))
        assertEquals("The settled month's chip went before 200ms", 1, count("DAY 14 OF 31"))

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No UPDATING once the re-read had run 200ms: ${texts()}", 1, count(UPDATING))
        assertEquals("The re-read went back to READING", 0, count(READING))
        assertEquals("The last month's hero was not kept", 1, count("SPENT SO FAR"))
        assertEquals("The header does not name the month picked: ${texts()}", 1, count("NOVEMBER 2026"))
        assertEquals("The last month's chip, a count about to change, is still drawn", 0, count("DAY 14 OF 31"))
        assertEquals("The chip is not a dash: ${texts()}", 1, count(NOT_READ_YET))
    }

    @Test fun aReReadThatLandsInside200msNeverSaysUpdating() {
        draw()

        pick()
        at(READING_DELAY_MILLIS - 60)
        rereading.value = false
        at(READING_DELAY_MILLIS * 3)

        assertEquals("A re-read that landed inside 200ms drew UPDATING, at these ms", emptyList<Long>(), updatingAt)
    }
}
