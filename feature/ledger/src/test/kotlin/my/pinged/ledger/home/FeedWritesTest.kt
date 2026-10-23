package my.pinged.ledger.home

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import my.pinged.read.rememberReadState
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * The feed's *write seen* signal as one more trigger of Spending's summary
 * read (#299, #296 decision 10), with today's rule: only when a write was
 * seen, so a source replaced for damage does not read again, and read again
 * and so on (`DamagedFeedTest` measures that on a device).
 *
 * The compose rule's activity is resumed, so the first composition is the
 * screen's one resume read.
 */
@RunWith(RobolectricTestRunner::class)
class FeedWritesTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())
    private val today = LocalDate.of(2026, 10, 14)
    private val spending = SpendingRead(scope, runner, SavedStateHandle(), chooser = { ChooserRead() }, today = { today })
    private val writes = FeedWrites()

    @After fun end() {
        scope.cancel()
    }

    private fun show() {
        compose.setContent {
            val seen by writes.count.collectAsState()
            spending.read.rememberReadState(alsoOn = seen)
        }
        compose.waitForIdle()
    }

    @Test fun aWriteSeenIsReadAfterOnceTheFeedIsObservingAgain() {
        show()
        val calls = runner.calls.size

        writes.saw()
        writes.observing()
        compose.waitForIdle()

        assertEquals("A write the feed saw did not read the summary again, or read twice", calls + 1, runner.calls.size)
    }

    @Test fun aSourceReplacedWithNoWriteSeenReadsNothing() {
        show()
        val calls = runner.calls.size

        writes.observing()
        writes.observing()
        compose.waitForIdle()

        assertEquals("A feed that saw no write read the summary again", calls, runner.calls.size)
    }

    @Test fun oneWriteIsSpentByTheObservationThatFollowsIt() {
        show()
        writes.saw()
        writes.observing()
        compose.waitForIdle()
        val calls = runner.calls.size

        writes.observing()
        compose.waitForIdle()

        assertEquals("One write read the summary again for the second source as well", calls, runner.calls.size)
    }

    @Test fun aWriteSeenAfterAMonthWasPickedReadsThePickedMonthAndNotTheOneReplaced() {
        show()
        runner.holding = true
        spending.pick(SEPTEMBER, thisMonth = YearMonth.from(today))

        writes.saw()
        writes.observing()
        compose.waitForIdle()
        // Each release lets the next queued call begin, so release as they come.
        repeat(4) {
            runner.calls.toList().forEach { if (!it.finished) it.release() }
            compose.waitForIdle()
        }

        assertEquals(
            "The summary on screen is not of the month picked",
            SEPTEMBER,
            spending.read.state.value.answer?.summary?.month,
        )
    }

    private companion object {
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
    }
}
