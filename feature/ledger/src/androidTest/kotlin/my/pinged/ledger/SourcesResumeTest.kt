package my.pinged.ledger

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.SourceCounters
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.entity.CaptureSource
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.settings.TransferJob
import my.pinged.data.entity.Arrival
import my.pinged.ledger.sources.SourcesScreen
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.6's discovery promise on the *second* foreground: a package appears
 * once it has spoken, which the screen's own footer prints as AN APP APPEARS
 * HERE ONCE IT NOTIFIES.
 *
 * The listener runs while no Activity does -- that is the whole design -- so the
 * foreground the user comes back to is the one that has to show what arrived.
 * Two things stand in the way of that and are each right on their own:
 * `MainActivity.onResume` does not call `refresh()`, and `NavDisplay` scopes
 * each holder to its `NavEntry` so the holder survives backgrounding. A
 * `LaunchedEffect(viewModel)` therefore fires once for the life of the entry,
 * and a warm resume reads nothing at all -- no new row, no moved SEEN count,
 * and a frozen `storageUnavailable` that can contradict the banner above it.
 * There is no pull-to-refresh and nowhere to navigate, so nothing else forces
 * the read.
 *
 * **`createAndroidComposeRule`, not `createComposeRule`, and that is the point
 * of the file.** Only the former exposes `activityRule.scenario`, and only a
 * real `ActivityScenario` transition drives the `Lifecycle` the effect observes
 * through `LocalLifecycleOwner`: `moveToState(CREATED)` runs `onPause` and
 * `onStop` on the host Activity and `moveToState(RESUMED)` runs `onStart` and
 * `onResume`, which is a genuine background and foreground of the composition
 * rather than a hand-driven `Lifecycle`. What it does not reproduce is the
 * process being killed while away -- that path recreates the holder and reads
 * correctly whatever the effect is keyed on.
 */
@RunWith(AndroidJUnit4::class)
class SourcesResumeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Not a real package and not in the pack, so it belongs in the lower list.
     * Unique per run for the reason `SourcesAllowListTest` gives: `SourceCounters`
     * never prunes, so the identifier outlives the test on a device whose app data
     * survives the next `install -r`.
     */
    private val pkg = "my.pinged.probe.spokewhileaway.p" + System.nanoTime()

    @Test fun aPackageThatSpokeWhileTheScreenWasAwayAppearsOnResume() {
        // The open is memoized for the life of the process, so without the reset
        // this can assert against a volatile read left by an earlier test.
        Databases.reset()

        val viewModel = SourcesViewModel(context.applicationContext as Application)
        compose.setContent { PingedTheme { SourcesScreen(viewModel, onBack = {}) } }

        compose.waitUntil(TIMEOUT) { viewModel.state.value.loaded }
        assertEquals(
            "precondition: nothing has been counted for this package, so no row " +
                "for it may be drawn yet",
            0,
            compose.onAllNodesWithTextSafely(pkg),
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        // Stage 1, spec 9.6, with no Activity resumed: a package the allow-list
        // has never heard of posts something, which leaves a count in DataStore
        // and nothing at all in Room.
        runBlocking {
            SourceCounters.countOne(context, pkg, Arrival.POSTED, System.currentTimeMillis())
        }
        assertTrue(
            "precondition: the count was not recorded while the screen was " +
                "stopped, so this test cannot say anything about the resume",
            runBlocking { SourceCounters.seenCount(context, pkg) } > 0,
        )

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // The read is the subject, and the state is where it is unambiguous: a
        // Compose text assertion cannot tell "the screen never read" from
        // "the row is composed but scrolled out of the lazy list".
        val reRead = runCatching {
            compose.waitUntil(TIMEOUT) {
                viewModel.state.value.seenNotCaptured.any { it.pkg == pkg }
            }
        }.isSuccess
        assertTrue(
            "A package counted while the screen was backgrounded is still not in " +
                "the allow-list state after a resume, so the screen performed no " +
                "read on this foreground. The holder is NavEntry-scoped and " +
                "survives backgrounding, so an effect that fires once per holder " +
                "reads once and never again -- and the footer's claim that an app " +
                "appears here once it notifies is false until the process is " +
                "recreated",
            reRead,
        )

        // And drawn, not merely held: the state feeds a LazyColumn, so scroll to
        // it rather than trusting that the lower list fits on the screen of an
        // emulator that has accumulated discovered packages.
        val drawn = runCatching {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(pkg))
        }.isSuccess
        assertTrue(
            "The row is in the allow-list state but the screen does not draw it",
            drawn && compose.onAllNodesWithTextSafely(pkg) > 0,
        )
    }

    /**
     * A delete that lands while the screen is showing, with no resume after
     * it: the user opened the allow-list from settings while the delete ran.
     *
     * The subject is consent. The delete takes every `capture_source` row with
     * it, so a screen still drawing the replaced ledger's state shows a source
     * switched ON that nothing is capturing from.
     */
    @Test fun aDeleteWhileTheScreenIsShowingIsReadWithoutAResume() {
        Databases.reset()
        context.deleteDatabase(DatabaseFactory.NAME)
        Databases.captureSourceDao(context).insertForImport(
            CaptureSource(pkg = pkg, label = "Probe", enabled = true, firstSeenAt = 1L),
        )

        val app = context.applicationContext as Application
        val viewModel = SourcesViewModel(app)
        compose.setContent { PingedTheme { SourcesScreen(viewModel, onBack = {}) } }
        assertTrue(
            "precondition: the enabled source never reached the allow-list state",
            runCatching {
                compose.waitUntil(TIMEOUT) {
                    viewModel.state.value.suggested.any { it.pkg == pkg && it.enabled }
                }
            }.isSuccess,
        )

        val settings = SettingsViewModel(app)
        runBlocking { settings.deleteEverything().join() }
        assertTrue(
            "The delete itself failed, so nothing below is about this screen: " +
                "${settings.state.value.job}",
            settings.state.value.job !is TransferJob.Failed,
        )

        val reRead = runCatching {
            compose.waitUntil(TIMEOUT) {
                viewModel.state.value.suggested.none { it.pkg == pkg && it.enabled }
            }
        }.isSuccess
        assertTrue(
            "Delete everything removed every capture source and the allow-list " +
                "on screen still shows $pkg switched on: the holder is drawing " +
                "the database the delete replaced",
            reRead,
        )
    }

    private companion object {
        /** Covers a cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
