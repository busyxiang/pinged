package my.pinged.ledger.review

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The review inbox while its first read is in flight (#282):
 * `design/LoadingInbox`, through `ReadingLayout`. The read is held back as
 * null cards.
 */
@RunWith(AndroidJUnit4::class)
class InboxNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val clock = HeldClock(compose)

    private val cards = MutableStateFlow<List<ReviewCard>?>(null)
    private val unavailable = MutableStateFlow(false)

    private fun draw(heldBy: HeldBy? = null) {
        clock.hold()
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    ReviewInboxScreenContent(
                        cards = cards.collectAsState().value,
                        storageUnavailable = unavailable.collectAsState().value,
                        busy = null,
                        onBack = {},
                        onAnswer = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, clock.count(READING))
        assertEquals("The count's dash was drawn before 200ms", 0, clock.count(COUNT_NOT_READ_YET))
        assertEquals("The title, known without a read, waited for it", 1, clock.count(INBOX_TITLE))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, clock.count(READING))
        assertEquals("The header's count was not a dash", 1, clock.count(COUNT_NOT_READ_YET))
        assertEquals("The board's three cards did not each draw a dash", 3, clock.count(NOT_READ_YET))
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures())
    }

    @Test fun anExportHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.Export)
        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals("An export holding the database was not named", 1, clock.count(WAITING_FOR_AN_EXPORT))
        assertEquals("READING was drawn beside the export's wording", 0, clock.count(READING))
    }

    @Test fun anEmptyInboxLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        cards.value = emptyList()
        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("An empty inbox read at 48ms waited for the delay", 1, clock.count(NOTHING_AWAITING_REVIEW))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("An empty inbox still drew its count as not read yet", 0, clock.count(COUNT_NOT_READ_YET))
    }

    @Test fun anUnreadableLedgerFoundBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        clock.at(48)

        unavailable.value = true
        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("An unreadable ledger found at 48ms waited for the delay", 1, clock.count(CANNOT_READ_YOUR_DATA))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("An unreadable ledger was still drawn as being read", 0, clock.count(READING))
        assertEquals("An unreadable ledger drew its count as not read yet", 0, clock.count(COUNT_NOT_READ_YET))
    }

    private companion object {
        /** `design/LoadingInbox.dc.html`'s header line. */
        const val COUNT_NOT_READ_YET = "— AWAITING REVIEW"
    }
}
