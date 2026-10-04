package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 11.3, asserted against the filesystem: a delete that reports success
 * having removed nothing passes any weaker test.
 */
@RunWith(AndroidJUnit4::class)
class WipeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startWithSomethingToDelete() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        DatabaseFactory.build(context).useDb { it.rawCaptureDao().insert(sampleCapture(hash = "doomed")) }
    }

    @Test fun nothingSurvives() = runBlocking {
        val main = context.getDatabasePath(DatabaseFactory.NAME)
        assertEquals(
            "a delete of a healthy device reported something left behind",
            Wipe.Leftover.NONE,
            Wipe.everything(context),
        )

        assertFalse("the database file is still there", main.exists())
        assertFalse("a -wal is still there", File("${main.path}-wal").exists())
        assertFalse("a -shm is still there", File("${main.path}-shm").exists())
        assertFalse("the wrapped key is still there", DatabaseKey.exists(context))
    }

    /** The app has to work afterwards, on a new key and an empty ledger. */
    @Test fun theAppOpensAgainAfterwards() = runBlocking {
        assertEquals(Wipe.Leftover.NONE, Wipe.everything(context))
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(0, db.rawCaptureDao().countAll())
        }
    }

    /**
     * A verdict left on disk describes a database that no longer exists, so a
     * forget that will not clear it is something the caller has to hear about
     * -- swallowed, `Transfers.deleteEverything` reports success, the settings
     * screen's re-read finds the stale flag, and the screen headlines `DAMAGED`
     * with a salvage offer over the database it created seconds ago.
     *
     * **A returned [Wipe.Leftover] rather than a throw, and the destroy
     * succeeding is what makes the difference.** A throw is the right report
     * for that caller and the wrong one for `Restore.replaceEverything`, which
     * wraps this call in the translation that turns a throw into
     * `RestoreLedgerLostException` and so would skip the import that is still
     * perfectly able to run -- losing the ledger over a preference write, with
     * a retry that re-enters the same failing write. `RestoreTest`'s
     * `aRestoreWhoseForgetFailsStillImports` is that case, provoked the same
     * way as this one.
     *
     * A directory standing where DataStore wants its scratch file is the
     * cheapest full disk on offer: measured on emulator-5554, `edit` then
     * fails with `IOException: Inoperable file: canonical[...]
     * freeSpace[5282471936]`. The name is DataStore's own -- the store's name
     * plus `.preferences_pb`, plus the `.tmp` it writes through before
     * renaming.
     *
     * **The surviving timestamp is asserted, not just the answer.** If
     * DataStore ever renames that scratch file the provocation stops working
     * and the forget quietly succeeds, at which point a test asserting only
     * on the returned value would be asserting on nothing.
     *
     * The other half -- a forget that runs although [DatabaseKey.destroy]
     * threw -- is not reachable from here: `KeyStore.deleteEntry` does not
     * throw on an absent alias (measured, a second [Wipe.everything] returns
     * normally), so provoking it needs a seam. `RestoreTest`'s hostile
     * `getDatabasePath` is that seam, in `:feature:ledger` -- two modules up,
     * and unreachable from this source set, which is why the two halves are
     * pinned in different APKs.
     */
    @Test fun aForgetThatFailsIsAnUnfinishedDelete() = runBlocking {
        IntegrityStore.record(context, 1L, ok = false)
        val scratch = File(context.filesDir, "datastore/integrity.preferences_pb.tmp")
        assertTrue("the scratch path was already taken", scratch.mkdirs())

        val leftover = try {
            Wipe.everything(context)
        } finally {
            scratch.deleteRecursively()
        }

        assertEquals(
            "the verdict could not be cleared and the delete reported nothing left " +
                "behind, so nothing asks for the second delete that would finish it",
            Wipe.Leftover.INTEGRITY_VERDICT,
            leftover,
        )
        assertFalse(
            "the database file survived",
            context.getDatabasePath(DatabaseFactory.NAME).exists(),
        )
        assertEquals(
            "the forget succeeded after all, so this case no longer provokes what " +
                "it is named for",
            1L,
            IntegrityStore.lastCheckAt(context),
        )
    }

    /**
     * **A cancelled forget is not a leftover, and the same `catch (Exception)`
     * that reports one would swallow it.** `CancellationException` descends
     * `IllegalStateException -> Exception` on the JVM, so caught there it
     * becomes [Wipe.Leftover.INTEGRITY_VERDICT] -- a delete reporting an
     * unfinished-delete banner to a screen that has already gone, while the
     * coroutine completes as though it had not been cancelled.
     *
     * Provoked by cancelling the caller rather than by a seam, which is what
     * makes it the real case: [DatabaseKey.destroy] runs to completion because
     * nothing in it suspends, and [IntegrityStore.forget] is then the first
     * suspension point the cancellation can surface at. The destroy-failed
     * branch's rethrow needs a seam instead, and is
     * `WipeCancellationTest.aCancelledForgetAfterAFailedDestroyStillCancels`
     * in `:feature:ledger`.
     */
    @Test fun aCancelledForgetCancelsTheDeleteRatherThanReportingALeftover() = runBlocking {
        IntegrityStore.record(context, 1L, ok = false)

        var outcome: Result<Wipe.Leftover>? = null
        CoroutineScope(Dispatchers.Default).launch {
            coroutineContext.job.cancel()
            outcome = runCatching { Wipe.everything(context) }
        }.join()

        assertTrue(
            "a cancelled forget was answered with a value rather than by cancelling, " +
                "so a screen that is gone is told its delete did not finish: $outcome",
            outcome?.exceptionOrNull() is CancellationException,
        )
    }

    /**
     * [aForgetThatFailsIsAnUnfinishedDelete] leaves a verdict behind by
     * construction, and `integrity` is one store for the whole process: left
     * there it would be read by every class that runs after this one in the
     * same APK.
     */
    @After fun leaveNoVerdictBehind() = runBlocking { IntegrityStore.forget(context) }
}
