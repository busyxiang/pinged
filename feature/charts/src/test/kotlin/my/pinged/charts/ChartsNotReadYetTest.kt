package my.pinged.charts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.charts.model.ChartsState
import my.pinged.ui.monthName
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import my.pinged.ui.theme.WAITING_FOR_A_CHECK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * Charts while its first read is in flight (#281): `design/LoadingCharts`,
 * through `ReadingLayout`.
 *
 * The read is held back by handing the screen [ChartsState.Reading], and the
 * main clock does not advance on its own, so "before 200ms" is a time this
 * test chose.
 */
@RunWith(RobolectricTestRunner::class)
class ChartsNotReadYetTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val state = MutableStateFlow<ChartsState>(ChartsState.Reading)
    private val unavailable = MutableStateFlow(false)

    private fun draw(heldBy: HeldBy? = null, today: LocalDate = LocalDate.of(2026, 10, 22)) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    ChartsScreenContent(
                        september,
                        state.collectAsState().value,
                        unavailable = unavailable.collectAsState().value,
                        today = today,
                    )
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

    private fun cells() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NOT_READ_YET_DAY), useUnmergedTree = true)
        .fetchSemanticsNodes().size


    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, count(READING))
        assertEquals("The grid's label was drawn before 200ms", 0, count("WHEN IT WENT"))
        assertEquals("A cell was drawn before 200ms", 0, cells())
        assertEquals("A dash was drawn before 200ms", 0, texts().count { it.contains(NOT_READ_YET) })

        at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, count(READING))
        assertEquals("The bare READING line is still drawn beside the layout", 1, texts().count { it.contains("READING") })
        listOf("TOTAL SPENT", "WHEN IT WENT", "WHERE IT WENT", "WHO GOT IT").forEach {
            assertEquals("$it, known without a read, was not drawn", 1, count(it))
        }
        assertEquals("September's 30 days were not each drawn as a cell not read yet", 30, cells())
        assertTrue(
            "The layout drew no em-dash where a value goes: ${texts()}",
            texts().count { it.contains(NOT_READ_YET) } >= 9,
        )
        val month = monthName(september)
        val figures = texts().filter { it != month && it.any(Char::isDigit) }
        assertEquals("A read in flight drew a figure, an amount or a count", emptyList<String>(), figures)
    }

    /** A day still to come is blank when the month lands, so it is not a cell waiting to be read either. */
    @Test fun daysStillToComeAreNotCellsWaitingToBeRead() {
        draw(today = september.atDay(20))
        at(READING_DELAY_MILLIS + 40)

        assertEquals("Days after the 20th were drawn as waiting to be read", 20, cells())
    }

    @Test fun theRunningMonthsHeroSaysSoFar() {
        draw(today = september.atDay(20))
        at(READING_DELAY_MILLIS + 40)

        assertEquals("The running month's hero did not read SPENT SO FAR", 1, count("SPENT SO FAR"))
        assertEquals("The running month's hero read as a whole month", 0, count("TOTAL SPENT"))
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, count(READING))
    }

    @Test fun anIntegrityCheckHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.IntegrityCheck)
        at(READING_DELAY_MILLIS + 40)

        assertEquals("An integrity check holding the database was not named", 1, count(WAITING_FOR_A_CHECK))
        assertEquals("The background check was blamed on an export", 0, count(WAITING_FOR_AN_EXPORT))
    }

    @Test fun anEmptyHistoryLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        at(48)

        state.value = ChartsState.Empty(monthName(september))
        at(READING_DELAY_MILLIS - 40)

        assertEquals("An empty history that landed at 48ms waited for the delay", 1, count("NOTHING CAPTURED YET"))
    }

    @Test fun anUnreadableDatabaseFoundBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        at(48)

        unavailable.value = true
        at(READING_DELAY_MILLIS - 40)

        assertEquals("An unreadable database found at 48ms waited for the delay", 1, count(CANNOT_READ_YOUR_DATA))
    }
}
