package my.pinged.ledger.entry

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.ledger.Withheld
import my.pinged.ledger.busyLine
import my.pinged.read.LedgerAccess
import my.pinged.read.LedgerReplacement
import my.pinged.read.test.ScriptedRunner
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen's one line for what stops Save (#376), drawn by the real
 * screen over its real holder, through `ScriptedRunner` and a claim of the
 * test's own: what the holder publishes and what the screen makes of it.
 */
@RunWith(AndroidJUnit4::class)
class EntryLedgerLineTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val claim = MutableStateFlow<LedgerReplacement?>(null)

    private fun show(): ManualEntryViewModel {
        val viewModel = ManualEntryViewModel(app, SavedStateHandle(), target = null, runner = runner, replacement = claim) {
            CompletableDeferred(null)
        }
        compose.setContent { ManualEntryScreen(viewModel, onBack = {}, onSaved = {}) }
        compose.waitForIdle()
        return viewModel
    }

    /**
     * Every other screen says a ledger that cannot be read over a replacement
     * running: there is nothing on screen that the replacement withholds.
     */
    @Test fun aLedgerThatCannotBeReadIsSaidInPlaceOfTheBusyLine() {
        claim.value = LedgerReplacement.RESTORE
        runner.refusing = true

        show()

        compose.onNodeWithText(CANNOT_READ_YOUR_DATA).performScrollTo()
        compose.onNodeWithText(busyLine(LedgerReplacement.RESTORE, Withheld.SAVED)).assertDoesNotExist()
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled()
    }

    /**
     * A save that meets a delete's or a restore's gate -- its claim landed
     * after Save was asked for, or the gate refused what the claim had not --
     * is the ledger being replaced, as every other screen says it, and not a
     * ledger that cannot be read. The draft stays, Save off.
     *
     * Only the write is refused, and the screen is read before its read-back
     * answers, so what is drawn is the write's refusal and no other call's.
     */
    @Test fun aSaveTheGateTurnsAwaySaysTheLedgerIsBeingReplacedAndKeepsTheDraft() {
        val viewModel = show()
        viewModel.amount(1_250L)
        runner.holding = true
        val asked = runner.calls.size

        val saving = viewModel.save()
        val nearby = awaitCall(asked)
        assertFalse("Save's first call was not the nearby-capture lookup", nearby.write)
        nearby.release()
        val write = awaitCall(asked + 1)
        assertTrue("Save's second call was not its write", write.write)
        write.replace()
        val readBack = awaitCall(asked + 2)
        compose.waitForIdle()

        assertEquals(LedgerAccess.BeingReplaced, viewModel.state.value.ledger)
        compose.onNodeWithText(REPLACING_YOUR_DATA).performScrollTo()
        compose.onNodeWithText(CANNOT_READ_YOUR_DATA).assertDoesNotExist()
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled()
        assertEquals("The draft was not kept", 1_250L, viewModel.state.value.draft.amountSen)

        readBack.replace()
        awaitUntil { saving.isCompleted }
    }

    /**
     * The runner's call at [index], once it is asked for. Save cleans the
     * merchant on `Dispatchers.IO`, which `waitForIdle` does not wait for, and
     * `compose.waitUntil` times out on the compose clock, not the wall's: so
     * polled on the wall's.
     */
    private fun awaitCall(index: Int): ScriptedRunner.Call {
        awaitUntil { runner.calls.size > index }
        return runner.calls[index]
    }

    private fun awaitUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + SAVE_MS
        while (!done() && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(10)
        }
        check(done()) { "Not done within ${SAVE_MS}ms; the runner was asked for ${runner.calls.map { it.what }}" }
    }

    private companion object {
        const val SAVE_MS = 10_000L
    }
}
