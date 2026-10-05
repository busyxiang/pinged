package my.pinged.ledger.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * The screen offers no row it cannot run: Export on a database that will not
 * open is a button whose only outcome is an error.
 */
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHealthyLedgerOffersEverything() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, sourcesOn = 4, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertIsDisplayed()
        compose.onNodeWithText("Check my data").assertIsDisplayed()
        compose.onNodeWithText("4 ON").assertIsDisplayed()
        compose.onNodeWithText("NEVER").assertIsDisplayed()
    }

    /**
     * A time nobody could read is drawn as nothing. "NEVER" would be a claim
     * about a store that would not open, and to a user who exported
     * yesterday a false one; see `SettingsStoreGuardTest`.
     */
    @Test fun aTimeThatCouldNotBeReadIsDrawnAsNothing() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, lastExportAt = null, lastCheckAt = null, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertIsDisplayed()
        compose.onNodeWithText("Check my data").assertIsDisplayed()
        compose.onNodeWithText("NEVER").assertDoesNotExist()
        compose.onNodeWithText("NEVER CHECKED").assertDoesNotExist()
    }

    @Test fun aKeylessLedgerOffersNeitherExportNorCheck() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.KEY_GONE, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertDoesNotExist()
        compose.onNodeWithText("Check my data").assertDoesNotExist()
        compose.onNodeWithText("Replace everything from a backup").assertIsDisplayed()
    }

    /**
     * [Storage.DAMAGED] is still readable (design 2.3), so it can be checked;
     * but design 6 gives it no Export, whose first damaged page throws and
     * leaves nothing. The recovery notice's rescue replaces it
     * (`RecoveryStateTest`).
     */
    @Test fun aDamagedLedgerOffersCheckButNotExport() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.DAMAGED, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertDoesNotExist()
        compose.onNodeWithText("Check my data").assertIsDisplayed()
    }

    /** The other unreadable state, alongside [aKeylessLedgerOffersNeitherExportNorCheck]. */
    @Test fun anUnreadableLedgerOffersNeitherExportNorCheck() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.UNREADABLE, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertDoesNotExist()
        compose.onNodeWithText("Check my data").assertDoesNotExist()
        compose.onNodeWithText("Replace everything from a backup").assertIsDisplayed()
    }

    /**
     * A count query that aborted mid-scan must not fall back to a wrong
     * count. `SettingsState.sourcesOn` null draws no value at all -- not
     * `0 ON`, which would be a wrong answer shown with confidence.
     */
    @Test fun aSourceCountThatCouldNotBeReadDrawsNoValue() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.DAMAGED, sourcesOn = null, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Capture sources").assertIsDisplayed()
        compose.onNodeWithText(" ON", substring = true).assertDoesNotExist()
    }
}
