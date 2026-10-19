package my.pinged.ledger.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import my.pinged.ui.theme.NOT_READ_YET
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * The screen offers no row it cannot run: Export on a database that will not
 * open is a button whose only outcome is an error.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHealthyLedgerOffersEverything() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, sourcesOn = 4, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Export everything").assertIsDisplayed()
        compose.onNodeWithText("Check my data").assertIsDisplayed()
        compose.onNodeWithText("NEVER").assertDoesNotExist()
        compose.onNodeWithText("NEVER CHECKED").assertDoesNotExist()
    }

    /**
     * #237: before the first read lands the two times are not known, and the
     * rows say so with the loading boards' dash rather than "NEVER", which a
     * user who exported yesterday would take for an answer.
     */
    @Test fun beforeTheReadLandsNoTimeIsClaimed() {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(),
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                )
            }
        }
        compose.onNodeWithText("NEVER", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Export everything") and hasText(NOT_READ_YET)).assertExists()
        compose.onNode(hasText("Check my data") and hasText(NOT_READ_YET)).assertExists()
    }

    @Test fun aKeylessLedgerOffersNeitherExportNorCheck() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.KEY_GONE, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Capture sources").assertIsDisplayed()
        compose.onNodeWithText(" ON", substring = true).assertDoesNotExist()
    }

    /** #50 and spec 5.7: the row's value is both lists' count, and it opens them. */
    @Test fun theTaughtRowShowsTheRuleCountAndOpensTheList() {
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, learnedCount = 1_230, ignoredCount = 4, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = { opened++ }, onOpenCategories = {}, onOpenLicences = {},
                onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
            }
        }
        compose.onNodeWithText("Things you taught Pinged").assertIsDisplayed()
        compose.onNodeWithText("1,234").assertIsDisplayed()
        compose.onNodeWithText("Things you taught Pinged").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, opened)
    }

    /** #155: the artboard's "Categories · 14", and it opens the list. */
    @Test fun theCategoriesRowShowsTheCountAndOpensTheList() {
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(storage = Storage.HEALTHY, categoryCount = 17, loaded = true),
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = { opened++ }, onOpenLicences = {},
                    onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                )
            }
        }
        compose.onNodeWithText("17").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Categories").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** A count nobody could read is drawn as nothing, as every count on this screen is. */
    @Test fun anUnreadCountIsDrawnAsNothing() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, learnedCount = null, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {},
                onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
        }
        compose.onNodeWithText("Things you taught Pinged").assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    /** Spec 5.6: the unread row says how many, and opens the list. */
    @Test fun theUnreadRowShowsItsCountAndOpensTheList() {
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, unreadCount = 1_201, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {},
                onExport = {}, onRestore = {}, onCheck = {}, onDelete = {}, onOpenUnread = { opened++ },
            )
            }
        }
        compose.onNodeWithText("HELPS ADD SUPPORT FOR NEW BANKS").assertIsDisplayed()
        compose.onNodeWithText("1,201").assertIsDisplayed()
        compose.onNodeWithText("Notifications Pinged could not read").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, opened)
    }

    /** Spec 8: the fonts' licences are one tap from settings. */
    @Test fun theLicencesRowOpensTheLicences() {
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            SettingsRows(
                state = SettingsState(storage = Storage.HEALTHY, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = { opened++ },
                onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
            )
            }
        }
        compose.onNodeWithText("Licences").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, opened)
    }

    /**
     * Spec 10.2: the daily check can only reach a user who allowed it to post,
     * and this row is where that is asked -- never unprompted on launch.
     */
    @Test fun alertsSayWhetherTheyCanReachYouAndAskWhenTapped() {
        var asked = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(storage = Storage.HEALTHY, loaded = true),
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                    alertsOn = false,
                    onAlerts = { asked++ },
                )
            }
        }
        compose.onNodeWithText(CAPTURE_ALERTS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OFF").assertIsDisplayed()
        compose.onNodeWithText(CAPTURE_ALERTS).performClick()
        compose.runOnIdle { assertEquals(1, asked) }
    }
}
