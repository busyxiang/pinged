package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * What `DatabaseAccess.read`'s check rests on (#302, decision 10): Room 2.8.4's
 * `inTransaction()` over SQLCipher reports the calling thread's transaction,
 * so asking it before the hop refuses a caller that would deadlock and no one
 * else. Each case is a figure measured on emulator-5554 and recorded in
 * `read`'s KDoc; this pins them, so a Room bump that changes one fails here
 * rather than in the field.
 *
 * Real SQLCipher and a real lock are why this is not in `DatabaseAccessTest`.
 */
@RunWith(AndroidJUnit4::class)
class InTransactionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Reverted, the last case deadlocks, and the thread it abandons keeps
     * `Databases`' monitor for the life of the process: every case after it
     * would wait on that monitor and the run would hold the emulator until
     * `emu` stopped it. So each case fails at 30s instead.
     */
    @get:Rule val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    @Before fun startFromAnEmptyDatabase() {
        Databases.whileDeleting { context.deleteDatabase(DatabaseFactory.NAME) }
    }

    @Test fun itReportsTheCallingThreadsTransactionAndNothingElses() {
        val db = Databases.shared(context)
        assertFalse("outside any transaction", db.inTransaction())
        assertTrue("inside runInTransaction, same thread", db.runInTransaction(Callable { db.inTransaction() }))
        assertFalse("after it ended", db.inTransaction())

        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread { db.runInTransaction(Callable { inside.countDown(); release.await(20, TimeUnit.SECONDS) }) }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        val asked = AtomicReference<Boolean>()
        val other = thread { asked.set(db.inTransaction()) }
        other.join(3_000)
        val waited = other.isAlive
        release.countDown()
        holder.join(10_000)
        other.join(10_000)

        assertFalse("a second thread's inTransaction() waited for the first's transaction", waited)
        assertFalse("a second thread was told it was in the first's transaction", asked.get())
    }

    @Test fun itIsTrueInsideAReadBlockAndFalseInsideAWriteBlock() {
        val access = DatabaseAccess.production(context)

        val inRead = runBlocking { access.read("a read") { it.inTransaction() } }
        val inWrite = runBlocking { access.write("a write") { it.inTransaction() } }

        assertEquals(Access.Done(true), inRead)
        assertEquals(Access.Done(false), inWrite)
    }

    @Test fun onAClosedInstanceItIsFalseAndDoesNotReopenIt() {
        val own = DatabaseFactory.build(context)
        own.close()

        assertFalse(own.inTransaction())
        assertFalse("inTransaction() reopened a closed instance", own.isOpen)
    }

    /**
     * Reverted, this is the deadlock the check exists for: the IO thread takes
     * `Databases`' monitor and waits for the connection this thread's transaction
     * holds. So it runs on a thread it abandons after 10s rather than hanging
     * the suite, and what it asserts is the refusal.
     */
    @Test fun aReadFromInsideATransactionOnTheServedInstanceIsRefusedNotDeadlocked() {
        val access = DatabaseAccess.production(context)
        val db = Databases.shared(context)
        val thrown = AtomicReference<Throwable>()
        val caller = thread(isDaemon = true) {
            try {
                db.runInTransaction(Callable { runBlocking { access.read("a nested read") { 1 } } })
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        caller.join(10_000)
        val hung = caller.isAlive
        if (hung) caller.interrupt()

        assertFalse("the read waited on the connection its caller's transaction held", hung)
        assertTrue("expected IllegalStateException but got ${thrown.get()}", thrown.get() is IllegalStateException)
    }
}
