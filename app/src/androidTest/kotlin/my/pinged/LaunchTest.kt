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
 * The only test in this project whose subject is the application rather than a
 * component of it, and the one that was missing when v0.1.0 shipped an APK that
 * died before drawing a frame. `MainActivity` lives in `:app`, so no other
 * module can start it; `:app` had no `androidTest` source set at all, so
 * nothing did.
 *
 * `ActivityScenario` rather than a Compose rule, because the failure was in the
 * lifecycle and not in the tree: `onResume` launched database work in
 * `lifecycleScope`, which is `Dispatchers.Main.immediate`, and a Compose test
 * that renders `SourcesScreen` against a supplied scope never runs that code.
 */
@RunWith(AndroidJUnit4::class)
class LaunchTest {

    /**
     * Where the failure this test exists for actually lands.
     *
     * `lifecycleScope` carries no `CoroutineExceptionHandler`, so a throw inside
     * it reaches the thread's default handler, which kills the process. Left
     * alone that ends the instrumentation run with a dead process and no
     * attributable failure; captured here it becomes an assertion naming the
     * lifecycle step it happened in.
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
        // A cold process is the subject. `Databases` memoizes its handle for the
        // life of the process, so without this the open -- and
        // `DatabaseFactory`'s eager seed, which is the blocking work -- may
        // already have been paid for by an earlier caller on another thread, and
        // the launch this test watches would never touch the file.
        Databases.reset()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNothingWasThrownDuring("launch")

            // onResume again. This is the path that failed, and it is also
            // ForegroundRebinder's counter going 1 -> 0 -> 1, so a second
            // foreground exercises spec 10.1's third rebind path rather than
            // just repeating the first assertion.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertNothingWasThrownDuring("a second foreground")

            // A configuration change runs onCreate and onResume again against a
            // database that is now open, which is the other half of the state
            // space: the first launch tests the open, this tests the memoized
            // read.
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertNothingWasThrownDuring("a configuration change")
        }
    }

    /**
     * Drain the main thread, then assert.
     *
     * `waitForIdleSync` is the whole instrument and it is enough for this
     * failure specifically: `Dispatchers.Main.immediate` runs the coroutine
     * body synchronously when it is already on the main thread, which `onResume`
     * is, so the throw happened inside `onResume` rather than after it.
     *
     * What this does **not** cover is a throw from the `Dispatchers.IO`
     * continuation after the read completes. Nothing outside `MainActivity`
     * can observe that refresh finishing -- the state lives in a private
     * `SourcesViewModel` -- so making it condition-based would mean widening
     * the Activity's surface for a test. `MainThreadRefreshTest` holds the
     * `Job` directly and joins it, and that is where the asynchronous tail is
     * asserted.
     */
    private fun assertNothingWasThrownDuring(step: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertNull(
            "An uncaught exception reached the default handler during $step. " +
                "On a device that is the process dying, which is what v0.1.0 did.",
            uncaught.get(),
        )
    }
}
