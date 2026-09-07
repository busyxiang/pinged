package my.pinged.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.ledger.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * The first refresh of a cold process, on the thread the app actually calls it
 * from.
 *
 * `MainActivity` builds `SourcesViewModel` with `lifecycleScope`, which is
 * `Dispatchers.Main.immediate`. Every other test in this module supplies
 * `Dispatchers.Default`, and that one substitution is the difference between a
 * suite that passes and an app that cannot start: the database open inside
 * `CaptureStorage.guarded` is blocking, Room's `assertNotMainThread` throws on
 * the main thread, and `lifecycleScope` carries no `CoroutineExceptionHandler`,
 * so on a device the throw reaches the thread's uncaught handler and takes the
 * process with it.
 *
 * [Databases.reset] is what makes this a cold start rather than a volatile
 * read. The open is memoized for the life of the process, so without the reset
 * the first caller -- any earlier test class, on any thread -- has already paid
 * for it and this test proves nothing. That is also why the reset cannot be
 * dropped as tidying: it *is* the subject.
 *
 * The handler stands in for the uncaught handler, because a `SupervisorJob`
 * child's failure does not resurface at `join()`. Asserting on the `Job` alone
 * would pass while the process was dying.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadRefreshTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val crashed = AtomicReference<Throwable?>(null)

    private val mainScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, thrown -> crashed.set(thrown) },
    )

    @After fun releaseTheScope() = mainScope.cancel()

    @Test fun aColdRefreshFromTheMainThreadDoesNotThrow() {
        // Drop the memoized handle so this refresh is the one that opens the
        // file and runs DatabaseFactory's eager seed, which is the blocking
        // work that has to be off the main thread.
        Databases.reset()

        val sources = SourcesViewModel(context, mainScope)
        runBlocking { withTimeout(TIMEOUT_MILLIS) { sources.refresh().join() } }

        assertNull(
            "A refresh dispatched on Dispatchers.Main.immediate threw. On a device " +
                "this is MainActivity.onResume, lifecycleScope has no handler, and " +
                "the app dies before drawing anything.",
            crashed.get(),
        )
        assertTrue(
            "The refresh completed without publishing a loaded state, so it did " +
                "not read the allow-list at all.",
            sources.state.value.loaded,
        )
    }

    private companion object {
        /**
         * Generous because it covers a cold SQLCipher open on a loaded emulator,
         * and this test is not measuring how long that takes -- `OpenTest` is.
         */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
