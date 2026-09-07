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
 * The first refresh of a cold process, on the thread the app calls it from.
 *
 * `MainActivity` builds `SourcesViewModel` with `lifecycleScope`, which is
 * `Dispatchers.Main.immediate`. Every other test in this module supplies
 * `Dispatchers.Default`, and that substitution is the difference between a
 * passing suite and an app that cannot start.
 *
 * The handler stands in for the uncaught handler `lifecycleScope` does not
 * have: a `SupervisorJob` child's failure does not resurface at `join()`, so
 * asserting on the `Job` alone would pass while the process was dying.
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
        // The reset is the subject, not tidying: the open is memoized for the
        // life of the process, so without it this asserts against a volatile
        // read and passes on broken code.
        Databases.reset()

        val sources = SourcesViewModel(context, mainScope)
        runBlocking { withTimeout(TIMEOUT_MILLIS) { sources.refresh().join() } }

        assertNull(
            "A refresh dispatched on Dispatchers.Main.immediate threw.",
            crashed.get(),
        )
        assertTrue(
            "The refresh completed without publishing a loaded state, so it did " +
                "not read the allow-list at all.",
            sources.state.value.loaded,
        )
    }

    private companion object {
        /** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` is what measures it. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
