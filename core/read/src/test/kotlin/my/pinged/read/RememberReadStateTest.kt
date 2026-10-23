package my.pinged.read

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import my.pinged.data.Databases
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The composable entry point's triggers (#296, decision 7): a screen shown
 * reads, and reads again when `Databases.rewrites` moves or its own trigger
 * does, with no wiring of its own.
 *
 * The compose rule's activity is resumed, so the first composition is a
 * resume.
 */
@RunWith(RobolectricTestRunner::class)
class RememberReadStateTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())
    private val read = ScreenRead.fixed(scope, runner, "The test cannot be read") { "read" }

    private var alsoOn by mutableStateOf(0)
    private var drawn: ReadState<Unit, String>? = null

    @After fun end() {
        scope.cancel()
    }

    private fun show() {
        compose.setContent { drawn = read.rememberReadState(alsoOn) }
        compose.waitForIdle()
    }

    @Test fun aScreenShownReadsOnceAndDrawsTheAnswer() {
        show()

        assertEquals("A shown screen did not read exactly once", 1, runner.calls.size)
        assertEquals("The screen was not handed the answer", "read", drawn?.answer)
    }

    @Test fun aChangedTriggerReadsAgain() {
        show()

        alsoOn = 1
        compose.waitForIdle()

        assertEquals("A changed trigger did not read again, or read twice", 2, runner.calls.size)
    }

    @Test fun aDeleteOrRestoreRewritingTheLedgerReadsAgain() {
        show()

        Databases.reset()
        compose.waitForIdle()

        assertEquals("A rewrite of the ledger did not read the screen again, or read twice", 2, runner.calls.size)
    }
}
