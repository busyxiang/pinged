package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.read.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [GuardedRunner], `:core:read`'s production adapter over `CaptureStorage.guarded`
 * (#296): what the module cannot test, because it needs the real database --
 * the one transaction, the thread, and the gate.
 */
@RunWith(AndroidJUnit4::class)
class GuardedRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runner = GuardedRunner(context)

    @Before fun healthyStorage() {
        Graph.reset()
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
        CaptureHealth.forgetProcessMemo()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    @After fun clearTheBanner() {
        Graph.reset()
        CaptureHealth.forgetProcessMemo()
        runBlocking { CaptureHealth.clearStorageUnavailable(context) }
    }

    /**
     * A read's statements are one snapshot only inside one transaction, and
     * a screen calls this from the main thread, where Room refuses a query.
     */
    @Test(timeout = 60_000)
    fun aReadRunsOffTheMainThreadInOneTransaction() {
        var inTransaction = false
        var thread: String? = null

        val outcome = runBlocking(Dispatchers.Main) {
            runner.read("a read, for the test") { db ->
                thread = Thread.currentThread().name
                inTransaction = db.inTransaction()
                db.captureDayDao().countAll()
            }
        }

        assertEquals(Outcome.Done(0), outcome)
        assertTrue("The read's statements were not in one transaction", inTransaction)
        assertNotEquals("The read ran on the main thread", "main", thread)
    }

    /** A write's statements are its own: the runner leases and guards, and opens no transaction around them. */
    @Test(timeout = 60_000)
    fun aWriteRunsOnTheSharedInstanceInNoTransactionOfItsOwn() {
        var inTransaction = true
        var shared = false

        val outcome = runBlocking(Dispatchers.Main) {
            runner.write("a write, for the test") { db ->
                inTransaction = db.inTransaction()
                shared = db === Databases.shared(context)
                "written"
            }
        }

        assertEquals(Outcome.Done("written"), outcome)
        assertTrue("The write was handed an instance other than the shared one", shared)
        assertEquals("The write ran inside a transaction the runner opened", false, inTransaction)
    }

    /**
     * A delete's or a restore's gate refuses a screen's call. It is not damage and
     * loses nothing, so it raises no storage banner: `damageStopsCapture` is false.
     * And it is a ledger replacement, not an unreadable ledger, so it answers
     * [Outcome.Replacing] for the screen to say so (#328).
     */
    @Test(timeout = 60_000)
    fun aReadAndAWriteRefusedByTheGateAnswerReplacingAndRaiseNoBanner() {
        val (read, write) = underTheGate {
            runner.read("a read, for the test") { 1 } to runner.write("a write, for the test") { 2 }
        }

        assertEquals("A read refused by the gate was not answered as a replacement", Outcome.Replacing, read)
        assertEquals("A write refused by the gate was not answered as a replacement", Outcome.Replacing, write)
        assertEquals(
            "A screen's call refused by a delete's gate raised the storage-unavailable banner",
            false,
            runBlocking { CaptureHealth.storageUnavailable(context) },
        )
    }

    /** [call], made from this thread while another holds `Databases.whileDeleting`. */
    private fun <T> underTheGate(call: suspend () -> T): T {
        val up = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = thread {
            Databases.whileDeleting { up.countDown(); release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
        try {
            check(up.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the gate never rose" }
            return runBlocking { call() }
        } finally {
            release.countDown()
            gate.join()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
