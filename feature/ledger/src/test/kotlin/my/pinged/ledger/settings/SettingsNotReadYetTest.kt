package my.pinged.ledger.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_A_CHECK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings while its first read is in flight (#283): `design/LoadingSettings.dc.html`,
 * its sections with every count a dash.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNotReadYetTest {
    @get:Rule val compose = createComposeRule()
    private val held = HeldClock(compose)
    private val state = MutableStateFlow(SettingsState())

    private fun draw(heldBy: HeldBy? = null) = held.draw(heldBy) {
        val now = state.collectAsState().value
        SettingsPage(now, buildVersion = "0.12.0", packVersion = 7, onOpenLicences = {}, onRescue = {}) {
            SettingsRows(
                state = now,
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
                onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
    }

    @Test fun beforeTwoHundredMillisecondsNothingIsDrawnThenTheSectionsWithEveryCountADash() {
        draw()

        held.at(READING_DELAY_MILLIS - 40)
        assertEquals("The status line was drawn before 200ms", 0, held.count(READING))
        assertEquals("A section was drawn before 200ms", 0, held.count("CAPTURE"))
        assertEquals("The footer was drawn before 200ms, where the rows will go", 0, held.count(FOOTER_FIRST_LINE))
        assertEquals("The title, known without a read, was not drawn at once", 1, held.count("Settings"))

        held.at(READING_DELAY_MILLIS + 40)
        assertEquals("No READING status line once 200ms had passed", 1, held.count(READING))
        listOf("CAPTURE", "HOW THINGS GET SORTED", "YOUR DATA", "Capture sources", "Categories", "Export everything", "Delete everything")
            .forEach { assertEquals("$it, known without a read, was not drawn", 1, held.count(it)) }
        // Capture sources, Could not read, Taught, Categories, Export, Check.
        assertTrue("Not every count was a dash: ${held.texts()}", held.count(NOT_READ_YET) >= 6)
        assertEquals("A read in flight drew a figure", emptyList<String>(), held.figures())
    }

    @Test fun anIntegrityCheckHoldingTheDatabaseIsNamed() {
        draw(heldBy = HeldBy.IntegrityCheck)
        held.at(READING_DELAY_MILLIS + 40)

        assertEquals("The integrity check holding the database was not named", 1, held.count(WAITING_FOR_A_CHECK))
        assertEquals("READING was drawn beside the check's wording", 0, held.count(READING))
    }

    @Test fun anUnreadableLedgerLandingBeforeTwoHundredMillisecondsDrawsAtOnce() {
        draw()
        held.at(48)

        state.value = SettingsState(storage = Storage.UNREADABLE, loaded = true)
        held.at(READING_DELAY_MILLIS - 40)

        assertEquals("An unreadable ledger found at 48ms waited for the delay", 1, held.count("Delete everything"))
        assertEquals("The footer did not return with the read", 1, held.count(FOOTER_FIRST_LINE))
        assertEquals("The status line outlived the read", 0, held.count(READING))
    }

    private companion object {
        const val FOOTER_FIRST_LINE = "NO INTERNET PERMISSION · NOTHING LEAVES THIS PHONE"
    }
}
