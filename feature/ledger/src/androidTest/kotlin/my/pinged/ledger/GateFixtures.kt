package my.pinged.ledger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Starts [operation] held at its turn, before it touches anything, waits for
 * [busy] to say it is running, and hands back a release that lets it finish
 * and waits for it.
 *
 * Every release is added to [releases], which the caller's `@After` counts
 * down, so a test that fails inside the hold does not leave a transfer
 * running into the next class.
 */
internal suspend fun holdWhile(
    operation: Operation,
    busy: StateFlow<Operation?>,
    releases: MutableList<CountDownLatch>,
    requester: Job = Job(),
    start: (Job, suspend () -> Unit) -> Unit,
): AutoCloseable {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1).also(releases::add)
    start(requester) {
        held.countDown()
        release.await(TIMEOUT, TimeUnit.MILLISECONDS)
    }
    assertTrue("precondition: the $operation never took its turn", held.await(TIMEOUT, TimeUnit.MILLISECONDS))
    assertEquals(
        "The holder does not say a $operation is running",
        operation,
        withTimeout(TIMEOUT) { busy.first { it != null } },
    )
    return AutoCloseable {
        release.countDown()
        runBlocking {
            withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } }
            // [busy] is a main-thread collector's copy of that state, a
            // dispatch behind it; failed twice in two full :feature:ledger
            // runs without this wait. A busy that never clears still fails
            // the caller's assertion, on its message.
            withTimeoutOrNull(TIMEOUT) { busy.first { it == null } }
        }
    }
}
