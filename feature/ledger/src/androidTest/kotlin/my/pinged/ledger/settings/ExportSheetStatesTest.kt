package my.pinged.ledger.settings

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.ledger.transfer.seedOneOfEverything
import my.pinged.ledger.transfer.useDb
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transfer design 10's export states that are not failures: idle with the
 * count of what will be written, running with a cancel, done naming where
 * the file went. Each is asserted against the state holder and then drawn.
 */
@RunWith(AndroidJUnit4::class)
class ExportSheetStatesTest {
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
     * **The idle count is the rows the export writes**, measured against the
     * writer itself over a ledger with a row in every section, so a count
     * that left a table out -- or counted rejected transactions away, as the
     * delete sheet's does -- disagrees with it.
     */
    @Test fun idleCountsWhatTheExportWillWrite() {
        val written = freshDatabase(app).useDb { db ->
            seedOneOfEverything(db)
            ExportJson.write(db, ByteArrayOutputStream())
        }
        Databases.reset()
        val viewModel = SettingsViewModel(app)
        runBlocking { viewModel.refresh() }

        assertEquals("the idle sheet's count is not what the export writes", written, viewModel.state.value.exportRows)
        sheet(viewModel)
        assertTrue(
            "the idle sheet does not show the count",
            appears("${grouped(written)} ROWS TO WRITE"),
        )
    }

    /** A running export's sheet offers Cancel, and Cancel is wired to [ExportSheet]'s `onCancel`. */
    @Test fun runningOffersACancel() {
        val cancels = AtomicInteger()
        compose.setContent {
            PingedTheme {
                // Scrollable, as `ReceiptSheet` is, for `performScrollTo`.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ExportSheet(
                        SettingsState(running = TransferJob.Running(Operation.EXPORT, 1_000)),
                        onPick = {},
                        onCancel = { cancels.incrementAndGet() },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Cancel did not reach onCancel", 1, cancels.get())
    }

    /**
     * **Done names the document**, by the name its provider gives it -- which
     * is the user's, typed in the picker, and not the one Pinged suggested.
     */
    @Test fun doneNamesTheDocument() {
        freshDatabase(app).useDb { seedOneOfEverything(it) }
        Databases.reset()
        val target = File(app.cacheDir, "named.json").apply { delete() }
        val sink = object : ExportSink {
            override fun open(): OutputStream = target.outputStream()
            override fun delete(): Boolean = target.delete()
            override fun name(): String = "Backup for the new phone.json"
        }
        try {
            val job = runBlocking {
                (Transfers.export(app, sink, Job()).await() as? Transfers.Answer.Settled)?.outcome?.job
            }
            assertEquals(
                "the outcome does not name the document",
                "Backup for the new phone.json",
                (job as? TransferJob.Exported)?.savedAs,
            )
            sheet(SettingsViewModel(app))
            assertTrue("the done sheet does not name the document", appears("Backup for the new phone.json", substring = true))
        } finally {
            target.delete()
        }
    }

    /**
     * [DocumentSink.name] against a real provider: MediaStore's Downloads
     * collection serves `OpenableColumns.DISPLAY_NAME` as every document
     * provider must, and needs no permission for the app's own entry.
     */
    @Test fun aDocumentSinkReadsItsProvidersName() {
        assumeTrue("MediaStore.Downloads is API 29", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = app.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "pinged-sink-name-test.json") },
        ) ?: error("MediaStore would not create a download")
        try {
            assertEquals("pinged-sink-name-test.json", DocumentSink(resolver, uri).name())
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun sheet(viewModel: SettingsViewModel) {
        compose.setContent {
            PingedTheme {
                Column {
                    ExportSheet(viewModel.state.collectAsState().value, onPick = {}, onCancel = {}, onDismiss = {})
                }
            }
        }
    }

    /** Whether [text] is drawn within [TIMEOUT], as an answer rather than a timeout. */
    private fun appears(text: String, substring: Boolean = false): Boolean = runCatching {
        compose.boundedWaitUntil(TIMEOUT) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }.isSuccess

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
