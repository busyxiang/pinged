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
import my.pinged.read.LedgerReplacement
import my.pinged.read.test.ScriptedRunner
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
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
}
