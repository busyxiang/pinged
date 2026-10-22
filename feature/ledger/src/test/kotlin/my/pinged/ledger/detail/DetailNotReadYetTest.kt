package my.pinged.ledger.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_A_CHECK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Detail while its read is in flight (#282): `design/LoadingDetail`, through
 * `ReadingLayout`. The read is held back as a null [DetailRead].
 */
@RunWith(AndroidJUnit4::class)
class DetailNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val clock = HeldClock(compose)

    private val read = MutableStateFlow<DetailRead?>(null)
    private val unavailable = MutableStateFlow(false)

    private fun draw(heldBy: HeldBy? = null) {
        clock.hold()
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    DetailScreenContent(
                        read = read.collectAsState().value,
                        storageUnavailable = unavailable.collectAsState().value,
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, clock.count(READING))
        assertEquals("The fields' labels were drawn before 200ms", 0, clock.count(WHEN))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, clock.count(READING))
        assertEquals("The amount was not drawn as the hero's dash", 1, clock.count(HERO_NOT_READ_YET))
        for (label in listOf(WHEN, CATEGORY, CAME_FROM, NAME_ON_THE_BILL, WHAT_PINGED_READ)) {
            assertEquals("The board's $label, known without a read, was not drawn", 1, clock.count(label))
        }
        assertEquals("The eyebrow and the four fields were not each a dash", 5, clock.count(NOT_READ_YET))
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures())
    }

    @Test fun theDirectionIsNotClaimedBeforeTheRead() {
        draw()
        clock.at(READING_DELAY_MILLIS + 40)

        // A refund's eyebrow is CAME BACK: which one it is, is a read.
        assertEquals("A row not read yet was called PAID", 0, clock.count(PAID))
        assertEquals("A row not read yet was called CAME BACK", 0, clock.count(CAME_BACK))
    }

    @Test fun anIntegrityCheckHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.IntegrityCheck)
        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals("An integrity check holding the database was not named", 1, clock.count(WAITING_FOR_A_CHECK))
        assertEquals("READING was drawn beside the check's wording", 0, clock.count(READING))
    }

    @Test fun aPaymentGoneBeforeTwoHundredMillisecondsSaysSoAtOnce() {
        draw()
        clock.at(48)

        read.value = DetailRead(row = null, chooser = ChooserRead())
        clock.at(READING_DELAY_MILLIS - 40)

        assertEquals("A payment found gone at 48ms waited for the delay", 1, clock.count(GONE))
    }

    @Test fun anUnreadableLedgerFoundBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        unavailable.value = true
        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("An unreadable ledger found at 48ms waited for the delay", 1, clock.count(CANNOT_READ_YOUR_DATA))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("An unreadable ledger was still drawn as being read", 0, clock.count(READING))
    }
}
