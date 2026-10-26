package my.pinged.ledger

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.capture.Corrections
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.RejectRuleRow
import my.pinged.data.dao.TaughtRuleRow
import my.pinged.data.dao.UnreadCapture
import my.pinged.ledger.categories.CategoriesViewModel
import my.pinged.ledger.corrections.CorrectionsViewModel
import my.pinged.ledger.detail.DetailViewModel
import my.pinged.ledger.learned.LearnedViewModel
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferJob
import my.pinged.ledger.settings.TransferState
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.unread.UnreadViewModel
import my.pinged.parse.Direction
import my.pinged.read.ScreenRead
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Detail's own writes and the Settings sub-screens' during a ledger
 * replacement (#331): from the claim, each asks the runner for nothing,
 * raises no `unavailable`, and writes again once the replacement clears.
 * `RowActionsReplacingTest` holds the rows' half.
 *
 * `ScriptedRunner` has no gate, so these are the window between a restore's
 * claim and its gate, where a write asked of the runner would land and then
 * be wiped. The ledger is empty: what is asserted is whether a write was asked.
 */
@RunWith(RobolectricTestRunner::class)
class ReplacingRefusesWritesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)
    private val transfers = MutableStateFlow(TransferState())

    @Test fun detailsWritesAreRefusedDuringARestore() {
        refuses("The note could not be saved") { detail().let { it.read to { it.saveNote("lunch") } } }
        refuses("The payment could not be left out") { detail().let { it.read to { it.notSpending() } } }
        refuses("The payment could not be counted") { detail().let { it.read to { it.countAsSpending() } } }
        refuses("The payment could not be removed") { detail().let { it.read to { it.remove {} } } }
    }

    @Test fun categoriesWritesAreRefusedDuringARestore() {
        refuses("The category could not be added") { categories().let { it.read to { it.add("Kucing", "dog") } } }
        refuses("The category could not be changed") { categories().let { it.read to { it.edit(2L, "Kucing", "dog") } } }
        refuses("The category could not be deleted") { categories().let { it.read to { it.delete(2L) } } }
    }

    @Test fun learnedWritesAreRefusedDuringARestore() {
        refuses("The learned merchant could not be deleted") {
            learned().let { it.read to { it.delete(LearnedMerchant("MR DIY", "Mr DIY", 1L, "Shopping", 0)) } }
        }
        refuses("The ignored message could not be deleted") {
            learned().let { it.read to { it.deleteIgnored(RejectRuleRow(1L, "p", "sample", 0L, null, 0)) } }
        }
        refuses("The taught message could not be deleted") {
            learned().let { it.read to { it.deleteMessage(TaughtRuleRow(1L, "p", "sample", Direction.EXPENSE, false, 0, null)) } }
        }
    }

    @Test fun anAllowListToggleIsRefusedDuringADelete() {
        refuses("The allow-list cannot be changed", Operation.DELETE) {
            SourcesViewModel(app, runner, transfers).let { it.read to { it.setEnabled("my.bank", true) } }
        }
    }

    @Test fun correctionAnswersAreRefusedDuringARestore() {
        val txn = ledgerTxn(amountSen = 1_000L)
        val correction = Corrections.Correction(1L, 1, 2, null, "rule", txn, txn, emptySet())

        refuses("The correction could not be saved") { corrections().let { it.read to { it.accept(correction) } } }
        refuses("The correction could not be saved") { corrections().let { it.read to { it.decline(correction) } } }
    }

    @Test fun neverATransactionIsRefusedDuringARestore() {
        refuses("The notification could not be marked as never a transaction") {
            UnreadViewModel(app, runner, transfers).let { it.read to { it.neverATransaction(UnreadCapture(1L, "p", 0L, "t", "x", null, null)) } }
        }
    }

    private fun detail() = DetailViewModel(app, txnId = 1L, createdAt = 0L, runner, transfers)

    private fun categories() = CategoriesViewModel(app, runner, transfers)

    private fun learned() = LearnedViewModel(app, runner, transfers)

    private fun corrections() = CorrectionsViewModel(app, runner, Dispatchers.Unconfined, transfers)

    /**
     * The write [holder] makes, while [operation] runs, asks the runner for
     * nothing and leaves its read readable; once it clears, the same write is
     * asked for as [what].
     *
     * A holder per write, and that second write held rather than run: run, its
     * read-back would read Detail's chooser off the real `Databases`, which
     * has no SQLCipher under Robolectric, and the throw would land in the next
     * test's `runTest`. A held write keeps its holder's turn, so a second
     * write on the same holder would wait behind it and never be asked.
     */
    private fun refuses(
        what: String,
        operation: Operation = Operation.RESTORE,
        holder: () -> Pair<ScreenRead<*, *>, () -> Unit>,
    ) {
        runner.holding = false
        transfers.value = TransferState(running = TransferJob.Running(operation, 0))
        val (read, write) = holder()
        val before = runner.calls.size

        write()

        assertEquals(
            "\"$what\" during a ledger replacement asked the runner for something",
            emptyList<String>(),
            runner.calls.drop(before).map { it.what },
        )
        assertFalse("\"$what\" refused for a replacement raised unavailable", read.state.value.unavailable)

        transfers.value = TransferState()
        runner.holding = true
        val after = runner.calls.size
        write()
        assertTrue(
            "\"$what\" was not asked for once the replacement cleared",
            runner.calls.drop(after).any { it.write && it.what == what },
        )
    }
}
