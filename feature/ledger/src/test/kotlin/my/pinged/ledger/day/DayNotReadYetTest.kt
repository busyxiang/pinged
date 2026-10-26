package my.pinged.ledger.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import my.pinged.ui.weekdayDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Day while its read is in flight (#282): `design/LoadingDay`, through
 * `ReadingLayout`. The read is held back as a null [DayRead].
 */
@RunWith(AndroidJUnit4::class)
class DayNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val clock = HeldClock(compose)

    private val read = MutableStateFlow<DayRead?>(null)
    private val unavailable = MutableStateFlow(false)

    private fun draw(heldBy: HeldBy? = null) {
        clock.hold()
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    DayScreenContent(
                        date = DAY,
                        read = read.collectAsState().value,
                        storageUnavailable = unavailable.collectAsState().value,
                        onBack = {},
                        categories = FakeCategoryActions(),
                        merchants = FakeMerchantActions(),
                    )
                }
            }
        }
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, clock.count(READING))
        assertEquals("The hero's dash was drawn before 200ms", 0, clock.count(HERO_NOT_READ_YET))
        assertEquals("The date, known without a read, waited for it", 1, clock.count(weekdayDate(DAY)))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, clock.count(READING))
        assertEquals("The total was not drawn as the hero's dash", 1, clock.count(HERO_NOT_READ_YET))
        assertEquals("The board's four rows were not each a dash", 4, clock.count(NOT_READ_YET))
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures(weekdayDate(DAY)))
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, clock.count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, clock.count(READING))
    }

    @Test fun aReadDayLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        read.value = DayRead(rows = emptyList(), heading = DayHeading(total = "RM0.00", keptOut = null, notWatching = false), chooser = ChooserRead())
        clock.at(READING_DELAY_MILLIS - 40)

        assertEquals("A day read at 48ms waited for the delay", 1, clock.count("RM0.00"))
    }

    @Test fun anUnreadableDayFoundBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        unavailable.value = true
        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("An unreadable day found at 48ms waited for the delay", 1, clock.count(CANNOT_READ_YOUR_DATA))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("An unreadable day was still drawn as being read", 0, clock.count(READING))
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 9, 25)
    }
}
