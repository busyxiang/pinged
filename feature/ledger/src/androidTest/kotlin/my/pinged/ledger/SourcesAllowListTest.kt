package my.pinged.ledger

import android.app.Application
import my.pinged.data.Databases
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import my.pinged.capture.Graph
import my.pinged.capture.SourceCounters
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.CaptureSource
import my.pinged.ledger.sources.SourceRow
import my.pinged.ledger.sources.SourcesScreen
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A package goes from discovered-and-discarded to enabled, through the screen,
 * into the database the listener reads.
 *
 * Against the real encrypted database, because two of the three things
 * asserted here are absences -- Room has never heard of the package, and it
 * holds no content for it -- and an absence proved against a stand-in store is
 * proved about the stand-in.
 */
@RunWith(AndroidJUnit4::class)
class SourcesAllowListTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = Databases.captureSourceDao(context)

    /**
     * No scope to cancel in `@After`: `SourcesViewModel` supplies its own
     * `viewModelScope`, and every gate a test here closes is released before
     * that test returns.
     */
    private fun sourcesViewModel() =
        SourcesViewModel(context.applicationContext as Application)

    /**
     * Not a real package, and not in the pack, so it lands in the lower list.
     *
     * **Unique per run, and not tidiness.** A test that enables this identifier
     * leaves the row behind: `capture_source` has no delete, so it outlives the
     * test on a device whose app data survives the next `install -r`, and one
     * test asserts Room has never heard of it. With a fixed identifier the suite
     * passed once and then failed on every later run against the same emulator.
     */
    private val pkg = "my.pinged.probe.notabank.p" + System.nanoTime()

    @After fun leaveTheAllowListAsItWasFound() {
        dao.setEnabled(pkg, false)
    }

    @Test fun enablingASourceThroughTheScreenWritesTheAllowList() {
        dao.setEnabled(pkg, false)
        // Stage 1, spec 9.6: a package that has posted something and is not
        // enabled leaves a count in DataStore and nothing at all in Room.
        runBlocking {
            SourceCounters.countOne(context, pkg, Arrival.POSTED, System.currentTimeMillis())
        }
        assertNull(
            "Stage one wrote a capture_source row for a package the user has " +
                "never enabled, which is the record spec 9.6 says must not exist",
            dao.byPackage(pkg),
        )
        assertTrue(runBlocking { SourceCounters.seenCount(context, pkg) } > 0)

        val viewModel = sourcesViewModel()
        compose.setContent { PingedTheme { SourcesScreen(viewModel, onBack = {}) } }

        // The identifier is what is drawn: PackageManager cannot resolve a
        // label for this, and on API 30+ that is the expected case for
        // anything outside <queries> (spec 9.6).
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithTextSafely(pkg) > 0
        }
        compose.onNodeWithText(pkg).performClick()

        compose.waitUntil(TIMEOUT) { dao.byPackage(pkg)?.enabled == true }

        val row = requireNotNull(dao.byPackage(pkg))
        assertTrue("The row exists but the allow-list gate is still closed", row.enabled)
        assertEquals(
            "insertIfNew did not create the row, so setEnabled updated nothing",
            pkg,
            row.pkg,
        )
        assertTrue(
            "The allow-list and enabled() must agree. The listener gate reads " +
                "byPackage(pkg)?.enabled, not this list, so a row visible to one " +
                "and not the other means the settings screen and the gate " +
                "disagree about what is being captured",
            dao.enabled().any { it.pkg == pkg },
        )
        assertFalse(
            "Enabling a source must not silently mark it authoritative: spec " +
                "7.2 makes that the tie-breaker for duplicate pairs",
            row.isAuthoritative,
        )
    }

    /**
     * A bank outside the parse pack is discovered by posting something and enabled
     * from the lower list -- which is headed "SEEN RECENTLY, NOT CAPTURED" and
     * prints "NOT ONE WORD STORED" on every row. Leaving an enabled source there
     * says, beside a growing count, that nothing from it is stored, while stage one
     * writes its full text to `raw_capture`.
     *
     * Driving the view model rather than the screen: what is asserted is which list
     * the row is in, and a Compose text assertion cannot tell "not in the lower
     * list" from "scrolled off".
     */
    @Test fun anEnabledSourceLeavesTheNotCapturedSection() {
        dao.setEnabled(pkg, false)
        runBlocking {
            SourceCounters.countOne(context, pkg, Arrival.POSTED, System.currentTimeMillis())
        }
        val viewModel = sourcesViewModel()

        viewModel.refresh()
        val whileDisabled = awaitRow(viewModel) { it.seenNotCaptured }
        assertEquals(
            "A disabled, discovered package belongs in the lower list",
            pkg,
            whileDisabled.pkg,
        )
        assertFalse(whileDisabled.enabled)

        viewModel.setEnabled(pkg, true)

        val whileEnabled = awaitRow(viewModel) { it.suggested }
        assertTrue(
            "An enabled source has to move above the tear: the lower section " +
                "claims NOT ONE WORD STORED and its text is now stored",
            whileEnabled.enabled,
        )
        assertTrue(
            "The enabled source is still listed under SEEN RECENTLY, NOT CAPTURED",
            viewModel.state.value.seenNotCaptured.none { it.pkg == pkg },
        )
        assertTrue(
            "Counting stopped at the gate, so the row it moved to reads 0 SEEN",
            whileEnabled.seenCount > 0,
        )
    }

    /**
     * Two taps on one toggle: the second one is what the user meant.
     *
     * On, then off, is a normal way to change your mind, and a `launch` per tap
     * orders nothing. SQLite serialising the statements does not help -- it
     * serialises them in whichever order the threads arrive -- so the first tap's
     * write can land last and leave the source capturing after the user switched
     * it off, with the toggle showing the database's answer rather than theirs.
     *
     * The gate is how this is made to happen rather than waited for: both
     * operations otherwise take the same few milliseconds, so a test without a seam
     * would pass either way.
     */
    @Test fun theSecondTapOnAToggleIsTheOneThatSticks() {
        dao.setEnabled(pkg, false)
        // Discovered by posting something, so the row is drawn whichever way
        // the two taps resolve -- the state assertion below has to be able to
        // find it in the losing case as well as the winning one.
        runBlocking {
            SourceCounters.countOne(context, pkg, Arrival.POSTED, System.currentTimeMillis())
        }
        val viewModel = sourcesViewModel()

        val firstReachedTheGate = CompletableDeferred<Unit>()
        val releaseTheGate = CompletableDeferred<Unit>()
        val isTheFirst = AtomicBoolean(true)
        viewModel.beforeEachOperation = {
            if (isTheFirst.compareAndSet(true, false)) {
                firstReachedTheGate.complete(Unit)
                releaseTheGate.await()
            }
        }

        val first = viewModel.setEnabled(pkg, true)
        runBlocking { withTimeout(TIMEOUT) { firstReachedTheGate.await() } }
        val second = viewModel.setEnabled(pkg, false)

        // Give the second tap every chance to land its write first, which is
        // what an unordered view model lets it do. Ordered, it cannot: it is
        // waiting on the job that is holding the gate, so this waits out its
        // budget and costs the suite that long, once.
        runBlocking { withTimeoutOrNull(SECOND_TAP_BUDGET) { second.join() } }
        releaseTheGate.complete(Unit)
        runBlocking { withTimeout(TIMEOUT) { first.join(); second.join() } }

        assertFalse(
            "The first tap's write landed last, so the source is capturing and " +
                "the last thing the user did was switch it off",
            requireNotNull(dao.byPackage(pkg)).enabled,
        )
        assertFalse(
            "The screen is drawing a state the user did not ask for",
            viewModel.state.value.suggested.plus(viewModel.state.value.seenNotCaptured)
                .first { it.pkg == pkg }.enabled,
        )
    }

    /**
     * Cancelling something in the middle of the queue must not let the rest of the
     * queue past.
     *
     * A chain built on `previous.join()` does not hold, and both halves of that
     * are traps: `Job.join()` is cancellable, and a cancelled `Job` *completes*.
     * Cancelling a link that is still **waiting** then releases its successor
     * immediately, while the cancelled link's own predecessor is still running.
     *
     * The shape below is the minimum that shows it: one operation holding the
     * queue, one cancelled while waiting for it, and one behind that, which must
     * not start.
     */
    @Test fun cancellingAQueuedOperationDoesNotReleaseTheOnesBehindIt() {
        val viewModel = sourcesViewModel()

        val entered = AtomicInteger(0)
        val firstReachedTheGate = CompletableDeferred<Unit>()
        val releaseTheGate = CompletableDeferred<Unit>()
        viewModel.beforeEachOperation = {
            if (entered.incrementAndGet() == 1) {
                firstReachedTheGate.complete(Unit)
                releaseTheGate.await()
            }
        }

        val holding = viewModel.refresh()
        runBlocking { withTimeout(TIMEOUT) { firstReachedTheGate.await() } }

        val cancelled = viewModel.refresh()
        cancelled.cancel()
        val behind = viewModel.refresh()

        // Long enough that an unchained third operation would have finished a
        // whole read; chained, it cannot have begun one.
        runBlocking { withTimeoutOrNull(SECOND_TAP_BUDGET) { behind.join() } }
        assertEquals(
            "An operation queued behind a cancelled one ran while the operation " +
                "*it* was queued behind was still holding the queue",
            1,
            entered.get(),
        )

        releaseTheGate.complete(Unit)
        runBlocking { withTimeout(TIMEOUT) { holding.join() } }
    }

    /**
     * The job `refresh()` hands back does not complete until its read has
     * published, even when another refresh follows it.
     *
     * The trap underneath is that `refresh()` cancels the read in flight and
     * `Job.join()` on a cancelled job returns immediately, so a caller
     * sequencing a write after a read can be released by the cancellation
     * rather than by the read. `MainActivity.onResume` rested on this and
     * probes the database itself now, so the contract stays asserted here
     * rather than in `MainActivity`, which has no test source set.
     */
    @Test fun aRefreshJobDoesNotCompleteUntilItsReadHasPublished() {
        val viewModel = sourcesViewModel()

        val reachedTheGate = CompletableDeferred<Unit>()
        val releaseTheGate = CompletableDeferred<Unit>()
        val isTheFirst = AtomicBoolean(true)
        viewModel.beforeEachOperation = {
            if (isTheFirst.compareAndSet(true, false)) {
                reachedTheGate.complete(Unit)
                releaseTheGate.await()
            }
        }

        val first = viewModel.refresh()
        runBlocking { withTimeout(TIMEOUT) { reachedTheGate.await() } }
        assertFalse("precondition: nothing has been published yet", viewModel.state.value.loaded)

        viewModel.refresh()

        val completed = runBlocking {
            withTimeoutOrNull(SECOND_TAP_BUDGET) { first.join() } != null
        }
        assertFalse(
            "The job returned by refresh() completed while its read was still " +
                "held, so anything sequencing a write after a read sees the " +
                "previous answer",
            completed && !viewModel.state.value.loaded,
        )

        releaseTheGate.complete(Unit)
        runBlocking { withTimeout(TIMEOUT) { first.join() } }
        assertTrue(viewModel.state.value.loaded)
    }

    /** The row for [pkg] in the list [pick] selects, once the read has landed. */
    private fun awaitRow(
        viewModel: SourcesViewModel,
        pick: (SourcesState) -> List<SourceRow>,
    ): SourceRow {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (state.loaded) pick(state).firstOrNull { it.pkg == pkg }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("$pkg never appeared. Last state: ${viewModel.state.value}")
    }

    private companion object {
        private const val TIMEOUT = 10_000L

        /**
         * How long the second tap is given to overtake the first. Long enough
         * that an unordered view model reliably does -- its whole body is one
         * UPDATE and one read -- and paid in full on every ordered run.
         */
        private const val SECOND_TAP_BUDGET = 1_500L
    }
    /**
     * The screen's claim about stored text has to come off the database, not off
     * which list the row landed in.
     *
     * `last_notification_at` is written one statement before the capture, past the
     * allow-list gate, so it is the record of "text was stored for this package". A
     * row carrying it that is now disabled is the case that used to render "NOT ONE
     * WORD STORED" over a `raw_capture` full of that package's notifications.
     */
    @Test fun aDisabledSourceThatOnceCapturedIsMarkedAsStillHoldingText() {
        val pkg = "my.pinged.probe.former.p" + System.nanoTime()
        dao.insertIfNew(
            CaptureSource(
                pkg = pkg,
                label = "Former Bank",
                enabled = false,
                firstSeenAt = System.currentTimeMillis(),
            ),
        )
        dao.setLastNotificationAt(pkg, System.currentTimeMillis())
        runBlocking { SourceCounters.countOne(context, pkg, Arrival.POSTED, System.currentTimeMillis()) }

        val viewModel = sourcesViewModel()
        viewModel.refresh()
        val row = awaitRow(viewModel, pkg)

        assertFalse("precondition: the source is off", row.enabled)
        assertTrue(
            "The row says nothing was stored while capture_source records that it was",
            row.textStored,
        )
    }

    private fun awaitRow(viewModel: SourcesViewModel, pkg: String): SourceRow {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val state = viewModel.state.value
            if (state.loaded) {
                (state.suggested + state.seenNotCaptured).firstOrNull { it.pkg == pkg }?.let { return it }
            }
            Thread.sleep(50)
        }
        throw AssertionError("The screen never listed $pkg")
    }

    /**
     * [aRefreshJobDoesNotCompleteUntilItsReadHasPublished]'s contract on the
     * ungated path: with nothing holding the queue, the job still completes
     * only after the state its read produced is published.
     */
    @Test fun theRefreshJobCompletesOnlyAfterItsStateIsPublished() {
        val viewModel = sourcesViewModel()
        assertFalse("precondition: nothing read yet", viewModel.state.value.loaded)

        runBlocking { viewModel.refresh().join() }

        assertTrue(
            "The job finished before the read it performed was visible, so anything " +
                "ordered after it sees the previous answer",
            viewModel.state.value.loaded,
        )
    }

}

/**
 * `onAllNodesWithText(...).fetchSemanticsNodes().size`, without the import
 * churn -- and without `assertIsDisplayed`, which throws rather than returning
 * false and so cannot be used inside a `waitUntil` predicate.
 *
 * `internal`, so the other suites in this module use this one rather than
 * keeping a copy of the same three calls each.
 */
internal fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextSafely(
    text: String,
): Int = onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
