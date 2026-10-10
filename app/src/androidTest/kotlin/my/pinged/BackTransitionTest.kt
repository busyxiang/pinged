package my.pinged

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import kotlin.math.abs
import my.pinged.ledger.sources.BACK_DESCRIPTION
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #56: leaving a pushed screen looks the same whether the chevron or the
 * system back gesture does it.
 *
 * `NavDisplay` takes two specs for a pop -- `popTransitionSpec` for a plain
 * pop, `predictivePopTransitionSpec` for the gesture -- and their library
 * defaults differ: a cross-fade against a scale to 0.7 on a spring. Each test
 * opens Sources, pauses the Compose clock, starts the pop its own way, advances
 * [SAMPLE_MS] and captures the frame. The two frames must match. A scale and a
 * fade disagree over a large share of the screen; semantics bounds cannot be
 * used instead, because they ignore a graphics layer's scale.
 *
 * The gesture is the activity's own dispatcher fed the events the system would
 * send, so it needs no gesture navigation on the device.
 */
class BackTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** The frame [SAMPLE_MS] into a pop that [pop] starts from the open Sources screen. */
    private fun frameAfterPop(pop: () -> Unit): ImageBitmap {
        // Settings is the shown tab on the second call, where its tab is selected
        // already; clicking it again is a no-op.
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("Capture sources").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        pop()
        compose.mainClock.advanceTimeBy(SAMPLE_MS)
        return compose.onRoot().captureToImage()
    }

    private fun chevronFrame() =
        frameAfterPop { compose.onNodeWithContentDescription(BACK_DESCRIPTION).performClick() }

    private fun gestureFrame() = frameAfterPop {
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
        val chevron = chevronFrame()
        // Back on Settings' root with the clock running again, so the second pop
        // starts from the same screen the first did.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val gesture = gestureFrame()
        val share = differingShare(chevron, gesture)
        assertTrue("chevron and gesture frames differ over $share of the screen", share < MAX_DIFFERING_SHARE)
    }

    private companion object {
        const val SAMPLE_MS = 100L

        /**
         * Measured on the API 37 emulator: 2.8% with one spec for both pops, which
         * is the chevron's pressed highlight and a few ms of timing; 12.2% with the
         * library defaults (fade against scale).
         */
        const val MAX_DIFFERING_SHARE = 0.05f
    }
}
