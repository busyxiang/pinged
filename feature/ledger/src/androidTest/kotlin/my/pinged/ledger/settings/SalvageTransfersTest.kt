package my.pinged.ledger.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.damagedLedger
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.ledger.transfer.useDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Salvage as `Transfers`' fifth operation: on instances of its own, one at a
 * time with the other four, and letting go of its document on every way out
 * but a finished one.
 */
@RunWith(AndroidJUnit4::class)
class SalvageTransfersTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val releases = mutableListOf<CountDownLatch>()

    @Before fun startClean() {
        Transfers.forgetOutcome()
        app.discardTheDatabase()
    }

    @After fun letEverythingGo() = runBlocking<Unit> {
        releases.forEach { it.countDown() }
        withTimeout(TIMEOUT) { Transfers.reading { } }
        app.discardTheDatabase()
        Transfers.forgetOutcome()
    }

    /**
     * **Constraint 1: salvage never reads through `Databases.shared`.** Every
     * damaged read poisons the connection it lands on, so a salvage there
     * would leave the listener's next insert answering code 26. The shared
     * instance the listener holds is the same one afterwards, and stores.
     */
    @Test(timeout = 60_000)
    fun aSalvageLeavesTheSharedInstanceStoringCaptures() = runBlocking<Unit> {
        app.damagedLedger()
        val shared = Databases.shared(app)
        val target = File(app.cacheDir, "rescued.json").apply { delete() }

        val answer = Transfers.salvage(app, FileSink(target), Job()).await()

        val stored = runCatching { Databases.leasing { shared.rawCaptureDao().insert(bulkCapture(CAPTURE_AFTER)) } }
        assertNull("a capture right after the salvage was not stored: ${stored.exceptionOrNull()}", stored.exceptionOrNull())
        assertSame("the salvage replaced the shared instance", shared, Databases.shared(app))
        val job = (answer as? Transfers.Answer.Settled)?.outcome?.job
        assertTrue(
            "the salvage did not meet the damage, so this test proves nothing: $job",
            (job as? TransferJob.Salvaged)?.report?.unreadable?.containsKey("raw_capture") == true,
        )
        assertEquals("a finished salvage did not keep its outcome", job, Transfers.state.value.outcome?.job)
        assertTrue("a finished salvage's file is not there", target.length() > 0)
        target.delete()
    }

    /** Constraint 2: refused, not queued, while another operation runs; its document let go of. */
    @Test(timeout = 60_000)
    fun aSalvageAskedForWhileAnotherRunsIsRefusedAndRemovesItsDocument() = runBlocking<Unit> {
        app.damagedLedger()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val check = Transfers.check(app, Job(), beforeStart = {
            held.countDown()
            release.await(TIMEOUT, TimeUnit.MILLISECONDS)
        })
        assertTrue("precondition: the check never took its turn", held.await(TIMEOUT, TimeUnit.MILLISECONDS))
        val opened = AtomicBoolean(false)
        val removed = AtomicBoolean(false)
        val sink = object : ExportSink {
            override fun open(): OutputStream {
                opened.set(true)
                error("a refused salvage opened its document")
            }
            override fun delete(): Boolean = true.also { removed.set(true) }
        }

        val answer = withTimeout(TIMEOUT) { Transfers.salvage(app, sink, Job()).await() }
        release.countDown()
        check.await()

        assertEquals("a salvage during a check was not refused", Transfers.Answer.Refused(Operation.CHECK), answer)
        assertFalse("a refused salvage opened its document", opened.get())
        assertTrue("a refused salvage left its document behind", removed.get())
    }

    /**
     * **Refused under a gate**, which `Databases.openAside` refuses: a salvage
     * cannot open the file a delete or a restore is replacing. Unreachable
     * through `Transfers`, whose turn the only raisers of a gate hold, so
     * raised here directly.
     */
    @Test(timeout = 60_000)
    fun aSalvageUnderAGateOpensNothingAndRemovesItsDocument() = runBlocking<Unit> {
        app.damagedLedger()
        val up = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val gate = thread { Databases.whileDeleting { up.countDown(); release.await(TIMEOUT, TimeUnit.MILLISECONDS) } }
        assertTrue("precondition: the gate never rose", up.await(TIMEOUT, TimeUnit.MILLISECONDS))
        val target = File(app.cacheDir, "gated.json").apply { delete() }

        val answer = try {
            Transfers.salvage(app, FileSink(target), Job()).await()
        } finally {
            release.countDown()
            gate.join()
        }

        val job = (answer as? Transfers.Answer.Settled)?.outcome?.job
        assertTrue(
            "a salvage under a gate was not refused for it: $job",
            job is TransferJob.Failed && job.message.startsWith("Pinged could not open its database to rescue it"),
        )
        assertFalse("a refused salvage left its document behind", target.exists())
    }

    /**
     * Constraint 6: **cancelled mid-walk, it deletes its document and keeps no
     * outcome.** A damaged root is read a row and a reopen at a time, about
     * 2.4 ms each (`RowReader`), so 2,000 rows is seconds of walk
     * still ahead when the cancel lands. A salvage that did not ask
     * `isCancelled` would run to the end and settle `Salvaged`, its file
     * kept.
     */
    @Test(timeout = 60_000)
    fun aSalvageCancelledMidWalkDeletesItsDocumentAndKeepsNoOutcome() = runBlocking<Unit> {
        aLedgerWithADamagedRoot(captures = 2_000)
        val target = File(app.cacheDir, "cancelled-rescue.json").apply { delete() }
        val deletedAt = AtomicBoolean(false)
        val sink = object : ExportSink {
            override fun open(): OutputStream = target.outputStream()
            override fun delete(): Boolean = target.delete().also { deletedAt.set(true) }
        }
        val model = SettingsViewModel(app)

        val job = model.salvageTo(sink)
        withTimeout(TIMEOUT) { Transfers.state.first { (it.running?.rows ?: 0) > STEPS_BEFORE_CANCEL } }
        val cancelledAt = System.nanoTime()
        job.cancel()
        job.join()
        val tookMs = (System.nanoTime() - cancelledAt) / 1_000_000

        assertTrue("a cancelled salvage did not delete its document", deletedAt.get())
        assertFalse("a cancelled salvage left its document on disk", target.exists())
        assertNull("a cancelled salvage kept an outcome: ${Transfers.state.value.outcome}", Transfers.state.value.outcome)
        assertTrue("the cancel took $tookMs ms to stop the walk", tookMs < CANCEL_BOUND_MS)
    }

    /** [captures] captures in the app's database, and the byte flipped in `raw_capture`'s root. */
    private fun aLedgerWithADamagedRoot(captures: Int) {
        val root = freshDatabase(app).useDb { db ->
            db.runInTransaction {
                (0 until captures).chunked(ExportJson.PAGE).forEach { chunk -> db.rawCaptureDao().insertAll(chunk.map(::bulkCapture)) }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            db.openHelper.readableDatabase
                .query("SELECT pageno, pagetype FROM dbstat WHERE name = 'raw_capture' AND path = '/'")
                .use { c ->
                    c.moveToFirst()
                    assertEquals("the root is a leaf, so a read past it is not a walk", "internal", c.getString(1))
                    c.getInt(0)
                }
        }
        Databases.reset()
        RandomAccessFile(app.getDatabasePath(DatabaseFactory.NAME), "rw").use { raf ->
            val at = (root - 1).toLong() * 4096 + 1000
            raf.seek(at)
            val original = raf.readByte()
            raf.seek(at)
            raf.writeByte(original.toInt() xor 0xFF)
        }
    }

    private class FileSink(private val target: File) : ExportSink {
        override fun open(): OutputStream = target.outputStream()
        override fun delete(): Boolean = target.delete()
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val CAPTURE_AFTER = 1_000_000

        // Into the walk, with most of it ahead: 100 steps of 2,000.
        const val STEPS_BEFORE_CANCEL = 100

        // One read and one reopen is 2.4 ms; the rest of the walk is seconds.
        const val CANCEL_BOUND_MS = 1_000L
    }
}
