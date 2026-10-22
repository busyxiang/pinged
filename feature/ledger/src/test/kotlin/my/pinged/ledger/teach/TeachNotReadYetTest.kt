package my.pinged.ledger.teach

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ledger.ROW_NOT_READ_YET
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.Separator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Teach while its capture's read is in flight (#283): `design/LoadingList.dc.html`. */
@RunWith(AndroidJUnit4::class)
class TeachNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val held = HeldClock(compose)
    private val state = MutableStateFlow(TeachState())

    private fun draw() = held.draw {
        TeachContent(state.collectAsState().value, onBack = {}, onTap = {}, onNoMerchant = {}, onDirection = {}, onSave = {})
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheLayoutWithNoFigure() {
        draw()

        held.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, held.count(READING))
        assertEquals("A placeholder line was drawn before 200ms", 0, held.tagged(ROW_NOT_READ_YET))
        assertEquals("The title, known without a read, was not drawn at once", 1, held.count(TEACH_TITLE))

        held.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, held.count(READING))
        assertEquals("The source and time were not dashes", 1, held.count(NOT_READ_YET + Separator + NOT_READ_YET))
        assertTrue("No placeholder lines where the message goes: ${held.texts()}", held.tagged(ROW_NOT_READ_YET) >= 3)
        assertEquals("Save was offered before the capture was read", 0, held.count(SAVE))
        assertEquals("A read in flight drew a figure", emptyList<String>(), held.figures())
    }

    @Test fun anUnreadableCaptureLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = TeachState(loaded = true, storageUnavailable = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("An unreadable capture found at 48ms waited for the delay", 1, held.count(CANNOT_READ_YOUR_DATA))
        assertEquals("The placeholder lines outlived the read", 0, held.tagged(ROW_NOT_READ_YET))
    }

    @Test fun aCaptureGoneLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = TeachState(loaded = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("A capture found gone at 48ms waited for the delay", 1, held.count(GONE))
    }
}
