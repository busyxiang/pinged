package my.pinged

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * The app starts.
 *
 * The only test whose subject is the application rather than a component of
 * it. `MainActivity` lives in `:app`, so no other module can start it.
 *
 * `ActivityScenario` rather than a Compose rule, because the lifecycle is the
 * subject: a test that renders `SourcesScreen` against a scope it supplies
 * never runs `lifecycleScope`, which is where the database work is dispatched
 * from.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTest {

    /**
     * `lifecycleScope` carries no `CoroutineExceptionHandler`, so a throw
     * inside it reaches the thread's default handler and kills the process --
     * ending the run with no attributable failure. Captured, it becomes an
     * assertion naming the lifecycle step.
     */
    private val uncaught = AtomicReference<Throwable?>(null)

    private var systemHandler: Thread.UncaughtExceptionHandler? = null

    @Before fun captureWhatWouldKillTheProcess() {
        systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, thrown ->
            uncaught.compareAndSet(null, thrown)
        }
    }

    @After fun restoreTheSystemHandler() {
        Thread.setDefaultUncaughtExceptionHandler(systemHandler)
    }

    @Test fun theAppStartsAndSurvivesBackgroundingAndRotation() {
        // A cold process is the subject. `Databases` memoizes its handle, so
        // without this the launch under test may touch no file at all.
        Databases.reset()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNothingWasThrownDuring("launch")

            // Also ForegroundRebinder's counter going 1 -> 0 -> 1, so this is
            // spec 10.1's third rebind path and not a repeat of the above.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertNothingWasThrownDuring("a second foreground")

            // onCreate and onResume again against an open database: the launch
            // above tests the open, this tests the memoized read.
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNothingWasThrownDuring("a configuration change")
        }
    }

    /**
     * The back stack is the reason Nav3 is here rather than a `when` over a
     * `StateFlow`, so it is the thing worth asserting about it.
     *
     * `recreate()` re-runs `onCreate` against the saved state the previous
     * instance wrote. What that exercises is the whole restore path -- the
     * reflective `NavKeySerializer` reading `Ledger` back out of a
     * `SavedState`, and `rememberViewModelStoreNavEntryDecorator` handing the
     * new entry the retained `ViewModelStore` -- and a break in any of it
     * throws in composition rather than returning a wrong answer, which is why
     * this asserts RESUMED and an absence rather than a rendered screen.
     *
     * It is the weaker half of what it looks like: it cannot tell a restored
     * back stack from a fresh one built on the same start destination, because
     * it never leaves the start destination. [NavigationTest] drives the
     * ledger's control to `Sources` and back; the stronger assertion -- push
     * `Sources`, recreate, and come back on the allow-list -- is **still
     * outstanding**.
     */
    @Test fun theActivityComesBackFromRecreationOnARestoredBackStack() {
        Databases.reset()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertNothingWasThrownDuring("launch")
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNothingWasThrownDuring("recreation with a restored back stack")
        }
    }

    /**
     * Drain the main thread, then assert.
     *
     * `waitForIdleSync` is enough for this failure because
     * `Dispatchers.Main.immediate` runs the body inline when already on the
     * main thread, which `onResume` is. It does **not** cover a throw from the
     * `Dispatchers.IO` continuation: nothing outside `MainActivity` can
     * observe that refresh finishing, so `MainThreadRefreshTest` holds the
     * `Job` and asserts the asynchronous tail there instead.
     */
    private fun assertNothingWasThrownDuring(step: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNull("An uncaught exception killed the process during $step.", uncaught.get())
    }
}
