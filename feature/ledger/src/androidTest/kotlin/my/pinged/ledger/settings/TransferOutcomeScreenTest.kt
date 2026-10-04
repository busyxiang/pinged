package my.pinged.ledger.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.collectAsState
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Job
import my.pinged.ledger.transfer.TransferStore
import my.pinged.ledger.transfer.useDb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.ledger.transfer.seedOneOfEverything
import my.pinged.ledger.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `SettingsScreen` draws of `Transfers`' state, rather than of its own
 * holder's: a result from a screen that has gone, and an operation running
 * that this screen did not start.
 */
@RunWith(AndroidJUnit4::class)
class TransferOutcomeScreenTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        runBlocking { IntegrityStore.forget(app) }
    }

    /**
     * **A restore's result is shown by the next settings screen until it is
     * acknowledged, and not after.** The restore runs on a holder that is
     * then discarded; the screen is composed over a new one.
     */
    @Test fun aRestoreResultFromAnotherScreenIsShownUntilAcknowledged() {
        DatabaseFactory.build(app).close()
        runBlocking {
            SettingsViewModel(app).restoreFrom { ByteArrayInputStream("{\"not\":\"a backup\"}".toByteArray()) }.join()
        }

        val viewModel = SettingsViewModel(app)
        screen(viewModel)
        compose.onNodeWithText("RESTORE DID NOT FINISH").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("RESTORE DID NOT FINISH").assertDoesNotExist()
        assertEquals(
            "An acknowledged outcome is still held, so every later screen shows it again",
            null,
            SettingsViewModel(app).state.value.outcome,
        )
    }

    /**
     * **While an operation runs, the screen says so and its four transfer
     * rows do nothing.** Drawn from state alone, so no operation has to be
     * held open for it.
     */
    @Test fun aRunningOperationIsNamedAndItsRowsAreInert() {
        val taps = AtomicInteger()
        compose.setContent {
            // In a Column, as the screen has them: stacked at the origin, the
            // notice would sit over the rows and take their taps itself.
            PingedTheme {
                Column {
                RunningNotice(TransferJob.Running(Operation.RESTORE, 1_200))
                SettingsRows(
                    state = SettingsState(
                        loaded = true,
                        running = TransferJob.Running(Operation.RESTORE, 1_200),
                    ),
                    onOpenSources = {},
                    onExport = { taps.incrementAndGet() },
                    onRestore = { taps.incrementAndGet() },
                    onCheck = { taps.incrementAndGet() },
                    onDelete = { taps.incrementAndGet() },
                )
                }
            }
        }

        compose.onNodeWithText("RESTORING", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1,200 ROWS", substring = true).assertIsDisplayed()
        listOf("Export everything", "Replace everything from a backup", "Check my data", "Delete everything")
            .forEach { compose.onNodeWithText(it).performClick() }
        compose.waitForIdle()
        assertEquals("A transfer row acted while another operation was running", 0, taps.get())
    }

    /**
     * **A lost ledger nobody has seen yet is not replaced by a lesser
     * outcome.** It is the most serious thing this app says, and the only
     * place it is said: a check settling after it, on a screen that drew
     * nothing in between, would otherwise leave the next screen drawing an
     * ordinary empty ledger. The restore loses the ledger at
     * `DatabaseKey.destroy`'s own resolution of the file, with it unlinked.
     */
    @Test fun aLostLedgerIsStillShownAfterALesserOutcomeSettles() {
        val bytes = aBackup()
        DatabaseFactory.build(app).close()
        val hostile = HostileApp(app)
        hostile.atResolution = { n -> if (n == 1) hostile.refusing = true }
        runBlocking {
            SettingsViewModel(hostile).restoreFrom { ByteArrayInputStream(bytes) }.join()
            SettingsViewModel(app).check().join()
        }
        val kept = Transfers.state.value.outcome
        check(kept?.operation == Operation.CHECK) { "precondition: the check did not settle last: $kept" }

        val viewModel = SettingsViewModel(app)
        drawn(viewModel)
        assertTrue(
            "A lost ledger was replaced by the check that settled after it, and the next " +
                "screen says nothing about it",
            appears("YOUR LEDGER IS GONE"),
        )
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("YOUR LEDGER IS GONE").assertDoesNotExist()
    }

    /**
     * **A check that could not finish says so.** `IntegrityStore.record`
     * failing -- a full disk, here a directory where DataStore's scratch file
     * goes -- is a failure the check row cannot show: its value is the last
     * check that was recorded, and this one was not.
     */
    @Test fun aCheckThatCouldNotFinishSaysSo() {
        DatabaseFactory.build(app).close()
        val scratch = File(app.filesDir, "datastore/integrity.preferences_pb.tmp")
        check(scratch.mkdirs()) { "the scratch path was already taken" }
        try {
            runBlocking { SettingsViewModel(app).check().join() }
        } finally {
            scratch.deleteRecursively()
        }
        check(runBlocking { IntegrityStore.lastCheckAt(app) } == 0L) {
            "precondition: the verdict was recorded after all, so nothing failed"
        }

        drawn(SettingsViewModel(app))
        assertTrue(
            "A check that could not record its verdict was shown nowhere: " +
                "${Transfers.state.value.outcome}",
            appears("CHECK DID NOT FINISH"),
        )
        assertTrue(
            "The sheet did not say what the check found, which nothing else on the " +
                "screen can say now that it was not recorded: ${Transfers.state.value.outcome}",
            appears("found nothing wrong", substring = true),
        )
    }

    /**
     * **A backup written whole is kept when its time will not record, and
     * the sheet says both.** The record is a DataStore write to this phone's
     * disk, which can be full while the file went to a provider elsewhere;
     * caught with every other failure, it deleted the complete file and said
     * only the disk's message.
     */
    @Test fun aCompleteExportWhoseTimeWillNotRecordIsKeptAndSaysSo() {
        val whole = freshDatabase(app).useDb { db ->
            seedOneOfEverything(db)
            exportBytes(db).size
        }
        Databases.reset()
        val target = File(app.cacheDir, "complete-but-unrecorded.json").apply { delete() }
        val deleted = AtomicInteger()
        val sink = object : ExportSink {
            override fun open(): OutputStream = target.outputStream()
            override fun delete(): Boolean {
                deleted.incrementAndGet()
                return target.delete()
            }
        }
        Transfers.recordExport = { _, _ -> throw IOException("No space left on device (simulated)") }
        val job = try {
            runBlocking { (Transfers.export(app, sink, Job()).await() as? Transfers.Answer.Settled)?.outcome?.job }
        } finally {
            Transfers.recordExport = TransferStore::recordExport
        }

        try {
            assertEquals("the complete backup was deleted", 0, deleted.get())
            assertEquals("the file kept is not the whole export", whole.toLong(), target.length())
            assertTrue("the export did not say its time went unrecorded: $job", (job as? TransferJob.Exported)?.unrecorded != null)
            val viewModel = SettingsViewModel(app)
            compose.setContent {
                PingedTheme { Column { ExportSheet(viewModel.state.collectAsState().value, onPick = {}, onDismiss = {}) } }
            }
            assertTrue("the sheet did not say the file was saved", appears("SAVED", substring = true))
            assertTrue("the sheet did not say the file is complete", appears("The file is complete", substring = true))
        } finally {
            target.delete()
        }
    }

    /** A backup of one of everything, from a database of its own. */
    private fun aBackup(): ByteArray {
        val source = freshDatabase(app)
        seedOneOfEverything(source)
        return exportBytes(source).also { source.close() }
    }

    private fun drawn(viewModel: SettingsViewModel) {
        compose.setContent {
            PingedTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenSources = {},
                    onExport = {},
                    onRestore = {},
                    onRescue = {},
                )
            }
        }
    }

    /** Whether [text] is drawn within [TIMEOUT], as an answer rather than a timeout. */
    private fun appears(text: String, substring: Boolean = false): Boolean = runCatching {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private fun screen(viewModel: SettingsViewModel) {
        compose.setContent {
            PingedTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    onOpenSources = {},
                    onExport = {},
                    onRestore = {},
                    onRescue = {},
                )
            }
        }
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("RESTORE DID NOT FINISH").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
