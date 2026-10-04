package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.Restore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A notification arriving while a restore writes the ledger.
 *
 * In this module because `CaptureHealth`, where the drop is recorded, is
 * `internal` to it -- the direction `RestoredBacklogTest` explains.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDuringRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * **Refused, and the refusal outlives the restore.** A capture in that
     * window is dropped -- as it would have been anyway, by default-deny on
     * the empty file -- and `CaptureStorage.guarded` records it, so the
     * storage banner says capture stopped until the next open that succeeds.
     * That record is in the store the restore clears, so a restore clearing
     * it *after* its import would erase every drop the import caused, and the
     * drop would be silent after all.
     *
     * What this pins is that order, not the gate. Made from `isCancelled`,
     * which `ImportJson` asks after every row, the capture meets the import's
     * open write transaction, and measured on emulator-5554 an open there is
     * refused even without the gate: the new instance's seed cannot take the
     * lock, and arrives as `DatabaseUnreadableException`. `RestoreTest` pins
     * the gate itself, at the import and before it.
     */
    @Test fun aCaptureDuringARestoreIsRefusedAndTheRefusalIsKept() = runBlocking {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        val bytes = DatabaseFactory.build(context).let { source ->
            ByteArrayOutputStream().also { ExportJson.write(source, it) }.toByteArray()
                .also { source.close() }
        }
        CaptureCaches.clear(context)

        var stored: Boolean? = null
        val unavailableAfter = try {
            Restore.replaceEverything(
                context,
                ByteArrayInputStream(bytes),
                isCancelled = {
                    if (stored == null) {
                        stored = runBlocking {
                            CaptureStorage.guarded(context, "a capture, for the test", { false }) { true }
                        }
                    }
                    false
                },
            )
            CaptureHealth.storageUnavailable(context)
        } finally {
            CaptureCaches.clear(context)
            Databases.reset()
        }

        assertEquals(
            "A capture made while the restore was writing the ledger was stored",
            false,
            stored,
        )
        assertTrue(
            "The capture refused during the restore left no record once the " +
                "restore had finished, so the drop is silent",
            unavailableAfter,
        )
    }
}
