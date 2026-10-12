package my.pinged.ledger.review

import my.pinged.data.entity.PendingReason
import my.pinged.parse.Direction
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgit

/**
 * Whether a swipe accepts [this] card and Accept all takes it (§9.2's
 * "plain cards"): by the stored reason, [BATCHABLE_REASONS], and not by
 * [ReviewCard.kind]. The two differ: **a duplicate whose pair cannot be found
 * draws as a plain card (`CardKind.PLAIN`) but is never swiped or batched**,
 * since it still has to be looked at, never accepted unseen (#109's story
 * 31). A row with no reason recorded is not batchable either.
 */
val ReviewCard.isBatchable: Boolean
    get() = txn.pendingReason in BATCHABLE_REASONS

/** Rule review, merchant missing and over threshold: the reasons a swipe or Accept all may answer. */
private val BATCHABLE_REASONS = setOf(
    PendingReason.RULE_REVIEW,
    PendingReason.MERCHANT_MISSING,
    PendingReason.OVER_THRESHOLD,
)

/**
 * The Accept all button (§9.2): [count] batchable cards and, when every one is
 * in ringgit, their [netSen] -- payments less refunds. Null [netSen] when
 * any is not, since a sum across currencies is no amount at all.
 */
data class AcceptAll(val count: Int, val netSen: Long?) {
    /** `ACCEPT 14 · RM1,234.00`, or `ACCEPT 14` with no sum. */
    val label: String get() = "ACCEPT $count" + sum()

    /** The snackbar's line once the batch is written: `ACCEPTED 14 · RM1,234.00`. */
    val said: String get() = "ACCEPTED $count" + sum()

    private fun sum(): String = netSen?.let { " · " + ringgit(it) }.orEmpty()

    companion object {
        /** Accept all over [cards]' batchable ones; null, and no button, when there are none. */
        fun of(cards: List<ReviewCard>): AcceptAll? {
            val batch = cards.filter { it.isBatchable }
            if (batch.isEmpty()) return null
            val net = if (batch.all { it.txn.currency == RINGGIT }) {
                batch.sumOf { if (it.txn.direction == Direction.REFUND) -it.txn.amountSen else it.txn.amountSen }
            } else {
                null
            }
            return AcceptAll(batch.size, net)
        }
    }
}
