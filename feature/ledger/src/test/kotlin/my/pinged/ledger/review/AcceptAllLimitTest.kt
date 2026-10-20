package my.pinged.ledger.review

import my.pinged.capture.REVIEW_THRESHOLD_SEN
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 9.2's check limit on Accept all (#223): a card over
 * [REVIEW_THRESHOLD_SEN] is left out of the batch by its amount, whatever
 * reason it is stored with, and still swipes. The batch written against the
 * database is `AcceptAllTest`'s; this is the predicate and the label.
 */
class AcceptAllLimitTest {
    @Test fun aCardOverTheLimitIsLeftOutOfTheBatchWhateverItsReason() {
        listOf(PendingReason.OVER_THRESHOLD, PendingReason.RULE_REVIEW, PendingReason.MERCHANT_MISSING).forEach { reason ->
            val over = card(1L, reason, REVIEW_THRESHOLD_SEN + 1)
            assertFalse("A $reason card over the limit is in Accept all's batch", over.isBatchable)
            assertTrue("A $reason card over the limit is not swipeable", over.isSwipeable)
        }
    }

    @Test fun aCardAtExactlyTheLimitIsStillBatched() {
        assertTrue(
            "A card at exactly RM500.00 was left out: the gate's test is strictly greater than",
            card(1L, PendingReason.RULE_REVIEW, REVIEW_THRESHOLD_SEN).isBatchable,
        )
    }

    @Test fun theLabelCountsAndSumsTheBatchAloneAndNamesWhatItLeftOut() {
        val cards = listOf(
            card(1L, PendingReason.OVER_THRESHOLD, 129_900L),
            card(2L, PendingReason.RULE_REVIEW, 120_000L),
            card(3L, PendingReason.MERCHANT_MISSING, 12_000L),
            card(4L, PendingReason.RULE_REVIEW, 50_000L),
            card(5L, PendingReason.TRANSFER_SUSPECT, 90_000L),
        )
        val offer = requireNotNull(AcceptAll.of(cards))

        assertEquals("Accept 2 · RM620.00 · 2 over RM500 left to check", offer.label)
        assertEquals("The snackbar line names the cards left out, though the batch never wrote them", "ACCEPTED 2 · RM620.00", offer.said)
    }

    @Test fun withNoNetTheNoteFollowsTheCount() {
        val cards = listOf(
            card(1L, PendingReason.OVER_THRESHOLD, 65_000L),
            card(2L, PendingReason.RULE_REVIEW, 1_200L).let { it.copy(txn = it.txn.copy(currency = "USD")) },
            card(3L, PendingReason.RULE_REVIEW, 1_200L),
        )

        assertEquals("Accept 2 · 1 over RM500 left to check", AcceptAll.of(cards)?.label)
    }

    @Test fun withNothingLeftOutTheLabelIsUnchanged() {
        val cards = listOf(card(1L, PendingReason.RULE_REVIEW, 5_000L), card(2L, PendingReason.MERCHANT_MISSING, 7_000L))

        assertEquals("Accept 2 · RM120.00", AcceptAll.of(cards)?.label)
    }

    @Test fun withEveryPlainCardOverTheLimitThereIsNoButton() {
        val cards = listOf(card(1L, PendingReason.OVER_THRESHOLD, 65_000L), card(2L, PendingReason.RULE_REVIEW, 50_001L))

        assertNull("Accept all was offered with nothing it may take", AcceptAll.of(cards))
    }

    private fun card(id: Long, reason: PendingReason, amountSen: Long) = ReviewCard(
        txn = ledgerTxn(amountSen = amountSen, state = TxnState.PENDING, pendingReason = reason, id = id),
        title = "Notification $id",
        body = "Body $id",
    )
}
