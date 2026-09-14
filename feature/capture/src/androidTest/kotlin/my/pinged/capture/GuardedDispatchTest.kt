package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * `CaptureStorage.guarded` puts its block on [Dispatchers.IO], and this pins
 * the mechanism rather than any one caller.
 *
 * Dispatching only the open leaves a block that goes on to query running on
 * whatever dispatcher the caller was on, and every call site then has to
 * remember its own hop. `MainThreadRefreshTest` in `:feature:ledger` covers
 * the two view models that do; nothing covers the next caller that does not.
 * Room's `assertNotMainThread` throws a plain `IllegalStateException`, which
 * is outside `DatabaseUnavailableException` and therefore invisible to the
 * guard's own catch, so on `viewModelScope` -- a bare `SupervisorJob` with no
 * handler -- it reaches the thread's uncaught handler and kills the process.
 *
 * Falsified on emulator-5554 by reverting the wrap to
 * `withContext(Dispatchers.IO) { Databases.shared(context) }` around the open
 * alone: [aBlockThatQueriesIsSafeToCallFromTheMainThread] fails with
 * `java.lang.IllegalStateException: Cannot access database on the main thread
 * since it may potentially lock the UI for a long period of time.` out of
 * `countAll`, not a timeout.
 */
@RunWith(AndroidJUnit4::class)
class GuardedDispatchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The call the guard's KDoc invites and nothing guarded: a blocking DAO
     * read in the block, from the main thread, with no `withContext` of the
     * caller's own.
     *
     * `countAll` and not a suspend DAO method, deliberately -- Room dispatches
     * a suspend query to its own executor and never asserts, so a suspend call
     * here would pass whatever the guard does with the block.
     */
    @Test fun aBlockThatQueriesIsSafeToCallFromTheMainThread() {
        val ranOn = AtomicReference<String?>(null)

        // `runBlocking(Dispatchers.Main)` blocks the instrumentation thread and
        // runs the body on the main looper, so anything thrown out of `guarded`
        // is rethrown here and fails this test by name rather than being
        // swallowed by a scope.
        val days = runBlocking(Dispatchers.Main) {
            withTimeout(TIMEOUT_MILLIS) {
                CaptureStorage.guarded(
                    context,
                    what = "a blocking read dispatched by the guard",
                    unavailable = { -1 },
                ) {
                    ranOn.set(Thread.currentThread().name)
                    Databases.captureDayDao(context).countAll()
                }
            }
        }

        assertNotEquals(
            "The guarded block ran on the main thread, so the next caller that " +
                "queries without its own withContext(IO) gets Room's " +
                "assertNotMainThread -- an IllegalStateException outside " +
                "DatabaseUnavailableException, which the guard's catch cannot see.",
            "main",
            ranOn.get(),
        )
        assertTrue(
            "The block never reached the database, so this asserts nothing about " +
                "the dispatcher it would have needed.",
            days >= 0,
        )
    }

    private companion object {
        /** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` is what measures it. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
