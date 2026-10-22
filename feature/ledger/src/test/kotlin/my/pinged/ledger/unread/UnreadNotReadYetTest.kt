package my.pinged.ledger.unread

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ledger.ROW_NOT_READ_YET
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Could not read while its read is in flight (#283): `design/LoadingList.dc.html`. */
@RunWith(AndroidJUnit4::class)
class UnreadNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val held = HeldClock(compose)
    private val state = MutableStateFlow(UnreadState())

    private fun draw() = held.draw {
        UnreadContent(state.collectAsState().value, onBack = {}, onNeverATransaction = {}, onCopy = {}, onEnd = {})
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheListsLayoutWithNoFigure() {
        draw()

        held.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, held.count(READING))
        assertEquals("A placeholder row was drawn before 200ms", 0, held.tagged(ROW_NOT_READ_YET))
        assertEquals("The title, known without a read, was not drawn at once", 1, held.count(UNREAD_TITLE))

        held.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, held.count(READING))
        assertTrue("No placeholder rows: ${held.texts()}", held.tagged(ROW_NOT_READ_YET) >= 3)
        assertEquals("A read in flight drew a figure", emptyList<String>(), held.figures())
    }

    @Test fun anUnreadableListLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = UnreadState(loaded = true, storageUnavailable = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("An unreadable list found at 48ms waited for the delay", 1, held.count(CANNOT_READ_YOUR_DATA))
        assertEquals("The placeholder rows outlived the read", 0, held.tagged(ROW_NOT_READ_YET))
    }

    @Test fun anEmptyListLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = UnreadState(loaded = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("An empty list read at 48ms waited for the delay", 1, held.count(NOTHING_UNREAD))
    }
}
