package my.pinged.ledger.sources

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capture sources while its read is in flight (#283):
 * `design/LoadingList.dc.html` in place of a bare READING in each section.
 */
@RunWith(AndroidJUnit4::class)
class SourcesNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val held = HeldClock(compose)
    private val state = MutableStateFlow(SourcesState())

    private fun draw() = held.draw {
        SourcesScreenContent(state.collectAsState().value, onToggle = { _, _ -> }, onBack = {})
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheListsLayoutWithNoFigure() {
        draw()

        held.at(READING_DELAY_MILLIS - 40)
        assertEquals("READING was drawn before 200ms", 0, held.count(READING))
        assertEquals("A placeholder row was drawn before 200ms", 0, held.tagged(ROW_NOT_READ_YET))
        assertEquals("The title, known without a read, was not drawn at once", 1, held.count("Capture sources"))

        held.at(READING_DELAY_MILLIS + 40)
        assertEquals("Not one status line once 200ms had passed", 1, held.count(READING))
        assertTrue("The header's count was not a dash: ${held.texts()}", held.texts().any { it.startsWith("$NOT_READ_YET ENABLED") })
        assertTrue("No placeholder rows: ${held.texts()}", held.tagged(ROW_NOT_READ_YET) >= 3)
        assertEquals("The rows' seen count was not a dash", held.tagged(ROW_NOT_READ_YET), held.count("$NOT_READ_YET SEEN"))
        assertEquals("A read in flight drew a figure", emptyList<String>(), held.figures())
    }

    @Test fun anUnreadableLedgerLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = SourcesState(loaded = true, storageUnavailable = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertTrue("An unreadable ledger found at 48ms waited for the delay", held.count(CANNOT_READ_YOUR_DATA) >= 1)
        assertEquals("The placeholder rows outlived the read", 0, held.tagged(ROW_NOT_READ_YET))
    }

    @Test fun anEmptyReadLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = SourcesState(loaded = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("An empty read at 48ms waited for the delay", 1, held.count("NO KNOWN SOURCE IS INSTALLED ON THIS DEVICE"))
        assertTrue("The header's count was not drawn at once", held.texts().any { it.startsWith("0 ENABLED") })
    }
}
