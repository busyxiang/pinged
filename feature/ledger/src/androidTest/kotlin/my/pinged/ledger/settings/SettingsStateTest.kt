package my.pinged.ledger.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.transfer.TransferStore
import my.pinged.ledger.transfer.bulkCapture
import my.pinged.ledger.transfer.damageMidFile
import my.pinged.ledger.transfer.useDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Section 6's state table, asserted against the holder rather than the UI --
 * offering an action that cannot run is decided here, not in the drawing.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStateTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application

    @Before fun startClean() = runBlocking {
        Transfers.forgetOutcome()
        Databases.reset()
        app.deleteDatabase(DatabaseFactory.NAME)
        TransferStore.forget(app)
        IntegrityStore.forget(app)
    }

    @Test fun aWorkingDatabaseWithNoBadNewsIsHealthy() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        val model = SettingsViewModel(app)
        model.refresh()
        assertEquals(Storage.HEALTHY, model.state.value.storage)
    }

    /**
     * **A connection closed under the counts is `android.database.SQLException`,
     * which `SQLiteException` extends rather than the other way round.** A
     * restore left running by a settings screen that went away resets the
     * database under whichever holder replaced it; the counts then run on the
     * closed instance and throw "Error code: 21, message: connection is
     * closed" as the superclass, which a `catch (SQLiteException)` never sees
     * -- and on `load`'s bare `viewModelScope.launch` that kills the process.
     *
     * The real exception, from the real counts: the instance is closed while
     * `Databases` still holds it, the state a reset landing between `opened`
     * and the counts leaves behind. Both counts are reached -- the source
     * count is evaluated first -- so this fails if either guard is narrowed.
     */
    @Test fun aConnectionClosedUnderTheCountsIsNotACrash() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        val model = SettingsViewModel(app)
        Databases.shared(app).close()
        val outcome = try {
            runCatching { model.refresh() }
        } finally {
            Databases.reset()
        }

        assertEquals(
            "A count on a closed connection threw out of refresh",
            null,
            outcome.exceptionOrNull(),
        )
        assertEquals(null, model.state.value.sourcesOn)
        assertEquals(null, model.state.value.wipeCounts)
    }

    /**
     * Healthy is the absence of bad news, and a recorded failure is bad news
     * that survives the process -- the check does not run at open.
     */
    @Test fun aRecordedFailureMakesItDamaged() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        IntegrityStore.record(app, at = 1L, ok = false)
        val model = SettingsViewModel(app)
        model.refresh()
        assertEquals(Storage.DAMAGED, model.state.value.storage)
    }

    @Test fun checkingADamagedFileRecordsIt() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            db.runInTransaction {
                repeat(10) { i ->
                    db.rawCaptureDao().insertAll(
                        List(500) { bulkCapture(i * 500 + it) },
                    )
                }
            }
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        Databases.reset()
        damageMidFile(app.getDatabasePath(DatabaseFactory.NAME))

        val model = SettingsViewModel(app)
        // check() launches on viewModelScope and returns its Job rather than
        // suspending -- join it, or this reads the default HEALTHY state
        // before the coroutine has run.
        model.check().join()
        assertEquals(Storage.DAMAGED, model.state.value.storage)
    }

    /**
     * The race `Transfers`' KDoc calls worse than [check]'s: a resume
     * landing beside a wipe must not redisplay pre-wipe counts on the one
     * screen whose job at that moment is telling the truth about what still
     * exists.
     *
     * `deleteEverything()` is held at [SettingsViewModel.beforeEachOperation]
     * before it touches anything -- there is one real source enabled going
     * in, so a stale read has something to be stale *about*. `load()` fires
     * while the wipe is held open, the same thing an app-switch does mid-
     * delete. Off the turn, the budgeted join below would return non-null:
     * `load()` has nothing to wait for, reads the still-intact database, and
     * publishes `sourcesOn = 1` immediately, well inside the budget --
     * clobbering whatever the wipe publishes after it finishes. On
     * `Transfers`' turn, `load()` cannot begin its own read until the delete
     * has let go of it, so the join times out, and once released `load()`
     * reads a database with nothing left in it, agreeing with the wipe rather
     * than overwriting it.
     *
     * No file corruption here, deliberately: [checkingADamagedFileRecordsIt]
     * establishes that `check()` survives a genuinely damaged file, but
     * `refresh()`'s own `captureSourceDao().enabledCount()` query is not
     * proven safe against one -- an orthogonal gap this test does not need to
     * cross to pin the ordering guarantee. `deleteEverything()` exercises the
     * same race against a database that stays entirely healthy throughout.
     *
     * **Falsified by taking `load()` off the turn**, which publishes the
     * stale count inside the budget and fails the first assertion. Taking
     * the delete off it instead never calls [beforeEachOperation], so the
     * first `withTimeout` times out before `load()` is asked for -- a
     * structural signal, not a run of the clobbered-value scenario.
     */
    @Test fun aResumeQueuedBehindADeleteDoesNotRedisplayPreWipeCounts() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            db.captureSourceDao().insertForImport(
                CaptureSource(pkg = "my.com.tngdigital.ewallet", label = "TNG", enabled = true, firstSeenAt = 1L),
            )
        }
        Databases.reset()

        val model = SettingsViewModel(app)
        val reachedTheGate = CompletableDeferred<Unit>()
        val releaseTheGate = CompletableDeferred<Unit>()
        val isTheFirst = AtomicBoolean(true)
        model.beforeEachOperation = {
            if (isTheFirst.compareAndSet(true, false)) {
                reachedTheGate.complete(Unit)
                releaseTheGate.await()
            }
        }

        val deleteJob = model.deleteEverything()
        withTimeout(TIMEOUT) { reachedTheGate.await() }

        val loadJob = model.load()
        // Give the queued load() every chance to publish first, which is
        // what an unchained load() would do: it has nothing to wait for.
        withTimeoutOrNull(SECOND_TAP_BUDGET) { loadJob.join() }
        // Only the ledger's half: the delete itself is drawn running, which
        // is the point of `SettingsState.running`.
        assertEquals(
            "load() is queued behind deleteEverything(), so neither has " +
                "published anything while the wipe is still held at the gate",
            SettingsState(),
            model.state.value.copy(running = null, outcome = null),
        )

        releaseTheGate.complete(Unit)
        withTimeout(TIMEOUT) { deleteJob.join(); loadJob.join() }

        // Wipe.everything rotates the key rather than leaving none behind
        // (its own KDoc), so the database that opens after it is a fresh,
        // healthy, empty one -- 0 is the true count, not a fallback.
        assertEquals(
            "load(), queued behind deleteEverything(), read the database " +
                "only after the wipe finished -- so it reported zero " +
                "sources rather than redisplaying the pre-wipe count",
            0,
            model.state.value.sourcesOn,
        )
    }

    /**
     * **The sheet's `Transactions` row counts what the feed counts.**
     *
     * `TxnDao.countAll` would answer 2 here. Every number this app has ever
     * shown the user comes off `FEED_SQL`, which hides `REJECTED`, and the
     * `Months of history` row two lines below this one hides it too -- so the
     * one place a count including rejections could appear is the last screen
     * read before agreeing to lose everything, where it reconciles against
     * nothing the user has seen.
     */
    @Test fun theDeleteSheetCountsTransactionsTheWayTheFeedDoes() = runBlocking {
        DatabaseFactory.build(app).useDb { db ->
            val cat = db.categoryDao().requireUncategorizedId()
            db.txnDao().insert(ledgerTxn(amountSen = 100, categoryId = cat))
            db.txnDao().insert(
                ledgerTxn(amountSen = 200, categoryId = cat, state = TxnState.REJECTED),
            )
        }
        Databases.reset()

        val model = SettingsViewModel(app)
        model.refresh()
        assertEquals(1, model.state.value.wipeCounts?.txns)
    }

    /**
     * **A delete that throws after the file is gone must report, not die.**
     *
     * Nothing in `Transfers.deleteEverything` throws an `SQLiteException`
     * once the database is unlinked: `Wipe.everything` ends in
     * `KeyStore.deleteEntry` and `CaptureCaches.clear` is a DataStore write,
     * so the guard there has to be a broad one, and it has to say the ledger
     * is gone.
     *
     * The throw is injected at `DatabaseKey.destroy`'s second line rather than
     * simulated -- see [HostileApp]. That is what makes
     * [TransferJob.Failed.ledgerLost] a provable claim here and not a cautious
     * one, which is why the file's absence is asserted below too.
     *
     * **Falsified by deleting that `catch`**: `Transfers`' last-resort one
     * then files the throw without [TransferJob.Failed.ledgerLost], and the
     * second assertion fails.
     */
    @Test fun aDeleteThatThrowsAfterTheWipeReportsInsteadOfCrashing() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        Databases.reset()

        val model = SettingsViewModel(HostileApp(app).apply { refusing = true })
        model.deleteEverything().join()

        val job = model.state.value.job
        assertEquals(
            "A delete that threw after the wipe did not publish a failure",
            true,
            job is TransferJob.Failed,
        )
        assertEquals(
            "The failure did not claim the ledger was lost, which is the one " +
                "thing this state means: $job",
            true,
            (job as TransferJob.Failed).ledgerLost,
        )
        assertEquals(
            "ledgerLost is only true if the ledger really is gone, and the " +
                "database file is still there",
            false,
            app.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
    }

    /**
     * **A delete that could not clear the verdict is an unfinished delete, and
     * this caller is the one that says so.** `Wipe.everything` hands the
     * failure back as a `Wipe.Leftover` rather than throwing it, because
     * `Restore.replaceEverything` must not lose a ledger over it -- see
     * `RestoreTest.aRestoreWhoseForgetFailsStillImports`. Here nothing else is
     * left to report it: dropped, the delete publishes success, and the
     * verdict on disk -- true of the ledger that just went -- is read by the
     * next `refresh` as [Storage.DAMAGED] with a salvage offer over the empty
     * database `opened()` created.
     *
     * The provocation is `WipeTest`'s directory-where-the-scratch-file-goes,
     * and the surviving timestamp is asserted because a DataStore that renamed
     * that file would make every assertion here pass for the ordinary reason.
     *
     * **[Storage] is asserted too, not just the job.** `SettingsState()`'s
     * default is [Storage.HEALTHY], so this is the "no `refresh()` on this
     * path" half of the behaviour: with one, the flag still on disk comes
     * straight back as [Storage.DAMAGED].
     */
    @Test fun aDeleteThatCannotClearTheVerdictAsksToBeRunAgain() = runBlocking {
        DatabaseFactory.build(app).useDb { }
        Databases.reset()
        IntegrityStore.record(app, at = 1L, ok = false)
        val scratch = File(app.filesDir, "datastore/integrity.preferences_pb.tmp")
        assertTrue("the scratch path was already taken", scratch.mkdirs())

        val model = SettingsViewModel(app)
        val checkAt = try {
            model.deleteEverything().join()
            IntegrityStore.lastCheckAt(app)
        } finally {
            scratch.deleteRecursively()
            IntegrityStore.forget(app)
        }

        assertEquals(
            "the forget succeeded after all, so this case no longer provokes what " +
                "it is named for",
            1L,
            checkAt,
        )
        val job = model.state.value.job
        assertEquals(
            "a delete that left the verdict behind published no failure, so nothing " +
                "asks for the second delete that would clear it: $job",
            true,
            job is TransferJob.Failed && job.ledgerLost,
        )
        assertEquals(
            "the screen headlines DAMAGED over the database this delete just left " +
                "empty, which is the verdict of the one it deleted",
            false,
            model.state.value.storage == Storage.DAMAGED,
        )
    }

    private companion object {
        private const val TIMEOUT = 10_000L

        // Long enough that an unchained load() would have finished a whole
        // read; chained, it cannot have begun one.
        private const val SECOND_TAP_BUDGET = 1_500L
    }
}
