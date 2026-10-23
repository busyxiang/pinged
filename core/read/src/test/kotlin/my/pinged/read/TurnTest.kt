package my.pinged.read

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two guarantees [ScreenRead]'s turn gives spec 9.6's consent (#301): two taps
 * on one toggle land in the order made, and a read never publishes over a write
 * that landed after it was asked for. Each has a hole that looks like a
 * simplification, and each test is the shape that falls through it.
 */
@RunWith(RobolectricTestRunner::class)
class TurnTest {
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())

    /** Scopes made by a test, cancelled when it ends. */
    private val scopes = ArrayList<CoroutineScope>()

    private fun readIn(dispatcher: CoroutineDispatcher): ScreenRead<Int, Int> {
        val scope = CoroutineScope(dispatcher + SupervisorJob()).also { scopes += it }
        return ScreenRead(scope, runner, "The test cannot be read", initial = 1) { _, question -> question }
    }

    @After fun end() {
        scopes.forEach { it.cancel() }
    }

    /**
     * Not a place in the queue lost by cancelling it. `Job.join()` is cancellable and a
     * cancelled `Job` completes, so a chain of `previous.join()` is severed the moment
     * a link still waiting is cancelled, and everything behind it runs beside the
     * operation the cancelled link was waiting for.
     */
    @Test fun cancellingALinkStillWaitingDoesNotReleaseTheLinksBehindIt() {
        val read = readIn(Dispatchers.Unconfined)
        runner.holding = true
        read.refresh()
        val cancelled = read.refresh()
        cancelled.cancel()
        val behind = read.refresh()

        assertEquals(
            "A read queued behind a cancelled link began while the read before the cancelled link was still held",
            1,
            runner.calls.size,
        )
        assertFalse("The link behind finished with the first read still held", behind.isCompleted)

        runner.calls[0].release()
        // The cancelled link may or may not reach the runner before it ends, so the
        // read that is held now is the last one asked of it.
        assertTrue("The link behind a cancelled one never got its turn: the queue is deadlocked", runner.calls.size >= 2)
        assertFalse(behind.isCompleted)

        runner.calls.last().release()
        assertTrue("The link behind did not complete once its own read was released", behind.isCompleted)
        assertEquals(Answered(1, 1), read.state.value.answered)
    }

    /**
     * A place in the turn is claimed by the call, on the caller's thread, and not by
     * the coroutine it launches. Claimed inside the coroutine, it is claimed when the
     * dispatcher gets to it, which is not the order the user tapped in: this
     * dispatcher runs the newest launch first, and the write asked for first must
     * still run first.
     */
    @Test fun aPlaceInTheTurnIsClaimedByTheCallNotByTheCoroutineItLaunches() {
        val dispatcher = NewestFirst()
        val read = readIn(dispatcher)

        val written = read.write("The test cannot be written") { }
        read.refresh()
        assertEquals("A coroutine ran before the dispatcher was asked to", 0, runner.calls.size)
        dispatcher.runAll()

        assertEquals(
            "The read asked for after the write ran before it: the turn's order was the dispatcher's, not the caller's",
            listOf(true, false, false),
            runner.calls.map { it.write },
        )
        assertTrue(written.isCompleted)
    }

    @Test fun beforeEachOperationHoldsEveryOperationAtTheHeadOfItsTurn() {
        val read = readIn(Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var reached = 0
        read.beforeEachOperation = {
            reached++
            if (reached == 1) gate.await()
        }

        read.refresh()
        read.refresh()
        assertEquals("The hook did not run for the first operation", 1, reached)
        assertEquals("An operation ran past the hook", 0, runner.calls.size)

        gate.complete(Unit)
        assertEquals("The hook did not run for the second operation", 2, reached)
        assertEquals("An operation did not run once the hook let it", 2, runner.calls.size)
    }

    /** A dispatcher that holds every task until [runAll], and then runs the newest first. */
    private class NewestFirst : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeLast().run()
        }
    }
}
