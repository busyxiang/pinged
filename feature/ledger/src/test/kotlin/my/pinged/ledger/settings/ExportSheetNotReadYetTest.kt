package my.pinged.ledger.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export sheet while its counts are read (#284, `design/LoadingSheet`):
 * after 200ms, each count it would draw is a dash rather than nothing.
 */
@RunWith(AndroidJUnit4::class)
class ExportSheetNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val clock = HeldClock(compose)

    private val state = MutableStateFlow(SettingsState())
    private val counts = MutableStateFlow<SpreadsheetCounts?>(null)

    private fun draw() = clock.draw {
        val now = state.collectAsState().value
        ExportReceiptSheet(
            state = now,
            onPick = {},
            onCancel = {},
            onDismiss = {},
            spreadsheet = SpreadsheetPanel(now.spreadsheet, counts.collectAsState().value, onChoose = {}, onPick = {}),
        )
    }

    @Test fun theRowsToWriteAreADashAfterTwoHundredMilliseconds() {
        draw()

        clock.at(READING_DELAY_MILLIS - 40)
        assertEquals("The count was drawn before 200ms", 0, clock.count(ROWS_NOT_READ_YET))
        assertEquals("The cards, known without a read, were not drawn at once", 1, clock.count(EVERYTHING))

        clock.at(READING_DELAY_MILLIS + 40)
        assertEquals("The rows to write were not a dash: ${clock.texts()}", 1, clock.count(ROWS_NOT_READ_YET))
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures())
    }

    @Test fun aCountThatCouldNotBeMadeDrawsNoDash() {
        state.value = SettingsState(loaded = true, exportRows = null)
        draw()
        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals("A count the read could not make was drawn as still being read", 0, clock.count(ROWS_NOT_READ_YET))
    }

    @Test fun theRowsLandingReplaceTheDash() {
        draw()
        clock.at(READING_DELAY_MILLIS + 40)

        state.value = SettingsState(loaded = true, exportRows = 1_204)
        clock.at(READING_DELAY_MILLIS + 80)

        assertEquals("The landed count was not drawn", 1, clock.count("1,204 ROWS TO WRITE"))
        assertEquals("The dash outlived the read", 0, clock.count(ROWS_NOT_READ_YET))
    }

    @Test fun whatToLeaveOutIsDashedWhileTheSpreadsheetIsCounted() {
        draw()
        compose.onNodeWithText(SPREADSHEET).performClick()
        clock.at(HeldClock.FRAME)

        assertEquals("WHAT TO LEAVE OUT was drawn before 200ms", 0, clock.count(WHAT_TO_LEAVE_OUT))

        clock.at(READING_DELAY_MILLIS + 80)
        assertEquals("WHAT TO LEAVE OUT was not drawn while counted: ${clock.texts()}", 1, clock.count(WHAT_TO_LEAVE_OUT))
        assertEquals("The two leave-outs' counts were not each a dash", 2, clock.count(NOT_READ_YET))
        assertEquals("A count in flight drew a figure", emptyList<String>(), clock.figures())
    }

    @Test fun nothingToLeaveOutDropsTheSectionOnceCounted() {
        draw()
        compose.onNodeWithText(SPREADSHEET).performClick()
        clock.at(READING_DELAY_MILLIS + 80)

        counts.value = SpreadsheetCounts(SpreadsheetChoice(), rows = 3, excluded = 0, pending = 0)
        clock.at(READING_DELAY_MILLIS + 120)

        assertEquals("A month with nothing to leave out still drew the section", 0, clock.count(WHAT_TO_LEAVE_OUT))
    }

    private companion object {
        val ROWS_NOT_READ_YET = "$NOT_READ_YET ROWS TO WRITE"
    }
}
