package my.pinged.ledger.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.theme.PingedTheme
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.ledger.transfer.seedOneOfEverything
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Design section 6: `RecoveryNotice`'s copy "never says the data is gone in a
 * state where it may not be" -- and, the other way round, never says nothing
 * has been deleted over a restore or a delete that deleted it.
 *
 * Both of its reassurances are claims about history -- "the database is
 * intact", "nothing has been deleted" -- drawn off a reading of storage that
 * a restore or a delete can overtake. These drive the real screen over the
 * real `Transfers` into the two states where they would be false.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryNoticeTruthTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val released = CompletableDeferred<Unit>()

    @Before fun startClean() = runBlocking {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        IntegrityStore.forget(app)
    }

    @After fun letGo() = runBlocking {
        released.complete(Unit)
        withTimeout(TIMEOUT) { Transfers.reading { } }
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        Unit
    }

    /**
     * **Between a restore's result and the read after it.** The result is
     * published with the turn still held, and the screen's re-read waits for
     * the turn -- here behind a read queued while the restore held it, so the
     * gap a device crosses in milliseconds (measured on 0ca70f5: KEY_GONE
     * Restored at 37ms, HEALTHY Restored at 42ms) stays open to look at.
     */
    @Test fun aRestoreFromAGoneKeyIsNotCalledIntactUnderItsOwnResult() = runBlocking {
        val bytes = aBackup()
        DatabaseFactory.build(app).close()
        Databases.reset()
        check(File(app.noBackupFilesDir, "db.key").delete()) { "the key file would not delete" }

        val model = SettingsViewModel(app)
        drawn(model)
        check(appears("THE KEY IS GONE", substring = true)) { "precondition: the screen never reached the key-gone state" }

        val held = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(true)
        model.beforeEachOperation = {
            if (armed.compareAndSet(true, false)) {
                // Undispatched, so it is queued on the turn before this
                // restore lets go of it, and ahead of the screen's re-read.
                CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
                    Transfers.reading {
                        held.complete(Unit)
                        released.await()
                    }
                }
            }
        }
        model.restoreFrom { ByteArrayInputStream(bytes) }
        withTimeout(TIMEOUT) { held.await() }
        withTimeout(TIMEOUT) { model.state.first { it.outcome?.job is TransferJob.Restored } }
        compose.waitForIdle()

        assertEquals(
            "Over the restore's own BACKUP RESTORED, the notice still described the " +
                "ledger the restore had replaced as intact and untouched",
            0,
            count("has been deleted") + count("is intact"),
        )
    }

    /**
     * **After a restore that lost the ledger, when the read after it cannot
     * open what is left.** The spec's case is a full disk failing the
     * rebuild and then the read; here the wipe is failed at its own
     * resolution of the file, with it unlinked, and garbage put where it
     * was, so the read files UNREADABLE through a key that is still present.
     * The notice must not say "Nothing has been deleted." behind the sheet
     * that says the ledger is gone, nor once that is dismissed.
     */
    @Test fun aRestoreThatLostTheLedgerIsNotContradictedByTheNotice() {
        val bytes = aBackup()
        DatabaseFactory.build(app).close()
        Databases.reset()
        val hostile = HostileApp(app)
        hostile.atResolution = { n ->
            if (n == 1) {
                app.getDatabasePath(DatabaseFactory.NAME).writeBytes(ByteArray(GARBAGE) { 0x5A })
                hostile.refusing = true
            }
        }
        runBlocking { SettingsViewModel(hostile).restoreFrom { ByteArrayInputStream(bytes) }.join() }

        val model = SettingsViewModel(app)
        drawn(model)
        check(appears("YOUR LEDGER IS GONE")) { "precondition: the restore did not lose the ledger" }
        runBlocking { withTimeout(TIMEOUT) { model.state.first { it.loaded && it.storage == Storage.UNREADABLE } } }
        compose.waitForIdle()
        assertEquals(
            "Behind \"YOUR LEDGER IS GONE\", the notice said nothing has been deleted",
            0,
            count("has been deleted"),
        )

        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertTrue(
            "Once the lost ledger was acknowledged, the notice was not drawn at all over a " +
                "database that will not open: the read after the restore was taken as stale",
            appears("CANNOT READ YOUR DATA", substring = true),
        )
        assertEquals(
            "Once the lost ledger was acknowledged, the notice said nothing has been deleted",
            0,
            count("has been deleted"),
        )
    }

    /** A backup of one of everything, written from the app's own database path. */
    private fun aBackup(): ByteArray {
        val source = freshDatabase(app)
        seedOneOfEverything(source)
        return exportBytes(source).also { source.close() }
    }

    private fun drawn(viewModel: SettingsViewModel) {
        compose.setContent {
            PingedTheme {
                SettingsScreen(viewModel = viewModel, onBack = {}, onOpenSources = {}, onExport = {}, onRestore = {}, onRescue = {})
            }
        }
    }

    private fun count(text: String): Int =
        compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().size

    private fun appears(text: String, substring: Boolean = false): Boolean = runCatching {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private companion object {
        const val TIMEOUT = 10_000L

        /** One SQLCipher page of something that is not one. */
        const val GARBAGE = 4096
    }
}
