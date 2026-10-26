package my.pinged

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ui.test.boundedWaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The storage banner stays true while the user is in the one screen it sends
 * them to. It sits above `NavDisplay`, so it is still drawn over settings
 * when they get there, and nothing there is a resume.
 *
 * Key-gone is provoked as `StorageUnavailableBannerTest` provokes it -- the
 * key file removed beside a database that stays -- and every test puts the
 * process back the way the next one in this APK expects: the same key bytes
 * back where no restore replaced them, and one `guarded` open to clear the
 * flag.
 */
@RunWith(AndroidJUnit4::class)
class StorageBannerFollowsTheLedgerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile get() = File(context.noBackupFilesDir, "db.key")

    /**
     * **On settings the banner offers no button.** Its button goes to
     * settings, which is already on top, so selecting it has nothing to do and
     * the tap would do nothing -- a control that does nothing, which ruling R35
     * forbids.
     */
    @Test fun theBannerOffersNoButtonOnTheScreenItLeadsTo() = withTheKeyGone {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEventually("precondition: the storage banner never appeared") { compose.countOf(LABEL) == 1 }
            compose.onNodeWithText(ACTION).performClick()
            assertTrue("precondition: settings never said the key is gone", compose.waitFor { compose.countOf(KEY_GONE) == 1 })

            assertEquals(
                "Over settings, the banner still offers \"$ACTION\", which leads to the " +
                    "screen already showing and so does nothing",
                0,
                compose.countOf(ACTION),
            )
        }
    }

    /**
     * **Over the allow-list, the button goes back to the settings beneath it.**
     * The banner is drawn over every destination, and the allow-list is
     * pushed from settings; a push there stacked a second settings above it,
     * so back from the settings the button showed went to the allow-list
     * rather than to the ledger the user started on.
     */
    @Test fun theButtonOverTheAllowListGoesBackToTheSettingsUnderIt() = withTheKeyGone {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEventually("precondition: the storage banner never appeared") { compose.countOf(LABEL) == 1 }
            compose.onNodeWithText(ACTION).performClick()
            assertTrue("precondition: settings never opened", compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 })
            // Scrolled to, as in the test below: with the banner and the bottom
            // bar drawn, the row starts below the fold on CI's 320x640
            // emulator, and a click on an off-screen node does nothing.
            compose.onNodeWithText(SOURCES_ROW).performScrollTo().performClick()
            assertTrue("precondition: the allow-list never opened", compose.waitFor { compose.countOf(SOURCES_ONLY) == 1 })
            assertEquals("precondition: no button over the allow-list", 1, compose.countOf(ACTION))

            compose.onNodeWithText(ACTION).performClick()
            assertTrue(
                "precondition: the button over the allow-list did not show settings",
                compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 && compose.countOf(SOURCES_ONLY) == 0 },
            )
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            assertTrue(
                "Back from the settings the banner's button showed did not reach the ledger: " +
                    "the allow-list is showing, so the button pushed a second settings over it",
                compose.waitFor { compose.countOf(SETTINGS_ONLY) == 0 && compose.countOf(SOURCES_ONLY) == 0 },
            )
        }
    }

    /**
     * **A restore from key-gone takes the banner down, even one that finishes
     * after its screen went.** A restore outlives the settings screen that
     * started it (ruling R41), so the user can be back on the ledger when it
     * lands, with no settings read to take the banner down and no resume
     * either: only the database being replaced says so. The restore is
     * `Transfers`' own, with the backup this device's ledger was exported to
     * before its key went; the picker is the only part skipped. What else
     * re-samples the banner is `AppStatusTest`'s.
     */
    @Test fun aRestoreThatFinishesUnderTheLedgerTakesTheBannerDown() {
        val backup = ByteArrayOutputStream().also { ExportJson.write(Databases.shared(context), it) }.toByteArray()
        var restored = false
        withTheKeyGone(putTheKeyBack = { !restored }) {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertEventually("precondition: the storage banner never appeared") { compose.countOf(LABEL) == 1 }

                restored = true
                runBlocking {
                    withTimeout(TIMEOUT) { Transfers.restoreFrom(context, { ByteArrayInputStream(backup) }, Job()).await() }
                }

                assertEventually(
                    "A restore finished under the ledger, and the banner still says Pinged " +
                        "cannot open its database",
                ) { compose.countOf(LABEL) == 0 }
                assertEquals("precondition: settings was opened after all", 0, compose.countOf(SETTINGS_ONLY))
            }
        }
    }

    /**
     * [block] with a database present and its key file gone, the key's bytes
     * handed in; then the process put back as `StorageUnavailableBannerTest`
     * leaves it.
     */
    private fun withTheKeyGone(putTheKeyBack: () -> Boolean = { true }, block: (ByteArray) -> Unit) {
        Databases.shared(context)
        val key = keyFile.readBytes()
        try {
            Databases.reset()
            assertTrue("The key file would not delete, so nothing below is provoked", keyFile.delete())
            block(key)
        } finally {
            if (putTheKeyBack()) keyFile.writeBytes(key)
            Transfers.state.value.outcome?.let { Transfers.acknowledge(it.serial) }
            Databases.reset()
            val opened = runBlocking {
                CaptureStorage.guarded(context, what = "putting the key back", unavailable = { false }) { true }
            }
            assertTrue("The database does not open again; later tests will fail on this", opened)
            assertFalse(
                "The database opens again and the flag is still up",
                runBlocking { ListenerStatus.report(context) }.storageUnavailable,
            )
        }
    }

    private fun report() = runBlocking { ListenerStatus.report(context) }.toString()

    /**
     * Fails with the report as it stands when the wait ran out. Built into the
     * message instead, it is read before the wait starts: issue #16's failure
     * printed `storageUnavailable=true` from the instant after the restore,
     * when the flag had been cleared for 30s.
     *
     * The banner's preconditions use it too: a banner that never appears is a
     * flag that never reached the store or a banner that never sampled it,
     * and only the report says which (issue #179).
     */
    private fun assertEventually(message: String, condition: () -> Boolean) {
        if (!compose.waitFor(condition)) fail("$message: ${report()}")
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { boundedWaitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        const val LABEL = "CANNOT OPEN YOUR DATA"
        const val ACTION = "What can I do about this?"
        const val KEY_GONE = "THE KEY IS GONE"

        /** See `BackupReminderTest.SETTINGS_ONLY`. */
        const val SETTINGS_ONLY = "Delete everything"

        /** Settings' row that opens the allow-list. */
        const val SOURCES_ROW = "Capture sources"

        /** The allow-list's section label, which neither other screen draws. */
        const val SOURCES_ONLY = "ON YOUR PHONE"
    }
}
