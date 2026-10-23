package my.pinged.ledger.review

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.capture.REVIEW_THRESHOLD_SEN
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.refreshed
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.parse.Direction
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.2's Accept all against the real encrypted database (#114): which
 * cards it commits, the one Undo that takes the whole batch back, and the
 * net its button names, and the swipe and the button on [ReviewScreen]. The
 * transaction itself is `SettleAllTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class AcceptAllTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun acceptAllCommitsEveryPlainCardWithinTheLimitAndNoOtherAndOneUndoReturnsThemAll() = runBlocking {
        val dao = context.freshLedger()
        // RM500.00 exactly is within it: the gate's test is strictly greater than.
        val batched = listOf(
            dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON, amountSen = REVIEW_THRESHOLD_SEN)),
            dao.insert(pending(PendingReason.MERCHANT_MISSING, at = NOON + 1, amountSen = 12_000L)),
            dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 2, amountSen = 3_000L)),
        )
        // Over the limit whatever the stored reason (#223): the gate stores
        // rule review over over threshold, so the reason alone cannot say.
        val overLimit = listOf(
            dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON + 3, amountSen = 129_900L)),
            dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 4, amountSen = REVIEW_THRESHOLD_SEN + 1)),
        )
        val transfer = dao.insert(pending(PendingReason.TRANSFER_SUSPECT, at = NOON + 5))
        // No capture, and an amount no other row has, so no manual entry
        // pairs with it: drawn as a plain card (#116), and still never in
        // the batch.
        val duplicate = dao.insert(pending(PendingReason.DUPLICATE_SUSPECT, at = NOON + 6, amountSen = 7_700L))
        val left = overLimit + transfer + duplicate
        val before = (batched + left).associateWith { dao.byId(it)!! }
        val inbox = inbox()
        assertEquals(
            "precondition: the pairless duplicate is not drawn as a plain card",
            CardKind.PLAIN,
            inbox.cards!!.single { it.txn.id == duplicate }.kind,
        )
        assertEquals(
            "The button does not name the batch alone and the two cards it leaves to check",
            "Accept 3 · RM650.00 · 2 over RM500 left to check",
            AcceptAll.of(inbox.cards!!)?.label,
        )

        inbox.acceptAll().join()

        assertEquals(
            "Accept all did not count every plain card within the limit",
            batched.map { TxnState.COMMITTED to null },
            batched.map { id -> dao.byId(id)!!.let { it.state to it.pendingReason } },
        )
        assertEquals(
            "Accept all touched a card over the check limit, a transfer or an unpaired duplicate card",
            left.map { before.getValue(it) },
            left.map { dao.byId(it)!! },
        )
        assertEquals(
            "The batch's Undo does not name it, or names the cards it left out",
            "ACCEPTED 3 · RM650.00",
            inbox.undoable.value?.said,
        )

        inbox.undo().join()

        assertEquals(
            "One Undo did not return every card of the batch exactly as it was, reasons included",
            before.values.toList(),
            before.keys.map { id -> dao.byId(id)!!.copy(updatedAt = before.getValue(id).updatedAt) },
        )
    }

    @Test fun theNetCountsMyrPlainCardsOnlyAndRefundsSubtract() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(pending(PendingReason.MERCHANT_MISSING, at = NOON, amountSen = 45_000L))
        dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1, amountSen = 5_000L).copy(direction = Direction.REFUND))
        dao.insert(pending(PendingReason.TRANSFER_SUSPECT, at = NOON + 2, amountSen = 10_000L))
        dao.insert(pending(PendingReason.DUPLICATE_SUSPECT, at = NOON + 3, amountSen = 2_000L))
        dao.insert(pending(PendingReason.TRANSFER_SUSPECT, at = NOON + 4, amountSen = 3_000L).copy(currency = "USD"))
        val inbox = inbox()

        assertEquals(
            "The net is not RM450.00 less the RM50.00 refund, over the two plain cards alone",
            "Accept 2 · RM400.00",
            AcceptAll.of(inbox.cards!!)?.label,
        )

        dao.insert(pending(PendingReason.MERCHANT_MISSING, at = NOON + 5, amountSen = 1_200L).copy(currency = "USD"))
        inbox.read.refreshed()

        assertEquals(
            "A plain card in another currency did not drop the sum, which would add dollars to ringgit",
            "Accept 3",
            AcceptAll.of(inbox.cards!!)?.label,
        )
    }

    @Test fun onTheScreenASwipeAndAcceptAllWriteAndUndoTakesTheBatchBack() {
        val dao = context.freshLedger()
        val swiped = dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON + 2))
        val batched = dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 1, amountSen = 1_200L))
        val transfer = dao.insert(pending(PendingReason.TRANSFER_SUSPECT, at = NOON))
        val viewModel = ReviewViewModel(context.applicationContext as Application)
        compose.setContent { PingedTheme { ReviewScreen(viewModel, onBack = {}) } }
        // The swiped card is over the limit: out of the batch, and a swipe still takes it (#223).
        waitForText("Accept 1 · RM12.00 · 1 over RM500 left to check")

        compose.onNodeWithTag(reviewCardTag(swiped)).performScrollTo().performTouchInput { swipeRight() }
        waitForText("ACCEPTED · RM650.00")
        compose.onNodeWithText("Accept 1 · RM12.00").performScrollTo().performClick()
        waitForText("ACCEPTED 1 · RM12.00")

        assertEquals(
            "The swipe and Accept all did not count the two plain payments and leave the transfer held",
            listOf(TxnState.COMMITTED, TxnState.COMMITTED, TxnState.PENDING),
            listOf(swiped, batched, transfer).map { dao.byId(it)!!.state },
        )

        // Pinned outside the scroll; see ReviewUndoTest.
        compose.onNodeWithText(UNDO).performClick()
        compose.boundedWaitUntil(TIMEOUT) { dao.byId(batched)!!.state == TxnState.PENDING }
        assertEquals(
            "Undo after Accept all did not return its card with its reason, or reached past it to the swipe",
            listOf(TxnState.COMMITTED to null, TxnState.PENDING to PendingReason.RULE_REVIEW),
            listOf(swiped, batched).map { id -> dao.byId(id)!!.let { it.state to it.pendingReason } },
        )
    }

    private fun waitForText(text: String) =
        compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}
