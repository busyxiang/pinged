package my.pinged

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The storage banner as a user meets it: no cause named, and a button that
 * reaches the one screen able to name it.
 *
 * `BannerPriorityTest` pins that this kind is chosen and nothing pins the
 * drawing, so an arm with no button, or one wired to `openListenerSettings`
 * like the capture banners, would ship green.
 *
 * **Provoked for real, as spec 11.1's key-gone state:** the database file
 * stays and `no_backup/db.key` goes, which is what a device transfer leaves.
 * `Databases.reset` drops the process's open handle, so `onResume`'s first
 * `CaptureStorage.guarded` rebuilds, `DatabaseKey` refuses to mint a key over
 * an existing file, and the flag is recorded before `ListenerStatus.report`
 * reads it. No grant is needed: this kind outranks every other.
 *
 * **The key's bytes are put back, not regenerated.** The Keystore entry that
 * wraps them is untouched, so the same bytes open the same database, and every
 * test after this one in the APK finds the ledger it left. The `finally` then
 * runs one `guarded` open, which is what clears the flag, and asserts both.
 */
@RunWith(AndroidJUnit4::class)
class StorageUnavailableBannerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile get() = File(context.noBackupFilesDir, "db.key")

    @Test fun theBannerNamesNoCauseAndItsButtonReachesTheScreenThatKnowsIt() {
        // Built if absent, so the key file below exists and a database sits
        // beside it: a key missing with no database is a first run, not this.
        Databases.shared(context)
        val key = keyFile.readBytes()
        try {
            Databases.reset()
            assertTrue("The key file would not delete, so nothing below is provoked", keyFile.delete())

            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(
                    "The storage banner never appeared with the database key gone: " + report(),
                    compose.waitFor { compose.countOf(LABEL) == 1 },
                )
                assertTrue(
                    "The banner names the key. The flag behind it is one boolean " +
                        "for a key gone, a full disk and a delete in progress, so " +
                        "on two of those three it is naming the wrong cause",
                    compose.countOf("key", ignoreCase = true) == 0,
                )

                compose.onNodeWithText(ACTION).performClick()

                assertTrue(
                    "\"$ACTION\" did not reach settings, the only screen that " +
                        "tells a gone key from a full disk and the only place the " +
                        "remedies are offered behind a confirmation",
                    compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 },
                )
                assertTrue(
                    "Settings was reached and did not say which failure this is",
                    compose.waitFor { compose.countOf(KEY_GONE) == 1 },
                )
            }
        } finally {
            keyFile.writeBytes(key)
            Databases.reset()
            val opened = runBlocking {
                CaptureStorage.guarded(context, what = "restoring the key", unavailable = { false }) { true }
            }
            assertTrue("The restored key did not open the database; later tests will fail on this", opened)
            assertFalse(
                "The database opens again and the flag is still up, so every later " +
                    "test in this APK starts under this banner",
                runBlocking { ListenerStatus.report(context) }.storageUnavailable,
            )
        }
    }

    /** What capture says about itself, for a failure message. */
    private fun report() = runBlocking { ListenerStatus.report(context) }.toString()

    /**
     * [ComposeTestRule.waitUntil] wrapped so a failure is the assertion above
     * it and its message, rather than a bare timeout from inside the rule.
     */
    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

    private fun ComposeTestRule.countOf(text: String, ignoreCase: Boolean = false): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = true, ignoreCase = ignoreCase)
            .fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** The label's second half, which only this banner draws. */
        const val LABEL = "CANNOT OPEN YOUR DATA"

        const val ACTION = "What can I do about this?"

        /** See `ExportNudgeBannerTest.SETTINGS_ONLY`. */
        const val SETTINGS_ONLY = "Delete everything"

        /** `RecoveryNotice`'s label for this state, drawn on settings alone. */
        const val KEY_GONE = "THE KEY IS GONE"
    }
}
