package my.pinged.capture

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.RawCapture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared instance replaced while another caller is still using it: what
 * `CaptureStorage.guarded` does when it meets a connection damage has
 * poisoned, with the listener, stage two and the screens all reading and
 * writing beside it.
 *
 * Measured on emulator-5554 with the replacement closing the instance at
 * once: a capture's transaction failed between its `BEGIN` and its `END`,
 * the connection kept its write lock, and every open of `pinged.db` after it
 * failed with code 5 for the life of the process; guarded readers holding the
 * instance failed with code 21 or "the connection pool has been closed". Each
 * test here makes one of those interleavings happen on purpose.
 */
@RunWith(AndroidJUnit4::class)
class SharedInstanceUnderUseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startClean() = runBlocking<Unit> {
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        CaptureCaches.clear(context)
        IntegrityStore.forget(context)
        CaptureFixtures.cancelStageTwo(context)
        ParseFixtures.prepare(context)
    }

    @After fun leaveAnOpenableDatabase() = runBlocking<Unit> {
        CaptureFixtures.cancelStageTwo(context)
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        CaptureCaches.clear(context)
        IntegrityStore.forget(context)
    }

    /**
     * **A transaction in flight when its instance is replaced finishes, and
     * leaves the file writable.** The capture's `@Transaction` in
     * `CaptureDays` against a guarded read meeting code 26 beside it, with
     * the replacement landing between the transaction's `BEGIN` and its
     * statement.
     */
    @Test(timeout = 60_000)
    fun aTransactionInFlightWhenItsInstanceIsReplacedFinishesAndLeavesTheFileWritable() = runBlocking<Unit> {
        val shared = Databases.shared(context)
        val inTransaction = CountDownLatch(1)
        val replaced = CountDownLatch(1)

        val capture = async(Dispatchers.IO) {
            runCatching {
                CaptureStorage.guarded(context, "the capture", unavailable = { -1 }) {
                    shared.runInTransaction<Int> {
                        inTransaction.countDown()
                        check(replaced.await(10, TimeUnit.SECONDS)) { "the replacement never came" }
                        shared.captureSourceDao().all().size
                    }
                }
            }
        }
        assertTrue("the capture never began its transaction", inTransaction.await(10, TimeUnit.SECONDS))
        // The read that met code 26, replacing the instance and finishing
        // while the capture is still inside its transaction.
        CaptureStorage.guarded(context, "the read beside it", { Unit }, damageStopsCapture = false) {
            Databases.replacePoisoned(shared)
        }
        replaced.countDown()
        val outcome = capture.await()

        val opened = runCatching {
            DatabaseFactory.build(context).let { own ->
                try { own.captureSourceDao().setEnabled(ParseFixtures.TNG, true) } finally { own.close() }
            }
        }
        assertNull(
            "pinged.db would not open and take a write after the replacement: " +
                "${opened.exceptionOrNull()} (cause ${opened.exceptionOrNull()?.cause})",
            opened.exceptionOrNull(),
        )
        assertNull(
            "the transaction threw ${outcome.exceptionOrNull()} once its instance was replaced",
            outcome.exceptionOrNull(),
        )
        assertTrue("the transaction read nothing", (outcome.getOrNull() ?: -1) > 0)
    }

    /**
     * **A capture's lease is what keeps its instance open**, whoever else is
     * reading: here the replacement is made under a lease of its own that
     * ends while the capture is inside its transaction -- the ledger feed's
     * page, or settings' counts -- and nothing but the capture's lease is
     * left up.
     */
    @Test(timeout = 60_000)
    fun aCapturesOwnLeaseKeepsItsInstanceOpenWhenAnotherLeaseEnds() = runBlocking<Unit> {
        val shared = Databases.shared(context)
        val inTransaction = CountDownLatch(1)
        val replaced = CountDownLatch(1)

        val capture = async(Dispatchers.IO) {
            runCatching {
                CaptureStorage.guarded(context, "the capture", unavailable = { -1 }) {
                    shared.runInTransaction<Int> {
                        inTransaction.countDown()
                        check(replaced.await(10, TimeUnit.SECONDS)) { "the replacement never came" }
                        shared.captureSourceDao().all().size
                    }
                }
            }
        }
        assertTrue("the capture never began its transaction", inTransaction.await(10, TimeUnit.SECONDS))
        Databases.leasing { Databases.replacePoisoned(shared) }
        replaced.countDown()
        val outcome = capture.await()

        val opened = runCatching {
            DatabaseFactory.build(context).let { own ->
                try { own.captureSourceDao().setEnabled(ParseFixtures.TNG, true) } finally { own.close() }
            }
        }
        assertNull(
            "pinged.db would not open and take a write after the replacement: " +
                "${opened.exceptionOrNull()} (cause ${opened.exceptionOrNull()?.cause})",
            opened.exceptionOrNull(),
        )
        assertNull("the transaction threw ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertTrue("the instance was not closed once the capture let go of it", shared.closed)
    }

    /**
     * **A DAO held across a replacement still reads.** A guarded block
     * resolves its DAOs and then another caller replaces the instance under
     * them: the reviewer's probe, made deterministic. Closed at once, the
     * read threw code 21, as `android.database.SQLException`, out of the
     * guard -- to `viewModelScope`, in the app, a process kill.
     */
    @Test(timeout = 60_000)
    fun aDaoHeldAcrossAReplacementStillReads() = runBlocking<Unit> {
        val result = runCatching {
            CaptureStorage.guarded(context, "the screen's read", { "refused" }, damageStopsCapture = false) {
                val dao = Databases.captureSourceDao(context)
                // Another caller meets code 26, replaces the instance and is
                // done with it, while this one still holds its DAO.
                CaptureStorage.guarded(context, "another read", { Unit }, damageStopsCapture = false) {
                    Databases.replacePoisoned(Databases.shared(context))
                }
                dao.all()
                "read"
            }
        }

        assertNull("the read threw ${result.exceptionOrNull()} past the guard", result.exceptionOrNull())
        assertEquals("the read held across a replacement", "read", result.getOrNull())
    }

    /**
     * **An instance closed under a guarded block is tried again, not thrown
     * out of the guard.** `Databases.reset` waits for leases, but only for
     * `Databases.RESET_LEASE_WAIT_MILLIS`, and a reset from inside a lease
     * waits for itself: past the bound it closes the instance under
     * whoever holds it, which throws code 21 from the next statement --
     * outside the guard's catch unless it is looked for. So this test takes
     * the bound's length.
     */
    @Test(timeout = 60_000)
    fun aStatementOnAnInstanceClosedUnderItIsTriedAgainOnTheNext() = runBlocking<Unit> {
        var attempts = 0
        val result = runCatching {
            CaptureStorage.guarded(context, "the screen's read", { "refused" }, damageStopsCapture = false) {
                attempts++
                val dao = Databases.captureSourceDao(context)
                if (attempts == 1) Databases.reset()
                dao.all()
                "read"
            }
        }

        assertNull("a statement on a closed instance threw ${result.exceptionOrNull()} past the guard", result.exceptionOrNull())
        assertEquals("the read on a closed instance", "read", result.getOrNull())
        assertEquals("the read was not tried exactly once more", 2, attempts)
    }

    /**
     * **A reset waits for a leased transaction rather than closing its
     * instance under it**, whether or not the file is unlinked after. With
     * no unlink the difference is the file: closed mid-transaction, the
     * capture fails with code 21, its retry with code 5 2.5s later, and
     * every open of `pinged.db` after that is refused (measured). Both
     * production callers unlink next, which is all that keeps that off a
     * live file.
     */
    @Test(timeout = 60_000)
    fun aResetThatUnlinksNothingWaitsForALeasedTransactionAndLeavesTheFileWritable() = runBlocking<Unit> {
        val shared = Databases.shared(context)
        val inTransaction = CountDownLatch(1)
        var ended = Long.MAX_VALUE

        val capture = async(Dispatchers.IO) {
            runCatching {
                CaptureStorage.guarded(context, "the capture", unavailable = { -1 }) {
                    shared.runInTransaction<Int> {
                        shared.captureSourceDao().setEnabled(ParseFixtures.MAE, false)
                        inTransaction.countDown()
                        Thread.sleep(HELD_MILLIS)
                        shared.captureSourceDao().setEnabled(ParseFixtures.MAE, true)
                        shared.captureSourceDao().all().size
                    }.also { ended = SystemClock.elapsedRealtime() }
                }
            }
        }
        assertTrue("the capture never began its transaction", inTransaction.await(10, TimeUnit.SECONDS))
        var closed = 0L
        Databases.whileDeleting { closed = SystemClock.elapsedRealtime() }
        val outcome = capture.await()

        val opened = runCatching {
            DatabaseFactory.build(context).let { own ->
                try { own.captureSourceDao().setEnabled(ParseFixtures.TNG, true) } finally { own.close() }
            }
        }
        assertNull(
            "pinged.db would not open and take a write after a reset under a transaction: " +
                "${opened.exceptionOrNull()} (cause ${opened.exceptionOrNull()?.cause})",
            opened.exceptionOrNull(),
        )
        assertNull("the transaction threw ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertTrue("the transaction was refused: ${outcome.getOrNull()}", (outcome.getOrNull() ?: -1) > 0)
        assertTrue("the reset closed the instance ${ended - closed}ms before the transaction ended", closed >= ended)
    }

    /**
     * **A delete beside stage two waits for one capture, not the run.** A
     * run holds its lease from its first capture to its last -- up to
     * `ParsePass.MAX_ROWS_PER_RUN` of them -- and a reset waits for every
     * lease; so the run stops at the gate, before its next capture, and the
     * wait is that capture's.
     */
    @Test(timeout = 120_000)
    fun aDeleteBesideAStageTwoRunWaitsForItsCaptureAndNotItsRun() = runBlocking<Unit> {
        backlog(BACKLOG)
        val underway = CountDownLatch(1)
        var parsed = 0
        val run = async(Dispatchers.IO) {
            runCatching {
                Databases.leasing {
                    ParseFixtures.pass(context, onCaptureFinished = { if (++parsed == UNDERWAY) underway.countDown() }).run()
                }
            }
        }
        assertTrue("stage two never got under way", underway.await(60, TimeUnit.SECONDS))

        val asked = SystemClock.elapsedRealtime()
        var waited = -1L
        Databases.whileDeleting { waited = SystemClock.elapsedRealtime() - asked }
        val outcome = run.await()
        Log.i(TAG, "a delete beside a $BACKLOG-capture run waited ${waited}ms; the run ended $outcome")

        assertTrue("the delete waited ${waited}ms for stage two", waited in 0 until WAIT_BOUND_MILLIS)
        val summary = outcome.getOrThrow()
        assertTrue("stage two parsed ${summary.processed} of $BACKLOG under the gate", summary.processed < BACKLOG / 2)
        assertTrue("stage two stopped by the gate reported nothing left", summary.remaining)
    }

    /** [count] matching `NEW` captures, in one transaction. */
    private fun backlog(count: Int) {
        val db = Databases.shared(context)
        val at = System.currentTimeMillis() - count
        val rows = List(count) { i ->
            RawCapture(
                sourcePackage = ParseFixtures.TNG, postedAt = at + i, whenMillis = null, capturedAt = at + i,
                sbnKey = "0|backlog|$i", notifId = i, notifTag = null, userHandle = 0, channelId = "txn",
                flags = 0, arrival = Arrival.POSTED, title = "Touch 'n Go eWallet",
                text = "Payment of RM1.00 to BACKLOG $i successful", bigText = null, subText = null,
                extrasJson = null, contentHash = "backlog-$i",
            )
        }
        db.runInTransaction { rows.chunked(500).forEach { db.rawCaptureDao().insertAll(it) } }
    }

    /**
     * **Nothing a replacement sets aside is left open once nobody uses it.**
     * A ledger month on a damaged page replaces the instance on every read
     * of it; left open, each would hold its file descriptors and its page
     * cache for the life of the process -- three descriptors an instance,
     * measured on emulator-5554.
     */
    @Test(timeout = 60_000)
    fun everyInstanceAReplacementSetsAsideIsClosedOnceNothingUsesIt() = runBlocking<Unit> {
        Databases.shared(context).captureSourceDao().all()
        val before = descriptors()
        val retired = mutableListOf<PingedDatabase>()

        repeat(REPLACEMENTS) {
            CaptureStorage.guarded(context, "a read of a damaged month", { Unit }, damageStopsCapture = false) {
                val db = Databases.shared(context)
                retired += db
                Databases.replacePoisoned(db)
                db.captureSourceDao().all()
                Unit
            }
        }
        Databases.shared(context).captureSourceDao().all()

        val open = retired.count { !it.closed }
        assertEquals("of $REPLACEMENTS instances set aside, $open were never closed", 0, open)
        val grown = descriptors() - before
        assertTrue(
            "$REPLACEMENTS replacements left $grown more file descriptors open",
            grown < REPLACEMENTS,
        )
        assertFalse("the replacements raised the storage flag", CaptureHealth.storageUnavailable(context))
    }

    private fun descriptors(): Int = File("/proc/self/fd").list()?.size ?: error("no /proc/self/fd")

    private companion object {
        const val TAG = "SharedInstanceUnderUse"
        const val REPLACEMENTS = 30

        /** How long the leased transaction holds its instance: well past a close's reach. */
        const val HELD_MILLIS = 400L

        const val BACKLOG = ParsePass.MAX_ROWS_PER_RUN
        const val UNDERWAY = 100

        /** A capture's parse and commit is 5-20ms (spec 3); a tenth of the reset's own bound. */
        const val WAIT_BOUND_MILLIS = 500L
    }
}
