package my.pinged.ledger.entry

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edit payment while the entry's read is in flight (#282): the Detail board,
 * `design/LoadingDetail`, through `ReadingLayout`. The read is held back as
 * [ManualEntryState.reading].
 */
@RunWith(AndroidJUnit4::class)
class EditPaymentNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val clock = HeldClock(compose)

    private var state by mutableStateOf(ManualEntryState(editing = 7, reading = true))

    private fun draw(heldBy: HeldBy? = null) {
        clock.hold()
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    ManualEntryScreenContent(
                        state = state,
                        onAmount = {},
                        onMerchant = {},
                        onNote = {},
                        onSave = {},
                        onBack = {},
                        onSaved = {},
                    )
                }
            }
        }
    }

    private fun amountFields() = compose.onAllNodesWithTag(AMOUNT_FIELD).fetchSemanticsNodes().size

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, clock.count(READING))
        assertEquals("The amount's dash was drawn before 200ms", 0, clock.count(HERO_NOT_READ_YET))
        assertEquals("The title, known without a read, waited for it", 1, clock.count(EDIT_PAYMENT))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, clock.count(READING))
        assertEquals("The amount was not drawn as the hero's dash", 1, clock.count(HERO_NOT_READ_YET))
        assertEquals("The merchant's label, known without a read, was not drawn", 1, clock.count(MERCHANT))
        assertEquals("The note's label, known without a read, was not drawn", 1, clock.count(NOTE))
        assertEquals("The merchant and the note were not each a dash", 2, clock.count(NOT_READ_YET))
        assertEquals("A field was drawn to type into before the payment was read", 0, amountFields())
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures())
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, clock.count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, clock.count(READING))
    }

    @Test fun aPaymentReadBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        state = state.copy(reading = false)
        clock.at(READING_DELAY_MILLIS - 40)

        assertEquals("A payment read at 48ms waited for the delay", 1, amountFields())
    }

    /**
     * A ledger that will not open is a read that will not land: the screen
     * says so at once, and does not go on to claim it is reading.
     */
    @Test fun anUnreadableLedgerFoundBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        state = state.copy(storageUnavailable = true)
        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("An unreadable ledger found at 48ms waited for the delay", 1, clock.count(CANNOT_READ_YOUR_DATA))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("An unreadable ledger was still drawn as being read", 0, clock.count(READING))
    }
}
