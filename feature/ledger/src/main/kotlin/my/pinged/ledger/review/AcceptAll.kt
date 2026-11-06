package my.pinged.ledger.review

import my.pinged.data.entity.PendingReason
import my.pinged.parse.Direction
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgit

/**
 * Whether a swipe accepts [this] card (§9.2's "plain cards"): by the stored
 * reason, [PLAIN_REASONS], and not by [ReviewCard.kind]. The two differ: **a
 * duplicate whose pair cannot be found draws as a plain card
 * (`CardKind.PLAIN`) but is never swiped or batched**, since it still has to
 * be looked at, never accepted unseen (#109's story 31). A row with no reason
 * recorded is not swipeable either. Accept all asks more: [isBatchable].
 */
val ReviewCard.isSwipeable: Boolean
    get() = txn.pendingReason in PLAIN_REASONS

/**
 * Whether Accept all takes [this] card: [isSwipeable], and not over the check
 * limit by its amount (#223). By the amount and not the stored reason, since
 * the gate stores one reason and over threshold ranks lowest: a row over the
 * limit that also fired rule review is stored as rule review. `>` strictly,
 * the gate's own test, so the limit exactly is batched.
 *
 * The limit is [ReviewCard.checkLimitSen], the one now (#362): after a raise
 * this is how a card held under the old limit is released, by the user's
 * Accept all, and after a lower a card under the old one is left to check.
 */
val ReviewCard.isBatchable: Boolean
    get() = isSwipeable && !isLeftOut

/** A plain card Accept all leaves to be checked: over the limit, and swipeable still. */
private val ReviewCard.isLeftOut: Boolean get() = isSwipeable && txn.amountSen > checkLimitSen

/** Rule review, merchant missing and over threshold: the reasons a swipe may answer. */
private val PLAIN_REASONS = setOf(
    PendingReason.RULE_REVIEW,
    PendingReason.MERCHANT_MISSING,
    PendingReason.OVER_THRESHOLD,
)

/**
 * The Accept all button (§9.2): [count] batchable cards and, when every one is
 * in ringgit, their [netSen] -- payments less refunds. Null [netSen] when
 * any is not, since a sum across currencies is no amount at all. [leftOut]
 * is the plain cards over the check limit, which the batch does not take, and
 * [limitSen] that limit; null when none is left out.
 */
data class AcceptAll(val count: Int, val netSen: Long?, val leftOut: Int, val limitSen: Long? = null) {
    /** `Accept 14 · RM1,234.00 · 2 over RM500 left to check`, without the sum or the note when there is none. */
    val label: String get() = "Accept $count" + sum() + note()

    /** The snackbar's line once the batch is written: `ACCEPTED 14 · RM1,234.00`, never the note. */
    val said: String get() = "ACCEPTED $count" + sum()

    private fun sum(): String = netSen?.let { " · " + ringgit(it) }.orEmpty()

    private fun note(): String = if (leftOut > 0 && limitSen != null) " · $leftOut over ${limit(limitSen)} left to check" else ""

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
            val leftOut = cards.filter { it.isLeftOut }
            return AcceptAll(batch.size, net, leftOut = leftOut.size, limitSen = leftOut.firstOrNull()?.checkLimitSen)
        }
    }
}
