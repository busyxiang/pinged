package my.pinged.capture

import my.pinged.data.Databases
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Seed
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole pipeline on a device where the app has never run, with **no test
 * seeding a category**.
 *
 * Every other class in this APK calls [ParseFixtures.prepare], which used to
 * put the fourteen categories in if they were missing. That one line is why
 * ~350 tests were green against an app that could not write a single
 * transaction: `Seed.categories()` had no production caller, so on a real
 * install `ParseWorker` threw on its first run, returned `Result.retry()`, and
 * every `raw_capture` row stayed at `NEW` for ever.
 *
 * So this deletes the database first and does not touch `category`. The
 * allow-list *is* written here, deliberately: it is default-deny user consent,
 * so an empty one is the correct state of a fresh install. The seeded
 * categories are the opposite -- app invariants no user action creates.
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val marker = "FRESH" + System.nanoTime()

    /**
     * A device this app has never run on.
     *
     * The order is not interchangeable. [Graph] memoizes one open SQLCipher
     * handle for the process, so the handle is closed and dropped *before* the
     * file is unlinked -- otherwise the next statement writes to a deleted
     * inode and the failure surfaces in some other test class entirely.
     */
    @Before fun deleteTheDatabaseTheAppHasNeverCreated() {
        CaptureFixtures.cancelStageTwo(context)
        Databases.shared(context).close()
        Graph.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    /**
     * Leaves the shared database in the state the rest of the APK expects: a
     * fresh file, built through the production path. Every other class
     * re-establishes its own allow-list rows in its own `@Before`.
     */
    @After fun leaveAUsableDatabaseBehind() {
        CaptureFixtures.cancelStageTwo(context)
    }

    /**
     * A real notification, through the real listener path, through the real
     * worker, into a committed transaction -- on a database nothing but the app
     * has written to.
     *
     * This is the test the logcat from `installDebug` was: the failure it
     * catches is not a wrong number, it is `Result.retry()` for ever and an
     * empty ledger.
     */
    @Test fun aRealNotificationBecomesACommittedTransactionWithNoTestSeeding() {
        // User consent, and the only thing this test writes. No category.
        CaptureFixtures.allowList(context, ParseFixtures.TNG, true)
        val postedAt = System.currentTimeMillis() - 5_000L
        val captureId = runBlocking {
            CaptureIngest.ingest(
                context = context,
                sbn = CaptureFixtures.posted(
                    CaptureFixtures.notification(
                        context,
                        title = "Touch 'n Go $marker",
                        text = "Payment of RM32.00 to 99 SPEEDMART successful",
                    ),
                    pkg = ParseFixtures.TNG,
                    id = 7_171,
                    tag = marker,
                    postTime = postedAt,
                ),
                arrival = Arrival.POSTED,
                now = postedAt,
            )
        }
        assertNotNull("Stage one stored nothing, so there is no stage two to test", captureId)

        val result = TestListenableWorkerBuilder<ParseWorker>(context).build().startWork().get()
        assertEquals(
            "Stage two must not retry on a fresh install. It does when the " +
                "seeded categories are missing: requireUncategorizedId throws, " +
                "doWork catches it and returns retry, and no transaction is " +
                "ever written.",
            ListenableWorker.Result.success(),
            result,
        )

        val capture = Databases.rawCaptureDao(context).byId(captureId!!)
        assertEquals(
            "The capture never left NEW, which is a silently lost transaction",
            ParseStatus.MATCHED,
            capture.parseStatus,
        )

        val txn = ParseFixtures.txnForCapture(context, captureId)
        assertNotNull("The capture produced no transaction", txn)
        requireNotNull(txn)
        assertEquals(3_200L, txn.amountSen)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertEquals("99 Speedmart", txn.merchantDisplay)
        // The category the transaction was filed under is a seeded row, so the
        // foreign key on `txn.category_id` resolves.
        assertTrue("txn.category_id is not a real row", txn.categoryId > 0)

        // Nothing in this class writes a category, so if they are here at all
        // they were put there by DatabaseFactory when it built the file above.
        assertEquals(
            "The database the app builds does not contain the seeded categories",
            Seed.categories().size,
            Databases.categoryDao(context).countAll(),
        )
    }

    /**
     * The worker's own dependency, isolated: nothing has run yet and the seed
     * has to already be there.
     */
    @Test fun theWorkerCanResolveUncategorizedBeforeAnythingElseRuns() {
        assertTrue(Databases.categoryDao(context).requireUncategorizedId() > 0)
    }
}
