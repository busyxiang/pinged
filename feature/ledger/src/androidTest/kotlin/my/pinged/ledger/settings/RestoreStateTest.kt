package my.pinged.ledger.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ledger.transfer.useDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refused import must say nothing was imported, and be telling the truth:
 * `ImportJson` reads inside one transaction, so a failure rolls back.
 */
@RunWith(AndroidJUnit4::class)
class RestoreStateTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
    }

    @Test fun aFileThatIsNotABackupChangesNothing() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            db.rawCaptureDao().insert(bulkCapture(0))
        }
        val model = SettingsViewModel(app)

        model.restoreFrom { ByteArrayInputStream("{\"not\":\"a backup\"}".toByteArray()) }.join()

        val job = model.state.value.job
        assertTrue("the failure was not reported: $job", job is TransferJob.Failed)
        assertTrue(
            "the message does not say nothing was imported",
            (job as TransferJob.Failed).message.contains("Nothing was imported"),
        )
        Databases.reset()
        DatabaseFactory.build(app).useDb { db ->
            assertEquals("the ledger was damaged by a refused import", 1, db.rawCaptureDao().countAll())
        }
    }
}
