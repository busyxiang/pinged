package my.pinged.data.dao

import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.parse.ExclusionReason

/**
 * One row a review-inbox answer wrote (§9.2), as much as `TxnDao.unsettle`
 * needs to take it back: the reason the row was awaiting review for, and the
 * state the answer left it in.
 *
 * Every answer writes `pending_reason = NULL`, so that is not recorded; the
 * three columns that differ between answers are. Accept, Keep as expense and
 * Keep both are `COMMITTED` with no [exclusionReason]; Not a payment and
 * Merge are `REJECTED`; Leave out is `COMMITTED` with its [exclusionReason].
 *
 * @property reason what the row is put back to awaiting review for.
 * @property state what the answer wrote; never `PENDING`.
 * @property exclusionReason what the answer left in `exclusion_reason`;
 *   non-null exactly when it left `is_excluded = 1`.
 */
data class Answered(
    val id: Long,
    val reason: PendingReason?,
    val state: TxnState,
    val exclusionReason: ExclusionReason? = null,
) {
    init {
        require(state != TxnState.PENDING) { "an answer leaves its row settled, not PENDING" }
    }
}

/** Thrown inside `TxnDao.unsettle` to roll every row back when one has moved. */
internal class StaleAnswerException : IllegalStateException("A row moved since its answer; nothing was undone.")
