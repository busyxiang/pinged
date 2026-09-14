package my.pinged.ledger

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.ledger.sources.SourcesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * The first refresh of a cold process, on the thread the app runs it on.
 *
 * What the class rests on is `Dispatchers.Main.immediate`, which
 * `viewModelScope` supplies but nothing in the type system states, so this file
 * asserts the dispatcher directly as well as asserting the refresh survives it.
 * A test that substitutes `Dispatchers.Default` is the difference between a
 * passing suite and an app that cannot start.
 *
 * **The default uncaught handler is where the throw actually arrives, and this
 * was measured rather than assumed.** It is not a stand-in for a
 * `CoroutineExceptionHandler`: one in the coroutine's own context would
 * short-circuit `handleCoroutineException` and this would never see the throw.
 * With neither scope carrying one, `handleCoroutineExceptionImpl` runs the
 * ServiceLoader handlers first, and kotlinx-coroutines-android 1.11.0's
 * `AndroidExceptionPreHandler.handleException` touches the platform
 * pre-handler only while `26 <= SDK_INT < 28` -- above that it returns without
 * throwing `ExceptionSuccessfullyProcessed`, so the implementation falls
 * through to `Thread.currentThread().uncaughtExceptionHandler`, which on the
 * main thread resolves to the default handler this test replaced. The process
 * therefore survives instead of being killed by `KillApplicationHandler`,
 * which is what happens in production where nothing has replaced it.
 *
 * Falsified on the Pixel_10a AVD, API 37: throwing
 * `DatabaseKeyUnavailableException` out of `readOrReportStorage` *outside*
 * `CaptureStorage.guarded` -- spec 11.1's state on the path the guard exists
 * for -- fails `aColdRefreshFromTheMainThreadDoesNotThrow` on its own
 * `assertNull`, naming the exception, and the sibling test in this file still
 * runs afterwards. Asserting on the `Job` alone would have passed, because
 * `join()` does not rethrow. On API 27, which `minSdk` still allows, the
 * pre-handler branch is live and this mechanism is untested.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadRefreshTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val crashed = AtomicReference<Throwable?>(null)

    private var systemHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun captureWhatWouldKillTheProcess() {
        systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown ->
            crashed.compareAndSet(null, thrown)
        }
    }

    @After fun restoreTheSystemHandler() {
        Thread.setDefaultUncaughtExceptionHandler(systemHandler)
    }

    @Test fun aColdRefreshFromTheMainThreadDoesNotThrow() {
        // The reset is the subject, not tidying: the open is memoized for the
        // life of the process, so without it this asserts against a volatile
        // read and passes on broken code.
        Databases.reset()

        val sources = sourcesViewModel()
        runBlocking { withTimeout(TIMEOUT_MILLIS) { sources.refresh().join() } }

        assertNull(
            "A refresh dispatched on viewModelScope threw, and nothing in that " +
                "scope would have caught it.",
            crashed.get(),
        )
        assertTrue(
            "The refresh completed without publishing a loaded state, so it did " +
                "not read the allow-list at all.",
            sources.state.value.loaded,
        )
    }

    /**
     * `SourcesViewModel.tail` is documented as needing no lock. Half of that
     * rests on the queued bodies sharing one thread, which holds only because
     * `viewModelScope` is `Dispatchers.Main.immediate` -- a choice
     * androidx.lifecycle makes and nothing in the type system states. So this
     * reads the thread name out of the queued body rather than trusting it.
     *
     * It does **not** cover the other half: `tail` is written by whoever calls
     * `enqueue`, on their thread, and that is the instrumentation thread here.
     * See `tail`'s own KDoc.
     */
    @Test fun enqueueStillRunsOnTheMainThread() {
        Databases.reset()
        val seen = AtomicReference<String?>(null)
        val sources = sourcesViewModel()
        sources.beforeEachOperation = { seen.set(Thread.currentThread().name) }

        runBlocking { withTimeout(TIMEOUT_MILLIS) { sources.refresh().join() } }

        assertEquals(
            "enqueue no longer runs on the main thread, so `tail`'s documented " +
                "lock-free invariant is false and two toggles can race",
            "main", seen.get(),
        )
    }

    private fun sourcesViewModel() =
        SourcesViewModel(context.applicationContext as Application)

    private companion object {
        /** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` is what measures it. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
