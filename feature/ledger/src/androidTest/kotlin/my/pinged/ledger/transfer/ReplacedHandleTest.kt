package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import my.pinged.data.DatabaseFactory
import my.pinged.data.DatabaseReplacedException
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ruling R42 for the transfer code's own raw paths. `runInTransaction` and
 * `openHelper` both reopen a closed instance's helper -- measured on
 * emulator-5554, `runInTransaction` on a closed instance answered and left
 * `isOpen` true -- so an export or import through a handle that has been
 * closed reaches the file with nothing in [Databases] having let it.
 */
@RunWith(AndroidJUnit4::class)
class ReplacedHandleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startClean() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @After fun cleanUp() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun anExportThroughAReplacedHandleDoesNotReopenTheFile() {
        val replaced = Databases.shared(context)
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)

        val outcome = runCatching { ExportJson.write(replaced, ByteArrayOutputStream()) }

        assertFalse(
            "ExportJson reopened the ledger through a handle Databases had replaced, " +
                "and answered $outcome",
            context.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
        assertTrue("the export was not refused: $outcome", outcome.exceptionOrNull() is DatabaseReplacedException)
    }

    @Test fun anImportThroughAClosedHandleDoesNotReopenTheFile() {
        val bytes = freshDatabase(context).useDb { exportBytes(it) }
        context.deleteDatabase(DatabaseFactory.NAME)
        val closed = DatabaseFactory.build(context)
        closed.close()
        context.deleteDatabase(DatabaseFactory.NAME)

        val outcome = runCatching { ImportJson.read(closed, ByteArrayInputStream(bytes)) }

        assertFalse(
            "ImportJson reopened the ledger through a closed handle, and answered $outcome",
            context.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
        assertTrue("the import was not refused: $outcome", outcome.exceptionOrNull() is DatabaseReplacedException)
    }
}
