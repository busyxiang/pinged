package my.pinged

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ListenerStatus
import my.pinged.data.DatabaseKeyUnavailableException
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.transfer.ExportJson
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
            assertTrue("precondition: the storage banner never appeared", compose.waitFor { compose.countOf(LABEL) == 1 })
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
            assertTrue("precondition: the storage banner never appeared", compose.waitFor { compose.countOf(LABEL) == 1 })
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
     * **A restore from key-gone takes the banner down.** There is no resume
     * after it, so a banner sampled only on resume said "Pinged cannot open
     * its database" over "BACKUP RESTORED" for the rest of the visit. The
     * restore is `Transfers`' own, with the backup this device's ledger was
     * exported to before its key went; the picker is the only part skipped.
     *
     * Two things take it down here -- the database being replaced, and
     * settings' read after the restore -- so this is the user's sequence
     * rather than the test of either; the two below take one each away.
     */
    @Test fun aRestoreFromAGoneKeyTakesTheBannerDown() {
        val backup = ByteArrayOutputStream().also { ExportJson.write(Databases.shared(context), it) }.toByteArray()
        var restored = false
        withTheKeyGone(putTheKeyBack = { !restored }) {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("precondition: the storage banner never appeared", compose.waitFor { compose.countOf(LABEL) == 1 })
                compose.onNodeWithText(ACTION).performClick()
                assertTrue("precondition: settings never said the key is gone", compose.waitFor { compose.countOf(KEY_GONE) == 1 })

                restored = true
                runBlocking {
                    withTimeout(TIMEOUT) { Transfers.restoreFrom(context, { ByteArrayInputStream(backup) }, Job()).await() }
                }
                assertTrue("precondition: the restore did not finish", compose.waitFor { compose.countOf(RESTORED) == 1 })

                assertEventually(
                    "The ledger was restored and opens, and the banner still says Pinged " +
                        "cannot open its database",
                ) { compose.countOf(LABEL) == 0 }
            }
        }
    }

    /**
     * **And one that finishes after its screen went.** A restore outlives the
     * settings screen that started it (ruling R41), so the user can be back
     * on the ledger when it lands, with no settings read to take the banner
     * down and no resume either: only the database being replaced says so.
     */
    @Test fun aRestoreThatFinishesUnderTheLedgerTakesTheBannerDown() {
        val backup = ByteArrayOutputStream().also { ExportJson.write(Databases.shared(context), it) }.toByteArray()
        var restored = false
        withTheKeyGone(putTheKeyBack = { !restored }) {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("precondition: the storage banner never appeared", compose.waitFor { compose.countOf(LABEL) == 1 })

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
     * **A clear that lands after the banner sampled takes it down.** A sample
     * can read the flag inside a refusal's window -- a ledger read refused
     * mid-restore raises it, and the next one that opens clears it 2-3ms
     * later -- and nothing samples again: the banner stood over a healthy
     * ledger for the rest of the visit (issue #16, 3 in 40 under load). Here
     * the refusal is put inside the sample through [betweenProbeAndReport].
     * The clear is left to whichever open comes next, as it was there: what
     * decides the banner is whether anything samples after it.
     */
    @Test fun aClearAfterTheBannerSampledTakesItDown() {
        // Counted so the refusal is armed only once the launch's own sample
        // is past it; met there, the reset's sample reads a clean flag.
        val samples = AtomicInteger()
        val refuse = AtomicBoolean(false)
        betweenProbeAndReport = {
            samples.incrementAndGet()
            if (refuse.compareAndSet(true, false)) {
                CaptureStorage.guarded(context, what = "a read refused mid-restore", unavailable = { }) {
                    throw DatabaseKeyUnavailableException("simulated: refused mid-restore")
                }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("precondition: the launch never sampled", compose.waitFor { samples.get() >= 1 })
                assertEquals("precondition: a banner over a healthy ledger", 0, compose.countOf(LABEL))
                refuse.set(true)
                Databases.reset()
                // The refusal having run, not the banner having shown: with
                // the flag watched, the banner can come down before a wait
                // for it to be up sees it.
                assertTrue("precondition: the sample never met the refusal", compose.waitFor { !refuse.get() })

                assertEventually(
                    "The flag was cleared after the banner sampled it, and the banner still " +
                        "says Pinged cannot open its database",
                ) { compose.countOf(LABEL) == 0 }
            }
        } finally {
            betweenProbeAndReport = {}
            runBlocking { CaptureStorage.guarded(context, what = "clearing the flag", unavailable = { }) { } }
        }
    }

    /**
     * **A cause that clears while the user is on settings takes the banner
     * down with the screen's own reading.** A transient full disk is the
     * case: the database opens again, settings reads HEALTHY, and "Settings
     * says more" is false. Here the key comes back with no reset, so nothing
     * but settings' own read reaches the database.
     */
    @Test fun aBannerThatSettingsNoLongerBearsOutGoesAway() = withTheKeyGone { key ->
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("precondition: the storage banner never appeared", compose.waitFor { compose.countOf(LABEL) == 1 })
            keyFile.writeBytes(key)
            compose.onNodeWithText(ACTION).performClick()
            assertTrue(
                "precondition: settings never reached the database once the cause cleared",
                compose.waitFor { compose.countOf(SETTINGS_ONLY) == 1 && compose.countOf(KEY_GONE) == 0 },
            )

            assertEventually(
                "Settings reads the database as healthy, and the banner above it still " +
                    "says it cannot be opened and that settings says more",
            ) { compose.countOf(LABEL) == 0 }
        }
    }

    /**
     * **Every settings read samples the banner again, not only one that found
     * something new.** A capture refused just as a gate lifts can raise the
     * storage flag between a sample's probe and its report, putting the
     * banner up over a ledger settings reads as healthy -- and every read
     * after that finds it healthy again. The refusal lands there through
     * [betweenProbeAndReport], by the `CaptureStorage.guarded` path a capture
     * takes; the database being replaced is what samples, and the read that
     * follows is settings' own, after "Check my data".
     */
    @Test fun aSettingsReadThatFindsNothingNewStillTakesAStaleBannerDown() {
        // Counted so the refusal is armed only once the samples already under
        // way -- the launch's resume, settings' first read -- are past it.
        val samples = AtomicInteger()
        val refuse = AtomicBoolean(false)
        betweenProbeAndReport = {
            samples.incrementAndGet()
            if (refuse.compareAndSet(true, false)) {
                CaptureStorage.guarded(context, what = "a capture refused as the gate lifts", unavailable = { }) {
                    throw DatabaseKeyUnavailableException("simulated: refused as the gate lifts")
                }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue("precondition: the launch never sampled", compose.waitFor { samples.get() >= 1 })
                compose.onNodeWithText(SETTINGS_TAB).performClick()
                assertTrue("precondition: settings' first read never sampled", compose.waitFor { samples.get() >= 2 })
                assertEquals("precondition: a banner over a healthy ledger", 0, compose.countOf(LABEL))

                refuse.set(true)
                Databases.reset()
                // As in [aClearAfterTheBannerSampledTakesItDown]: the
                // refusal having run, since the watched flag can take the
                // banner down before a wait for it to be up sees it.
                assertTrue("precondition: the sample never met the refusal", compose.waitFor { !refuse.get() })
                // Scrolled to: on CI's 320x640 emulator the row starts below
                // the fold, and a click on an off-screen node does nothing.
                compose.onNodeWithText(CHECK_ROW).performScrollTo().performClick()
                assertTrue(
                    "precondition: the check never settled",
                    compose.waitFor { Transfers.state.value.outcome != null && Transfers.state.value.running == null },
                )

                assertEventually(
                    "Settings read the ledger as healthy after its check, and the banner above " +
                        "it still says Pinged cannot open its database",
                ) { compose.countOf(LABEL) == 0 }
            }
        } finally {
            betweenProbeAndReport = {}
            Transfers.state.value.outcome?.let { Transfers.acknowledge(it.serial) }
            runBlocking {
                IntegrityStore.forget(context)
                CaptureStorage.guarded(context, what = "clearing the flag", unavailable = { }) { }
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
     */
    private fun assertEventually(message: String, condition: () -> Boolean) {
        if (!compose.waitFor(condition)) fail("$message: ${report()}")
    }

    private fun ComposeTestRule.waitFor(condition: () -> Boolean): Boolean =
        runCatching { waitUntil(TIMEOUT) { condition() } }.isSuccess

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
        const val RESTORED = "BACKUP RESTORED"

        /** See `ExportNudgeBannerTest.SETTINGS_ONLY`. */
        const val SETTINGS_ONLY = "Delete everything"

        /** The bottom bar's settings tab; see `SettingsNavigationTest`. */
        const val SETTINGS_TAB = "SETTINGS"

        /** Settings' row that runs a check, drawn only over a readable ledger. */
        const val CHECK_ROW = "Check my data"

        /** Settings' row that opens the allow-list. */
        const val SOURCES_ROW = "Capture sources"

        /** The allow-list's section label, which neither other screen draws. */
        const val SOURCES_ONLY = "ON YOUR PHONE"
    }
}
