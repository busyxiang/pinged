package my.pinged.ledger

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import my.pinged.capture.Corrections
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.RejectRuleRow
import my.pinged.data.dao.TaughtRuleRow
import my.pinged.data.dao.UnreadCapture
import my.pinged.parse.Direction
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.categories.CategoriesViewModel
import my.pinged.ledger.corrections.CorrectionsViewModel
import my.pinged.ledger.learned.LearnedViewModel
import my.pinged.ledger.review.ReviewAnswer
import my.pinged.ledger.review.ReviewCard
import my.pinged.ledger.review.ReviewViewModel
import my.pinged.ledger.unread.UnreadViewModel
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What each holder moved onto `:core:read` asks of it when a tap writes
 * (#300, #296 decisions 4 and 5): the write, then the read of the same screen
 * in the same turn, and a refusal raised through the module.
 * Through `ScriptedRunner`, which answers inline on the test's thread.
 *
 * The in-memory ledger is empty, so a write changes no row: what is asserted
 * is what was asked of the runner and in what order. What a write does to real
 * rows is the device suites'.
 */
@RunWith(RobolectricTestRunner::class)
class WritesReadBackTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)

    /** The calls the runner has seen, as `write what` or `read what`, in order. */
    private fun calls(): List<String> = runner.calls.map { (if (it.write) "write " else "read ") + it.what }

    private fun assertWroteThenReadOnce(write: String, read: String) {
        assertEquals("The write was not followed by exactly one read, in order", listOf("write $write", "read $read"), calls())
        assertTrue("The write did not run", runner.calls[0].ran)
        assertTrue("The read did not run", runner.calls[1].ran)
    }

    @Test fun aDeletedLearnedMerchantIsReadBackInItsOwnTurn() {
        LearnedViewModel(app, runner).delete(LearnedMerchant("MR DIY", "Mr DIY", 1L, "Shopping", 0))

        assertWroteThenReadOnce("The learned merchant could not be deleted", LEARNED)
    }

    @Test fun aDeletedIgnoredMessageIsReadBackInItsOwnTurn() {
        LearnedViewModel(app, runner).deleteIgnored(RejectRuleRow(1L, "p", "sample", 0L, null, 0))

        assertWroteThenReadOnce("The ignored message could not be deleted", LEARNED)
    }

    @Test fun aDeletedTaughtMessageIsReadBackInItsOwnTurn() {
        LearnedViewModel(app, runner).deleteMessage(TaughtRuleRow(1L, "p", "sample", Direction.EXPENSE, false, 0, null))

        assertWroteThenReadOnce("The taught message could not be deleted", LEARNED)
    }

    @Test fun aDismissedUnreadCaptureIsReadBackInItsOwnTurn() {
        UnreadViewModel(app, runner).neverATransaction(UnreadCapture(1L, "p", 0L, "t", "x", null, null))

        assertWroteThenReadOnce("The notification could not be marked as never a transaction", UNREAD)
    }

    @Test fun anAddedCategoryIsReadBackInItsOwnTurnAndARefusalIsRaisedByTheModule() {
        val model = CategoriesViewModel(app, runner)
        runner.refusing = true

        model.add("Kucing", "dog")

        assertEquals(
            "A refused write was not followed by the read that says whether the ledger is still unreadable",
            listOf("write The category could not be added", "read $CATEGORIES"),
            calls(),
        )
        assertTrue("A refused write did not raise storage-unavailable", model.read.state.value.unavailable)
    }

    @Test fun anAnsweredCorrectionIsReadBackInItsOwnTurn() {
        val txn = ledgerTxn(amountSen = 1_000L)
        val correction = Corrections.Correction(1L, 1, 2, null, "rule", txn, txn, emptySet())

        CorrectionsViewModel(app, runner, Dispatchers.Unconfined).decline(correction)

        assertWroteThenReadOnce("The correction could not be saved", CORRECTIONS)
    }

    @Test fun aReviewAnswerIsReadBackOnceInItsOwnTurn() {
        val model = ReviewViewModel(app, runner)
        val card = ReviewCard(ledgerTxn(amountSen = 1_000L, state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD))

        model.answer(card, ReviewAnswer.ACCEPT)

        assertWroteThenReadOnce("The payment could not be accepted", INBOX)
    }

    @Test fun aRefusedReviewAnswerOffersNoUndoAndRaisesStorageUnavailable() {
        val model = ReviewViewModel(app, runner)
        val card = ReviewCard(ledgerTxn(amountSen = 1_000L, state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD))
        runner.refusing = true

        model.answer(card, ReviewAnswer.ACCEPT)

        assertNull("An answer that was refused is on offer for Undo", model.inbox.undoable.value)
        assertTrue("A refused answer did not raise storage-unavailable", model.inbox.read.state.value.unavailable)
        assertFalse("A refused answer wrote", runner.calls[0].ran)
    }

    private companion object {
        const val LEARNED = "Learned merchants cannot be read"
        const val UNREAD = "The notifications Pinged could not read cannot be read"
        const val CATEGORIES = "Categories cannot be read"
        const val CORRECTIONS = "Corrections cannot be read"
        const val INBOX = "The review inbox cannot be read"
    }
}
