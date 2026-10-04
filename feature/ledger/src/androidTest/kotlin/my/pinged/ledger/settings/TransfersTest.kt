package my.pinged.ledger.settings

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.AppViewModelFactory
import my.pinged.ledger.transfer.TransferStore
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ledger.transfer.exportBytes
import my.pinged.ledger.transfer.freshDatabase
import my.pinged.ledger.transfer.seedOneOfEverything
import my.pinged.ledger.transfer.useDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ruling R41: a transfer's lifetime is the process, so its serialisation and
 * its result are too. Every case here is two settings screens -- two
 * [SettingsViewModel]s in [ViewModelStore]s the test clears, which is what
 * `MainActivity`'s nav-entry store does when the user backs out -- and an
 * operation the first one started that outlives it.
 *
 * [HostileApp.atResolution] holds a restore at an exact point. The third
 * resolution of the app's database in a restore is SQLCipher opening the file
 * for the restore's own first query: the wipe has run and the new key has
 * been minted, and nothing has been written under it yet.
 */
@RunWith(AndroidJUnit4::class)
class TransfersTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private val stores = mutableListOf<ViewModelStore>()
    private val releases = mutableListOf<CountDownLatch>()

    @Before fun startClean() = runBlocking {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        TransferStore.forget(app)
        IntegrityStore.forget(app)
    }

    @After fun letEverythingGo() = runBlocking {
        releases.forEach { it.countDown() }
        stores.forEach { it.clear() }
        // A read already under way is not stopped by its holder's clearing;
        // taking the turn waits it out, so the reset below cannot close the
        // database under it.
        withTimeout(TIMEOUT) { Transfers.reading { } }
        IntegrityStore.forget(app)
        Databases.reset()
    }

    /**
     * **A delete that has not started when its screen goes away does not
     * start.** Queued behind a read that is still running, then abandoned:
     * `Wipe.everything` has no suspension point before `DatabaseKey.destroy`,
     * so a delete that dequeues into a cancelled job destroys the ledger
     * before anything notices the cancellation.
     */
    @Test fun aDeleteWhoseScreenWentAwayBeforeItBeganDestroysNothing() = runBlocking {
        aLiveLedgerOfOneCapture()
        val (model, store) = screen()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        model.beforeEachOperation = {
            if (first.compareAndSet(true, false)) {
                reached.complete(Unit)
                release.await()
            }
        }

        model.load()
        withTimeout(TIMEOUT) { reached.await() }
        val delete = model.deleteEverything()
        Thread.sleep(QUEUE_BUDGET)
        store.clear()
        release.complete(Unit)
        withTimeout(TIMEOUT) { delete.join() }
        Thread.sleep(ORPHAN_BUDGET)

        assertEquals(
            "A delete confirmed on a screen that went away before the delete began " +
                "destroyed the ledger anyway",
            Result.success(1),
            runCatching { ledgerCaptures() },
        )
    }

    /**
     * **A delete whose screen goes away in the instant it takes its turn
     * destroys nothing either.** The turn is taken without suspending when it
     * is free, and a `Mutex` taken that way does not look at cancellation --
     * so nothing but `perform`'s own check stands between that instant and a
     * body that runs under `NonCancellable`. The hook cancels the requester
     * there, after the turn and before the body.
     */
    @Test fun aDeleteWhoseScreenWentAwayAsItTookItsTurnDestroysNothing() = runBlocking {
        aLiveLedgerOfOneCapture()
        val (model, _) = screen()
        val requested = CompletableDeferred<Job>()
        model.beforeEachOperation = { requested.await().cancel() }

        requested.complete(model.deleteEverything())
        withTimeout(TIMEOUT) { requested.await().join() }

        assertEquals(
            "A delete whose screen went away as it took its turn destroyed the ledger",
            Result.success(1),
            runCatching { ledgerCaptures() },
        )
    }

    /**
     * **An operation that never started still lets go of what it was
     * handed, and a `join` on it waits until it has.** The picker has
     * already created the export's document; a request whose screen goes
     * away while it waits for its turn never runs the body that would
     * delete it. The provider is slow here, so a `join` that returned on the
     * abandonment alone -- with the delete still under way on another
     * thread -- is seen to.
     */
    @Test fun anExportThatNeverStartedDeletesItsDocument() = runBlocking {
        val removed = AtomicBoolean(false)
        val sink = object : ExportSink {
            override fun open(): java.io.OutputStream = error("an export whose screen went away opened its document")
            override fun delete(): Boolean {
                Thread.sleep(SLOW_PROVIDER)
                removed.set(true)
                return true
            }
        }
        abandonWhileQueued { model -> model.exportTo(sink) }

        assertTrue(
            "The join on an export whose screen went away before its turn returned with the " +
                "document the picker created not yet deleted",
            removed.get(),
        )
    }

    /**
     * The restore's half of [anExportThatNeverStartedDeletesItsDocument]:
     * its document is opened only once the restore has its turn, so one
     * whose screen went away before then has no stream to let go of.
     */
    @Test fun aRestoreThatNeverStartedNeverOpensItsDocument() = runBlocking {
        val opened = AtomicBoolean(false)
        abandonWhileQueued { model ->
            model.restoreFrom {
                opened.set(true)
                ByteArrayInputStream(ByteArray(0))
            }
        }

        assertFalse(
            "A restore whose screen went away before its turn opened the document it would never read",
            opened.get(),
        )
    }

    /**
     * [request] made while a read holds the turn, and its screen cleared
     * before the read lets go.
     */
    private suspend fun abandonWhileQueued(request: (SettingsViewModel) -> Job) {
        aLiveLedgerOfOneCapture()
        val (model, store) = screen()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        model.beforeEachOperation = {
            if (first.compareAndSet(true, false)) {
                reached.complete(Unit)
                release.await()
            }
        }
        model.load()
        withTimeout(TIMEOUT) { reached.await() }
        val job = request(model)
        Thread.sleep(QUEUE_BUDGET)
        store.clear()
        release.complete(Unit)
        withTimeout(TIMEOUT) { job.join() }
    }

    /**
     * **A delete whose screen goes away once it has begun finishes, and says
     * so.** Stopped half way it would leave the ledger unlinked and the
     * verdict about it on disk, with nothing asking for the second delete
     * that clears it. The screen goes at `DatabaseKey.destroy`'s own
     * resolution of the file, with the ledger already unlinked; the verdict
     * makes the wipe's forget a suspension point a cancellation would surface
     * at.
     */
    @Test fun aDeleteWhoseScreenWentAwayOnceItHadBegunFinishesAndSaysSo() = runBlocking {
        aLiveLedgerOfOneCapture()
        IntegrityStore.record(app, at = 1L, ok = false)
        val requested = CompletableDeferred<Job>()
        val hostile = HostileApp(app).apply {
            atResolution = { n -> if (n == 1) runBlocking { requested.await().cancel() } }
        }
        val (model, _) = screen(hostile)
        requested.complete(model.deleteEverything())
        withTimeout(TIMEOUT) { requested.await().join() }
        val (next, _) = screen()

        assertEquals(
            "A delete whose screen went away after the wipe had begun stopped there: " +
                "the verdict about the ledger it destroyed is still on disk",
            0L,
            IntegrityStore.lastCheckAt(app),
        )
        assertEquals(
            "A delete whose screen went away after the wipe had begun reported nothing " +
                "to the next screen",
            TransferJob.Deleted,
            next.state.value.outcome?.job,
        )
    }

    /**
     * **No two destructive operations overlap, whichever screens started
     * them.** The reviewer's sequence: back out mid-restore, come straight
     * back in, confirm Delete everything, back out again. The restore is held
     * after it has minted the new key, which is where a destroy landing
     * beside it costs the most -- the import succeeds under a key whose file
     * the delete removed, and the restored ledger never opens again.
     */
    @Test fun aDeleteConfirmedDuringAnotherScreensRestoreNeverRunsBesideIt() = runBlocking {
        val (bytes, expected) = aBackup()
        aLiveLedgerOfOneCapture()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val hostile = HostileApp(app).apply {
            atResolution = { n ->
                if (n == 3) {
                    held.countDown()
                    release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                }
            }
        }
        val (restoring, restoringStore) = screen(hostile)
        val before = Databases.generation.value
        restoring.restoreFrom { ByteArrayInputStream(bytes) }
        assertTrue("precondition: the restore never reached its own open", held.await(TIMEOUT, TimeUnit.MILLISECONDS))
        restoringStore.clear()

        val (second, secondStore) = screen()
        second.load()
        val delete = second.deleteEverything()
        Thread.sleep(QUEUE_BUDGET)
        secondStore.clear()
        withTimeout(TIMEOUT) { delete.join() }
        Thread.sleep(ORPHAN_BUDGET)
        release.countDown()
        withTimeout(TIMEOUT) { Databases.generation.first { it > before } }

        assertEquals(
            "A delete confirmed while another screen's restore was running ran beside " +
                "it, and the restored ledger does not open with the backup's rows",
            Result.success(expected),
            runCatching { ledgerCaptures() },
        )
    }

    /**
     * **A restore's result outlives the screen that started it.** The one it
     * matters most for: the wipe ran and the rebuild failed, so the ledger is
     * gone and "restoring it again is safe, and is how to recover" is the
     * only way back. Written to a cleared holder, it reached nobody, and the
     * next settings screen drew an ordinary empty ledger.
     *
     * The destroy is failed at its own resolution of the database path, with
     * the file already unlinked -- where `RestoreLedgerLostException` is a
     * true claim rather than a cautious one.
     */
    @Test fun aRestoreThatLostTheLedgerSaysSoOnTheNextScreen() = runBlocking {
        val (bytes, _) = aBackup()
        aLiveLedgerOfOneCapture()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val hostile = HostileApp(app)
        hostile.atResolution = { n ->
            if (n == 1) {
                held.countDown()
                release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                hostile.refusing = true
            }
        }
        val (restoring, restoringStore) = screen(hostile)
        val before = Databases.generation.value
        restoring.restoreFrom { ByteArrayInputStream(bytes) }
        assertTrue("precondition: the restore never reached its wipe", held.await(TIMEOUT, TimeUnit.MILLISECONDS))
        restoringStore.clear()
        release.countDown()
        withTimeout(TIMEOUT) { Databases.generation.first { it > before } }

        val (next, _) = screen()
        next.load()
        val shown = withTimeoutOrNull(TIMEOUT) {
            next.state.first { it.job is TransferJob.Failed }
        }?.job as? TransferJob.Failed

        assertTrue(
            "A restore that lost the ledger after its screen went away told nobody: " +
                "the next settings screen shows ${next.state.value}",
            shown?.ledgerLost == true,
        )
    }

    /**
     * **A screen opened while another's restore runs says so, and publishes
     * nothing about the ledger until it has finished.** Not an ordinary
     * HEALTHY screen whose taps queue invisibly behind the restore -- and not
     * [Storage.UNREADABLE] either, whose "Nothing has been deleted" is false
     * of a ledger a restore has just wiped. Once the restore is done, the
     * same screen shows the ledger it wrote.
     */
    @Test fun aScreenOpenedDuringAnotherScreensRestoreShowsItRunning() = runBlocking {
        val (bytes, expected) = aBackup()
        aLiveLedgerOfOneCapture()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val hostile = HostileApp(app).apply {
            atResolution = { n ->
                if (n == 3) {
                    held.countDown()
                    release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                }
            }
        }
        val (restoring, restoringStore) = screen(hostile)
        val before = Databases.generation.value
        restoring.restoreFrom { ByteArrayInputStream(bytes) }
        assertTrue("precondition: the restore never reached its own open", held.await(TIMEOUT, TimeUnit.MILLISECONDS))
        restoringStore.clear()

        val (second, _) = screen()
        second.load()
        val during = withTimeoutOrNull(RUNNING_BUDGET) {
            second.state.first { it.job is TransferJob.Running }
        }
        Thread.sleep(RUNNING_BUDGET)
        val stillDuring = second.state.value
        release.countDown()
        withTimeout(TIMEOUT) { Databases.generation.first { it > before } }
        val after = withTimeoutOrNull(TIMEOUT) {
            second.state.first { it.loaded && it.job !is TransferJob.Running }
        }

        assertEquals(
            "A settings screen opened while another screen's restore ran did not say " +
                "a restore was running: it drew ${second.state.value}",
            "Restoring",
            (during?.job as? TransferJob.Running)?.operation?.doing,
        )
        assertFalse(
            "The screen published a reading of the ledger while the restore still " +
                "had it half written: $stillDuring",
            stillDuring.loaded,
        )
        assertEquals(
            "Once the restore finished, the screen did not show the ledger it wrote: $after",
            expected,
            after?.wipeCounts?.captures,
        )
    }

    /**
     * **A request made while another operation runs is refused, and touches
     * nothing.** Refused rather than queued: each was chosen against a screen
     * the running one is about to change. The export's document -- which the
     * picker has already created -- is removed, because nothing is going to
     * be written into it; the check never runs, so nothing is recorded.
     */
    @Test fun aRequestWhileAnotherRunsIsRefusedAndChangesNothing() = runBlocking {
        val (bytes, _) = aBackup()
        aLiveLedgerOfOneCapture()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        val hostile = HostileApp(app).apply {
            atResolution = { n ->
                if (n == 3) {
                    held.countDown()
                    release.await(TIMEOUT, TimeUnit.MILLISECONDS)
                }
            }
        }
        val (restoring, _) = screen(hostile)
        val before = Databases.generation.value
        restoring.restoreFrom { ByteArrayInputStream(bytes) }
        assertTrue("precondition: the restore never reached its own open", held.await(TIMEOUT, TimeUnit.MILLISECONDS))

        val (second, _) = screen()
        val removed = AtomicBoolean(false)
        val sink = object : ExportSink {
            override fun open(): java.io.OutputStream = error("a refused export opened its document")
            override fun delete(): Boolean {
                Thread.sleep(SLOW_PROVIDER)
                removed.set(true)
                return true
            }
        }
        val checkAnswered = withTimeoutOrNull(RUNNING_BUDGET) { second.check().join() } != null
        val exportAnswered = withTimeoutOrNull(RUNNING_BUDGET) {
            second.exportTo(sink).join()
        } != null
        val removedByTheJoin = removed.get()
        val drawn = second.state.value.job
        release.countDown()
        withTimeout(TIMEOUT) { Databases.generation.first { it > before } }
        // Behind anything that was queued rather than refused, so its effects
        // are on disk before they are looked for.
        withTimeout(TIMEOUT) { Transfers.reading { } }

        assertTrue("A check asked for during a restore waited for it rather than being refused", checkAnswered)
        assertEquals(
            "A check asked for during a restore ran: it recorded a verdict",
            0L,
            IntegrityStore.lastCheckAt(app),
        )
        assertTrue("An export asked for during a restore waited for it rather than being refused", exportAnswered)
        assertTrue(
            "The join on an export refused during a restore returned with its document not " +
                "yet removed",
            removedByTheJoin,
        )
        assertEquals(
            "The screen stopped showing the restore that refused it",
            "Restoring",
            (drawn as? TransferJob.Running)?.operation?.doing,
        )
    }

    private fun screen(application: Application = app): Pair<SettingsViewModel, ViewModelStore> {
        val store = ViewModelStore().also(stores::add)
        val model = ViewModelProvider(store, AppViewModelFactory(application, ::SettingsViewModel))[
            SettingsViewModel::class.java,
        ]
        return model to store
    }

    /** A backup of one of everything, and how many captures it carries. */
    private fun aBackup(): Pair<ByteArray, Int> {
        val source = freshDatabase(app)
        val captures = seedOneOfEverything(source).captures.size
        return (exportBytes(source) to captures).also { source.close() }
    }

    /** The app's own database, holding one capture, and not held open. */
    private fun aLiveLedgerOfOneCapture() {
        freshDatabase(app).useDb { it.rawCaptureDao().insert(bulkCapture(0)) }
        Databases.reset()
    }

    /** Captures in the app's own database, read the way the next open reads them. */
    private fun ledgerCaptures(): Int {
        Databases.reset()
        return DatabaseFactory.build(app).useDb { it.rawCaptureDao().countAll() }
    }

    private companion object {
        const val TIMEOUT = 10_000L

        // A confirmed delete is given this long to take its place in the queue
        // before its screen goes away: the user's own gap between confirming
        // and backing out is longer, and a request cleared before its
        // coroutine has even started never reaches the queue at all.
        const val QUEUE_BUDGET = 500L

        // A negative is asserted after this: long enough for an operation that
        // was going to run beside another to have run, measured against a
        // destroy that takes single milliseconds.
        const val ORPHAN_BUDGET = 1_000L

        // How long a second screen is given to show the restore it opened
        // over, and then how long it is watched for publishing early.
        const val RUNNING_BUDGET = 1_500L

        // A document provider slow to delete, or a stream slow to close:
        // longer than a join returning without them takes, and well inside
        // RUNNING_BUDGET.
        const val SLOW_PROVIDER = 300L
    }
}
