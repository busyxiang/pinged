package my.pinged.ledger

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import my.pinged.ui.test.boundedWaitUntil
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [boundedWaitUntil] fails at its timeout while its condition is blocked in
 * an idle wait that never ends, where a bare `waitUntil` hangs: with the
 * watchdog disabled this test held the emulator until `emu --max-hold 180`
 * stopped it. Run it that way when changing the helper.
 *
 * Espresso's own policies end the plain never-idle shapes, measured here: a
 * main queue that never drains throws `AppNotIdleException` at 60s, and a
 * Compose frame loop (`withFrameNanos` forever) `ComposeNotIdleException` at
 * 26s. Neither ends a state that is idle at one check and busy at the next:
 * each inner wait succeeds, and the loop around them, which re-checks, has
 * no clock. [flicker] is that state, and hangs in the shape of 2026-10-19's
 * thread dump from `EditRouteTest`: the main thread in
 * `loopMainThreadUntilIdle`, the test thread in `FutureTask.get`.
 */
@RunWith(AndroidJUnit4::class)
class BoundedWaitTest {
    @get:Rule val compose = createComposeRule()

    /** Cleared before the test returns, so the rule's teardown can reach idle. */
    private val spinning = AtomicBoolean(true)

    /** Busy at every other check while [spinning]. */
    private val flicker = object : IdlingResource {
        private var idle = true
        override val isIdleNow: Boolean
            get() {
                if (!spinning.get()) return true
                idle = !idle
                return idle
            }
    }

    @Test fun aConditionThatNeverReachesIdleFailsAtTheTimeout() {
        compose.setContent { BasicText("spinning") }
        // After setContent, which waits for idle itself.
        compose.registerIdlingResource(flicker)
        val started = System.nanoTime()
        try {
            compose.boundedWaitUntil(WAIT) { compose.onAllNodesWithText(ABSENT).fetchSemanticsNodes().isNotEmpty() }
            fail("A wait for a node that is never drawn succeeded")
        } catch (e: AssertionError) {
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("Failed after ${tookMillis}ms, not near the ${WAIT}ms timeout", tookMillis < WAIT + SLACK)
            assertTrue("Failed for another reason: ${e.message}", e.message.orEmpty().contains("never went idle"))
        } finally {
            spinning.set(false)
            compose.unregisterIdlingResource(flicker)
        }
    }

    private companion object {
        const val WAIT = 3_000L

        /** The watchdog's wake-up and the interrupt's unwinding, with room for a loaded emulator. */
        const val SLACK = 5_000L

        const val ABSENT = "never drawn"
    }
}
