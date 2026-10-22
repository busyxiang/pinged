package my.pinged.charts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.charts.model.MonthsItem
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.YearMonth

/**
 * `Jump to` while its months are read (#281): the sheet opens at once with
 * its title, and its rows are the shared not-read-yet layout after
 * `ReadingLayout`'s delay, never a bare `READING`.
 */
@RunWith(RobolectricTestRunner::class)
class MonthsSheetNotReadYetTest {
    @get:Rule val compose = createComposeRule()

    private val items = MutableStateFlow<List<MonthsItem>?>(null)

    private fun draw(heldBy: HeldBy? = null, onAny: (() -> Unit)? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    MonthsSheet(items.collectAsState().value, onPick = {}, onDismiss = {}, onAny = onAny)
                }
            }
        }
    }

    private val clock = HeldClock(compose)

    private fun at(millis: Long) {
        clock.at(millis)
    }

    private fun texts(): List<String> = clock.texts()

    private fun count(text: String): Int = clock.count(text)

    private companion object {
        val OCTOBER = MonthsItem.Row(YearMonth.of(2026, 10), "October", "1,000.00", now = true, notCaptured = null, greyed = false)
    }

    @Test fun theSheetOpensAtOnceThenDrawsItsRowsNotReadYetWithNoFigure() {
        draw()

        at(READING_DELAY_MILLIS - 40)
        assertEquals("The sheet's title, known without a read, waited for the delay", 1, count("Jump to"))
        assertEquals("The status line was drawn before 200ms", 0, count(READING))
        assertEquals("A dash was drawn before 200ms", 0, texts().count { it.contains(NOT_READ_YET) })

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, count(READING))
        assertTrue(
            "The rows not read yet drew no dash where a month's net goes: ${texts()}",
            texts().count { it == NOT_READ_YET } >= 4,
        )
        assertEquals("A read in flight drew a figure", emptyList<String>(), texts().filter { it.any(Char::isDigit) })
    }

    @Test fun anyMonthIsNotARead() {
        draw(onAny = {})
        at(READING_DELAY_MILLIS - 40)

        assertEquals("Any month, which is no read, waited for the delay", 1, count(ANY_MONTH))
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, count(READING))
    }

    @Test fun monthsLandingBeforeTwoHundredMillisecondsDrawAtOnce() {
        draw()
        at(48)

        items.value = listOf(OCTOBER)
        at(READING_DELAY_MILLIS - 40)

        assertEquals("Months that landed at 48ms waited for the delay", 1, count("October"))
        assertEquals("The rows not read yet outlived the read", 0, count(NOT_READ_YET))
    }
}
