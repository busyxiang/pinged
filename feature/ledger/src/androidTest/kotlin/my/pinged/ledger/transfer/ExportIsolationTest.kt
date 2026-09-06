package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import my.pinged.data.PingedDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An export taken while capture is running has to be a file this app will
 * accept back.
 *
 * As six independent statements with no transaction, a capture committed
 * between the `raw_captures` section closing and `txns` being read produced a
 * document whose `txn.raw_capture_id` named a capture the document did not
 * contain -- and `verifyReferences` refuses the **whole file** for one dangling
 * reference, while the export reported success either way.
 *
 * What is asserted is the mechanism rather than the race, because the race is
 * not deterministic and the mechanism is: while the export is open, a writer on
 * another thread cannot commit. Room 2.8 passes a single connection through to
 * SQLCipher, so the transaction serialises every other caller for its duration.
 */
@RunWith(AndroidJUnit4::class)
class ExportIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    private fun fresh(): PingedDatabase {
        db?.close()
        db = null
        return freshDatabase(context).also { db = it }
    }

    @Test fun aWriterCannotCommitWhileTheExportIsOpen() {
        val source = fresh()
        seedOneOfEverything(source)
        repeat(600) { source.rawCaptureDao().insert(bulkCapture(it)) }

        val exportStarted = CountDownLatch(1)
        val writerCommitted = CountDownLatch(1)

        // The writer records *when* it finished rather than whether it finished
        // inside some window. See the note above the assertion.
        val writerFinishedAt = java.util.concurrent.atomic.AtomicLong(0)
        val writer = Thread {
            exportStarted.await(10, TimeUnit.SECONDS)
            source.rawCaptureDao().insert(bulkCapture(7_000_001))
            writerFinishedAt.set(System.nanoTime())
            writerCommitted.countDown()
        }
        writer.start()

        val bytes = ByteArrayOutputStream()
        ExportJson.write(source, bytes, onProgress = { exportStarted.countDown() })
        val exportEndedAt = System.nanoTime()

        writer.join(30_000)
        assertTrue("the writer never ran at all", writerCommitted.count == 0L)

        // **Two timestamps, and no timeout anywhere in the assertion.**
        //
        // A latched `writerCommitted.await(1, SECONDS)` inside the progress callback is
        // a race, not a mechanism: a *reverted* build whose writer simply took longer
        // than a second to land -- a cold emulator, a first Room write on that thread --
        // would go green.
        //
        // The ordering is the property and needs no clock budget: if the export's
        // transaction serialises the connection, the writer cannot finish before the
        // export does. Both timestamps stretch together on a slow machine.
        assertTrue(
            "The writer committed ${(exportEndedAt - writerFinishedAt.get()) / 1_000_000}ms " +
                "before the export finished, so a capture landed in the middle of " +
                "it and the file can carry a transaction whose raw_capture is not " +
                "in it",
            writerFinishedAt.get() >= exportEndedAt,
        )

        // And the file the export produced is one this app accepts back.
        val target = fresh()
        ImportJson.read(target, ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(
            "Every transaction in the file must name a capture the file carries",
            0,
            target.txnDao().danglingRawCaptureIdCount(),
        )
    }
}
