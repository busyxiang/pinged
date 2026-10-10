package my.pinged

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import kotlin.math.abs
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.day.DAY_BACK
import my.pinged.ledger.sources.BACK_DESCRIPTION
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #56: leaving a pushed screen looks the same whether the chevron or the
 * system back gesture does it: Sources, and `Day` on the Charts tab (#69).
 *
 * `NavDisplay` takes two specs for a pop -- `popTransitionSpec` for a plain
 * pop, `predictivePopTransitionSpec` for the gesture -- and their library
 * defaults differ: a cross-fade against a scale to 0.7 on a spring. Each test
 * opens its screen, pauses the Compose clock, starts the pop its own way, advances
 * [SAMPLE_MS] and captures the frame. The two frames must match. A scale and a
 * fade disagree over a large share of the screen; semantics bounds cannot be
 * used instead, because they ignore a graphics layer's scale.
 *
 * The gesture is the activity's own dispatcher fed the events the system would
 * send, so it needs no gesture navigation on the device.
 */
class BackTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** The rows [leavingADayPlaysTheSameTransitionEitherWay] wrote are not the next class's. */
    @After fun leaveAnOpenableDatabase() {
        Databases.reset()
        compose.activity.deleteDatabase(DatabaseFactory.NAME)
    }

    /** The frame [SAMPLE_MS] into a pop that [pop] starts from the screen [open] opens. */
    private fun frameAfterPop(open: () -> Unit, pop: () -> Unit): ImageBitmap {
        open()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        pop()
        compose.mainClock.advanceTimeBy(SAMPLE_MS)
        return compose.onRoot().captureToImage()
    }

    /**
     * Settings, then Sources. Settings is the shown tab on the second call,
     * where its tab is selected already; clicking it again is a no-op.
     */
    private fun openSources() {
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("Capture sources").performClick()
    }

    /**
     * Charts, then today's square, which is tappable on any month that has a
     * history start (#69). Charts is the shown tab on the second call.
     */
    private fun openToday() {
        compose.onNodeWithText("CHARTS").performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithTag(TODAY_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TODAY_TAG).performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithContentDescription(DAY_BACK).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun chevronFrame(open: () -> Unit, chevron: String) =
        frameAfterPop(open) { compose.onNodeWithContentDescription(chevron).performClick() }

    private fun gestureFrame(open: () -> Unit) = frameAfterPop(open) {
        // The dispatcher `NavDisplay` listens on. `OnBackPressedDispatcher`'s own
        // events do not reach its predictive path.
        val input = DirectNavigationEventInput()
        compose.runOnUiThread { compose.activity.navigationEventDispatcher.addInput(input) }
        // A frame between each event: `NavDisplay` follows the gesture from a
        // collector, which a single burst would collapse into a plain pop.
        compose.runOnUiThread { input.backStarted(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.02f, 0.5f, 0f)) }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnUiThread { input.backProgressed(NavigationEvent(NavigationEvent.EDGE_LEFT, 0.02f, 0.5f, 0f)) }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnUiThread { input.backCompleted() }
    }

    /** Share of pixels whose channels differ by more than a few levels. */
    private fun differingShare(a: ImageBitmap, b: ImageBitmap): Float {
        val x = a.asAndroidBitmap()
        val y = b.asAndroidBitmap()
        assertTrue("frames differ in size", x.width == y.width && x.height == y.height)
        val pa = IntArray(x.width * x.height).also { x.getPixels(it, 0, x.width, 0, 0, x.width, x.height) }
        val pb = IntArray(pa.size).also { y.getPixels(it, 0, y.width, 0, 0, y.width, y.height) }
        var differing = 0
        for (i in pa.indices) {
            val d = abs((pa[i] shr 16 and 255) - (pb[i] shr 16 and 255)) +
                abs((pa[i] shr 8 and 255) - (pb[i] shr 8 and 255)) +
                abs((pa[i] and 255) - (pb[i] and 255))
            if (d > 12) differing++
        }
        return differing.toFloat() / pa.size
    }

    @Test fun theChevronAndTheGesturePlayTheSameTransition() {
        val chevron = chevronFrame(::openSources, BACK_DESCRIPTION)
        // Back on Settings' root with the clock running again, so the second pop
        // starts from the same screen the first did.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val gesture = gestureFrame(::openSources)
        val share = differingShare(chevron, gesture)
        assertTrue("chevron and gesture frames differ over $share of the screen", share < MAX_DIFFERING_SHARE)
    }

    /** The same, leaving `Day` for Charts (#69). */
    @Test fun leavingADayPlaysTheSameTransitionEitherWay() {
        // A history start, so Charts draws the grid rather than its empty state,
        // and a screenful of rows: over a near-empty `Day` the library's fade
        // and scale differed by less than [MAX_DIFFERING_SHARE], so this passed
        // with the two specs apart. With the rows they differ over 17.6%.
        val today = LocalDates.of(LocalDate.now())
        Databases.captureDayDao(compose.activity).recordListenerBound(today, true)
        Databases.txnDao(compose.activity).insertAll(
            (1..ROWS).map { n ->
                Txn(
                    rawCaptureId = null,
                    amountSen = n * 1_000L,
                    direction = Direction.EXPENSE,
                    occurredAt = n * 1_000L,
                    localDate = today,
                    merchantRaw = "WARUNG PROBE $n",
                    merchantDisplay = "Warung Probe $n",
                    merchantKey = "WARUNG PROBE $n",
                    categoryId = 1L,
                    sourcePackage = "my.com.tngdigital.ewallet",
                    sourceLabel = "Touch 'n Go eWallet",
                    confidence = Confidence.HIGH,
                    state = TxnState.COMMITTED,
                    createdAt = n * 1_000L,
                    updatedAt = n * 1_000L,
                )
            },
        )
        val chevron = chevronFrame(::openToday, DAY_BACK)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val gesture = gestureFrame(::openToday)
        val share = differingShare(chevron, gesture)
        assertTrue("chevron and gesture frames differ over $share of the screen", share < MAX_DIFFERING_SHARE)
    }

    private companion object {
        const val SAMPLE_MS = 100L

        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L

        /** Rows on today, enough to fill the `Day` screen. */
        const val ROWS = 15

        /** Today's grid square, by the tag `RhythmGridSection` gives it. */
        val TODAY_TAG = "grid-day-${LocalDate.now()}"

        /**
         * Measured on the API 37 emulator: 2.8% with one spec for both pops, which
         * is the chevron's pressed highlight and a few ms of timing; 12.2% with the
         * library defaults (fade against scale).
         */
        const val MAX_DIFFERING_SHARE = 0.05f
    }
}
