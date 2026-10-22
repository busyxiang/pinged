package my.pinged.ledger.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.READING_DELAY_MILLIS
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                )
            }
        }
        // Past `ReadingLayout`'s delay (#283): the dashes are drawn after it.
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.onNodeWithText("NEVER", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Export everything") and hasText(NOT_READ_YET)).assertExists()
        compose.onNode(hasText("Check my data") and hasText(NOT_READ_YET)).assertExists()
    }

    @Test fun aKeylessLedgerOffersNeitherExportNorCheck() {
        compose.setContent {
            SettingsRows(
                state = SettingsState(storage = Storage.KEY_GONE, loaded = true),
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = { opened++ }, onOpenCategories = {},
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
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = { opened++ },
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
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
                onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
                onExport = {}, onRestore = {}, onCheck = {}, onDelete = {}, onOpenUnread = { opened++ },
            )
            }
        }
        compose.onNodeWithText("HELPS ADD SUPPORT FOR NEW BANKS").assertIsDisplayed()
        compose.onNodeWithText("1,201").assertIsDisplayed()
        compose.onNodeWithText("Notifications Pinged could not read").performScrollTo().performClick()
        org.junit.Assert.assertEquals(1, opened)
    }

    /**
     * Spec 8: the fonts' licences are one tap from settings, from the footer's
     * LICENCES (#230). The rows have no About section to hold them.
     */
    @Test fun theFooterOpensTheLicencesAndNoRowDoes() {
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(storage = Storage.HEALTHY, loaded = true),
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
                    onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                )
                Footer(buildVersion = "1.0", packVersion = 7, onOpenLicences = { opened++ })
            }
        }
        compose.onNodeWithText("ABOUT").assertDoesNotExist()
        compose.onNodeWithText("Licences").assertDoesNotExist()
        compose.onNodeWithText("BUILD 1.0 · PACK 7 · ", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("LICENCES").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /**
     * #224: "NONE" names a mechanism nobody asked about, so the row is drawn
     * only while a correction waits -- and not while the sweep's count is
     * unknown, for `SettingsState.correctionsPending`'s reason.
     */
    @Test fun correctionsAppearOnlyWhileSomethingWaits() {
        var pending by mutableStateOf<Int?>(0)
        var opened = 0
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(storage = Storage.HEALTHY, correctionsPending = pending, loaded = true),
                    onOpenSources = {}, onOpenCorrections = { opened++ }, onOpenLearned = {}, onOpenCategories = {},
                    onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                )
            }
        }
        compose.onNodeWithText(CORRECTIONS).assertDoesNotExist()
        compose.onNodeWithText("NONE").assertDoesNotExist()
        pending = null
        compose.onNodeWithText(CORRECTIONS).assertDoesNotExist()
        pending = 3
        compose.onNodeWithText("3 TO REVIEW").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CORRECTIONS).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** The board's captions, word for word (#230). */
    @Test fun theCaptionsAreTheBoards() {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = SettingsState(storage = Storage.HEALTHY, loaded = true),
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
                    onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                    alertsOn = true, onAlerts = {},
                )
            }
        }
        compose.onNodeWithText("A DAILY CHECK, IN ANDROID'S SETTINGS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("EVERY TRANSACTION AND ITS NOTIFICATION").performScrollTo().assertIsDisplayed()
    }

    /**
     * #219, drawn by #230: Check my data says what the last check found, as
     * "NO PROBLEMS FOUND" beside when. Only on [Storage.HEALTHY] with a time:
     * there the damage flag is absent, which a check clears and anything that
     * meets damage after it sets (`IntegrityStore`), so the last check is the
     * latest word. Never checked has nothing to report, and on
     * [Storage.DAMAGED] damage may have been met since a clean check -- the
     * recovery notice says that instead.
     */
    @Test fun checkMyDataSaysWhatTheLastCheckFound() {
        var state by mutableStateOf(SettingsState(storage = Storage.HEALTHY, lastCheckAt = System.currentTimeMillis(), loaded = true))
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsRows(
                    state = state,
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
                    onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                )
            }
        }
        compose.onNodeWithText(NO_PROBLEMS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TODAY").assertIsDisplayed()
        state = state.copy(lastCheckAt = 0L)
        compose.onNodeWithText("NEVER CHECKED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(NO_PROBLEMS).assertDoesNotExist()
        state = state.copy(lastCheckAt = null)
        compose.onNodeWithText(NO_PROBLEMS).assertDoesNotExist()
        state = state.copy(storage = Storage.DAMAGED, lastCheckAt = System.currentTimeMillis())
        compose.onNodeWithText("Check my data").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(NO_PROBLEMS).assertDoesNotExist()
    }

    private companion object {
        const val CORRECTIONS = "Corrections from pack updates"
        const val NO_PROBLEMS = "NO PROBLEMS FOUND"
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
                    onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
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
