package my.pinged.ledger.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.ledger.AppViewModelFactory
import my.pinged.ledger.transfer.ExportJson
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.useDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ExportJson`'s KDoc: "a prefix of a JSON object is not a JSON document ...
 * the caller must delete the SAF document". A half-written file looks exactly
 * like a backup until the day it is needed.
 */
@RunWith(AndroidJUnit4::class)
class ExportCleanupTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        DatabaseFactory.build(app).useDb { }
    }

    /**
     * A file URI rather than a document-provider one: the assertion is that the
     * caller removes what it wrote, and `DocumentFile`-backed deletion is the
     * platform's job, not this holder's.
     */
    @Test fun aFailedExportLeavesNoFileBehind() = runBlocking {
        val target = File(app.cacheDir, "half-written.json")
        val model = SettingsViewModel(app)

        model.exportTo(FailingSink(target)).join()

        assertFalse(
            "a truncated export was left on disk, where it reads as a backup",
            target.exists(),
        )
    }

    /**
     * The other way a write does not finish: nobody threw, the screen that
     * asked for this just went away. [Transfers.export] wires
     * `ExportJson.write`'s `isCancelled` to the operation's own liveness
     * rather than offering a cancel button (the export is sub-second at
     * realistic sizes), so cancelling the `Job` this returns has to reach the
     * same cleanup [aFailedExportLeavesNoFileBehind] exercises via a thrown
     * exception.
     *
     * Ten pages of `raw_capture` (`ExportJson.PAGE` each) so the writer is
     * still deep in the paged section, not finished, when the cancel lands.
     * The cancel itself is timed off [SettingsViewModel.state] rather than a
     * sleep: the first progress update past zero is the writer's proof that
     * it is inside the paged loop -- where `isCancelled` is actually
     * checked -- with the other nine pages still ahead of it, not a delay
     * calibrated to this device's speed and liable to stop meaning anything
     * on a faster or slower one.
     *
     * **Bytes at deletion, not just "the file is gone", because "gone" alone
     * does not prove `isCancelled` did anything.** `exportTo`'s general
     * `catch` deletes the sink whatever threw it, so a build that dropped the
     * `isCancelled` wiring entirely would still pass a file-absence-only
     * version of this test: the write would run to completion, then the
     * *next* suspend call after it (`TransferStore.recordExport`) would throw
     * on the now-cancelled job, land in the same catch, and delete a
     * fully-written file exactly as this test expects -- a test that passes
     * whether or not the fix under test exists. Comparing against
     * [exportBytes]'s uncancelled size for the same rows is what tells those
     * two apart.
     */
    @Test fun cancellingMidExportDeletesThePartialDocument() = runBlocking {
        val fullSize = DatabaseFactory.build(app).useDb { db ->
            db.runInTransaction {
                repeat(RAW_CAPTURE_PAGES) { page ->
                    db.rawCaptureDao().insertAll(
                        List(ExportJson.PAGE) { bulkCapture(page * ExportJson.PAGE + it) },
                    )
                }
            }
            exportBytes(db).size
        }
        Databases.reset()

        val target = File(app.cacheDir, "cancelled-mid-write.json")
        val sink = RecordingFileSink(target)
        val model = SettingsViewModel(app)
        val job = model.exportTo(sink)

        withTimeout(TIMEOUT) {
            model.state.first { (it.job as? TransferJob.Running)?.rows?.let { rows -> rows > 0 } == true }
        }
        job.cancel()
        job.join()

        assertFalse(
            "a cancelled export left its partial document on disk",
            target.exists(),
        )
        val atDeletion = sink.bytesAtDeletion
        assertNotNull("ExportSink.delete() was never called", atDeletion)
        assertTrue(
            "the file was $atDeletion of $fullSize uncancelled bytes when deleted -- " +
                "the write ran to completion instead of being stopped by isCancelled",
            atDeletion!! < fullSize / 2,
        )
    }

    /**
     * A different bug from the one [cancellingMidExportDeletesThePartialDocument]
     * covers. `CancellationException` descends `IllegalStateException ->
     * Exception` on the JVM, so a `catch (Exception)` written before -- or
     * without -- a `catch (CancellationException)` swallows cancellation into
     * a [TransferJob.Failed] instead of letting the coroutine finish
     * cancelled. [CancellingSink] throws that exception directly from
     * `open()`, which is the shape a cancelled suspend call takes -- as
     * opposed to [ExportJson]'s own `TransferCancelledException` from a
     * synchronous `isCancelled` check, which is a plain `RuntimeException`
     * the general catch was always meant to handle and which
     * [cancellingMidExportDeletesThePartialDocument] already goes through.
     *
     * [kotlinx.coroutines.Job.isCancelled] is what tells the two failure
     * modes apart: a coroutine whose body rethrows `CancellationException`
     * completes cancelled; one that swallows it into a handled `Failed`
     * state and returns normally completes *not* cancelled, indistinguishable
     * from success at the `Job` level.
     */
    @Test fun cancellationDuringTheWriteIsNotReportedAsAFailure() = runBlocking {
        val target = File(app.cacheDir, "never-written.json")
        val sink = CancellingSink(target)
        val model = SettingsViewModel(app)

        val job = model.exportTo(sink)
        job.join()

        assertTrue(
            "cancellation was swallowed into TransferJob.Failed instead of " +
                "propagating -- the Job completed as if it had not been cancelled",
            job.isCancelled,
        )
        assertTrue("ExportSink.delete() was not called on cancellation", sink.deleteCalled)
    }

    /**
     * **An export whose screen went away is abandoned, not failed.** Two
     * backs while the write runs clear the holder that asked for it, and
     * `ExportJson` answers its `isCancelled` with `TransferCancelledException`
     * -- a `RuntimeException`, not a cancellation. Filed as a failure, it
     * would be kept, and the next export sheet would draw "The file is
     * incomplete and must be deleted" in place of its button, about a file
     * already deleted.
     */
    @Test fun anExportWhoseScreenWentAwayLeavesNothingForTheNextScreenToReport() = runBlocking {
        val fullSize = aLedgerOfTenPages()
        val target = File(app.cacheDir, "abandoned-mid-write.json")
        val sink = RecordingFileSink(target)
        val store = ViewModelStore()
        val job = holder(store).exportTo(sink)

        withTimeout(TIMEOUT) { Transfers.state.first { (it.running?.rows ?: 0) > 0 } }
        store.clear()
        withTimeout(TIMEOUT) { job.join() }

        assertFalse("the abandoned export left its partial document on disk", target.exists())
        assertTrue(
            "precondition: the write was not stopped mid-way, so this is not the case under test",
            (sink.bytesAtDeletion ?: Long.MAX_VALUE) < fullSize / 2,
        )
        assertEquals(
            "an export abandoned by its screen was kept as a failure, which the next " +
                "export sheet draws in place of its button",
            null,
            Transfers.state.value.outcome,
        )
        assertEquals(TransferJob.Idle, SettingsViewModel(app).state.value.jobOf(Operation.EXPORT))
    }

    /**
     * **Unless its document would not go.** Abandoned or not, a truncated
     * file left where the user put it reads as a backup, and they are the
     * only one who can remove it now.
     */
    @Test fun anAbandonedExportWhoseDocumentWouldNotGoSaysSo() = runBlocking {
        aLedgerOfTenPages()
        val target = File(app.cacheDir, "abandoned-and-stuck.json")
        val sink = object : ExportSink {
            override fun open(): OutputStream = target.outputStream()
            override fun delete(): Boolean = false
        }
        val store = ViewModelStore()
        val job = holder(store).exportTo(sink)

        withTimeout(TIMEOUT) { Transfers.state.first { (it.running?.rows ?: 0) > 0 } }
        store.clear()
        withTimeout(TIMEOUT) { job.join() }
        target.delete()

        val kept = Transfers.state.value.outcome?.job
        assertTrue(
            "an abandoned export whose document could not be removed told nobody: $kept",
            kept is TransferJob.Failed && kept.message.contains("delete it by hand"),
        )
    }

    /** Ten pages of `raw_capture` in the app's own database; the uncancelled export's size. */
    private fun aLedgerOfTenPages(): Int {
        val fullSize = DatabaseFactory.build(app).useDb { db ->
            db.runInTransaction {
                repeat(RAW_CAPTURE_PAGES) { page ->
                    db.rawCaptureDao().insertAll(
                        List(ExportJson.PAGE) { bulkCapture(page * ExportJson.PAGE + it) },
                    )
                }
            }
            exportBytes(db).size
        }
        Databases.reset()
        return fullSize
    }

    /** A holder in [store], as `MainActivity`'s nav entry keeps one. */
    private fun holder(store: ViewModelStore): SettingsViewModel =
        ViewModelProvider(store, AppViewModelFactory(app, ::SettingsViewModel))[SettingsViewModel::class.java]

    private companion object {
        private const val RAW_CAPTURE_PAGES = 10
        private const val TIMEOUT = 10_000L
    }
}

/**
 * An [ExportSink] whose stream throws after its first 100 bytes, so
 * [SettingsViewModel.exportTo] always lands in its general `catch` -- and
 * whose [delete] does what [ExportSink.delete]'s contract requires, so
 * [ExportCleanupTest.aFailedExportLeavesNoFileBehind] is a test of the
 * cleanup, not of this fake.
 */
private class FailingSink(private val target: File) : ExportSink {
    override fun open(): OutputStream {
        target.createNewFile()
        val real = target.outputStream()
        return object : FilterOutputStream(real) {
            private var written = 0

            override fun write(b: Int) {
                if (written++ >= 100) throw IOException("simulated failure after 100 bytes")
                super.write(b)
            }
        }
    }

    override fun delete(): Boolean = target.delete()
}

/**
 * A real, non-throwing file write -- unlike [FailingSink], nothing here
 * fails on its own. [cancellingMidExportDeletesThePartialDocument] is a test
 * of cancellation reaching the same [ExportSink.delete] contract, not of a
 * write that fails by itself, so this fake has no way to.
 *
 * [bytesAtDeletion] is read by that test before the file is gone -- proof of
 * how much of it existed at the moment cleanup ran, which "the file does not
 * exist afterward" alone cannot distinguish from a write that finished first.
 */
private class RecordingFileSink(private val target: File) : ExportSink {
    var bytesAtDeletion: Long? = null
        private set

    override fun open(): OutputStream = target.outputStream()

    override fun delete(): Boolean {
        bytesAtDeletion = target.length()
        return target.delete()
    }
}

/**
 * `open()` throws `CancellationException` directly, standing in for a
 * cancelled suspend call inside the write -- distinct from [ExportJson]'s own
 * `TransferCancelledException`, which is a plain `RuntimeException` and
 * already reaches the general catch. Never actually opens [target], so
 * [ExportSink.delete] deleting a file that was never created is exactly what
 * [DocumentSink.delete]'s `runCatching` around a provider that has nothing to
 * remove is for.
 */
private class CancellingSink(private val target: File) : ExportSink {
    var deleteCalled: Boolean = false
        private set

    override fun open(): OutputStream = throw CancellationException("simulated mid-write cancellation")

    override fun delete(): Boolean {
        deleteCalled = true
        return target.delete()
    }
}
