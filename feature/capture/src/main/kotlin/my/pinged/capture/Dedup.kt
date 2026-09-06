package my.pinged.capture

import my.pinged.data.DuplicateWindows
import my.pinged.data.SlotRefreshOutcome
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.slotRefreshOutcome
import my.pinged.parse.Direction
import my.pinged.parse.Merchant
import my.pinged.parse.MerchantNormalization

/**
 * What layer 1 decided about one capture. Four outcomes, and the difference
 * between the third and the other two is the difference between dropping money
 * and reviewing it.
 */
internal sealed interface Layer1Verdict {
    /** No earlier row matched either rule. Parse it normally. */
    data object NotADuplicate : Layer1Verdict

    /**
     * Rule 1 inside its ten-minute window, on either arrival path. The app
     * refreshed its own notification, so no money moved twice: `UPDATE_OF`, no
     * transaction.
     */
    data class SlotRefresh(val priorId: Long) : Layer1Verdict

    /**
     * Rule 2: the same `content_hash` from a different notification slot within
     * 60 seconds. `DUPLICATE_OF`, no transaction.
     */
    data class ContentRepeat(val priorId: Long) : Layer1Verdict

    /**
     * Rule 1 **beyond** its ten-minute window, on either arrival path. A
     * transaction is created and flagged `DUPLICATE_SUSPECT`; this is what
     * extends layer 2 to the same-package case layer 2's own conditions
     * exclude.
     */
    data class SlotSuspect(val priorId: Long) : Layer1Verdict
}

/**
 * Spec 7.2's two duplicate layers, which are different things and must stay so.
 *
 * Layer 1 is one notification seen twice: **no transaction**. Layer 2 is a
 * genuine second transaction for one purchase seen through two apps: a
 * transaction flagged `DUPLICATE_SUSPECT` and therefore `PENDING`, landing in
 * the review inbox with Merge or Keep both. Treating a layer-2 pair as layer 1
 * makes a real transaction vanish, against "duplicates are never dropped
 * automatically"; treating a layer-1 refresh as layer 2 puts a review item in
 * front of the user for every notification an app redraws.
 *
 * The windows live on [my.pinged.data.DuplicateWindows], which the DAO's own
 * tests also read, so a window cannot disagree with itself.
 */
internal object Dedup {

    /**
     * Spec 7.2 layer 1, both rules, in the order the spec gives them.
     *
     * **The windowed query is asked first, the unwindowed one only if it misses.**
     * A slot may hold a chain of identical posts, and only the windowed query can
     * tell that *some* earlier match falls inside ten minutes: with posts at t=0,
     * t=15min and t=20min, the unwindowed query returns the t=0 row and would call
     * the t=20min capture a suspect, where spec 7.2 says it is a refresh. The
     * unwindowed query then catches the genuinely old match, which is a suspect.
     *
     * Rule 2 is only reached when rule 1 found nothing, matching "two rules, in
     * order".
     */
    fun layerOne(dao: RawCaptureDao, capture: RawCapture): Layer1Verdict {
        // One path for both arrivals; see `slotRefreshOutcome` for what exempting
        // `CATCHUP` from the window dropped.
        //
        // The windowed query first and the unwindowed only if it misses, so a match
        // inside the window is preferred over an older one outside it -- the difference
        // between silently dropping the money and putting it in the review inbox.
        // Inside the window it returns the *oldest* match, not the nearest; see the DAO
        // for why.
        val prior = dao.findEarlierInSlotSince(
            key = capture.sbnKey,
            hash = capture.contentHash,
            selfId = capture.id,
            sinceMillis = capture.postedAt - DuplicateWindows.SLOT_REFRESH_MILLIS,
            untilMillis = capture.postedAt,
        ) ?: dao.findEarlierInSlot(
            key = capture.sbnKey,
            hash = capture.contentHash,
            selfId = capture.id,
        )

        if (prior != null) {
            // The verdict comes from data's own function rather than from a
            // comparison written here, so the window has exactly one
            // implementation.
            return when (slotRefreshOutcome(capture.postedAt - prior.postedAt)) {
                SlotRefreshOutcome.UPDATE_OF -> Layer1Verdict.SlotRefresh(prior.id)
                SlotRefreshOutcome.DUPLICATE_SUSPECT -> Layer1Verdict.SlotSuspect(prior.id)
            }
        }

        val repeat = dao.findByContentHash(
            hash = capture.contentHash,
            sinceMillis = capture.postedAt - DuplicateWindows.CONTENT_HASH_MILLIS,
            untilMillis = capture.postedAt,
        )
            .filter { it.sbnKey != capture.sbnKey && isEarlier(it, capture) }
            .minWithOrNull(EARLIEST_FIRST)

        return if (repeat == null) {
            Layer1Verdict.NotADuplicate
        } else {
            Layer1Verdict.ContentRepeat(repeat.id)
        }
    }

    /**
     * Spec 7.2 layer 2: identical `amount_sen` within ten minutes from a
     * **different** `source_package`, where merchant tokens overlap or one merchant
     * is absent.
     *
     * Returns the transaction the new capture looks like a duplicate of, or null.
     * It never modifies anything: the resolution is a review-inbox prompt.
     *
     * Only the *newer* of a pair is flagged, which falls out of the ordering rather
     * than being checked -- stage two processes captures in `posted_at` order, so
     * when the second app's capture is parsed the first app's transaction is
     * already in the table.
     *
     * `is_authoritative` is deliberately not consulted; see [ParsePass].
     *
     * `direction` has to match, for the reason on [TxnDao.findDuplicateSuspects]:
     * an expense and the refund reversing it satisfy every clause spec 7.2 writes
     * down, and the Merge the inbox would offer erases the reversal.
     */
    fun layerTwo(
        dao: TxnDao,
        sourcePackage: String,
        amountSen: Long,
        occurredAt: Long,
        direction: Direction,
        merchantRaw: String?,
        normalization: MerchantNormalization,
    ): Txn? = dao.findDuplicateSuspects(
        amountSen = amountSen,
        from = occurredAt - DuplicateWindows.LAYER_TWO_MILLIS,
        to = occurredAt + DuplicateWindows.LAYER_TWO_MILLIS,
        direction = direction,
        excludePackage = sourcePackage,
    ).firstOrNull { merchantsAgree(merchantRaw, it.merchantRaw, normalization) }

    /**
     * Spec 7.2 layer 2's merchant clause: "merchant tokens overlap or one merchant
     * is absent".
     *
     * An absent merchant counts as agreement, because a card-alert app routinely
     * posts an amount with no merchant and that pairing is the exact case layer 2
     * was written for. False positive costs one tap; false negative costs a
     * double-counted purchase.
     *
     * Tokens are compared after [Merchant.clean], so `TNG*STARBUCKS KLCC` and
     * `Starbucks KLCC` overlap, which they would not as raw strings.
     */
    fun merchantsAgree(a: String?, b: String?, normalization: MerchantNormalization): Boolean {
        val left = tokens(a, normalization)
        val right = tokens(b, normalization)
        if (left.isEmpty() || right.isEmpty()) return true
        return left.any { it in right }
    }

    private fun tokens(raw: String?, normalization: MerchantNormalization): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return Merchant.clean(raw, normalization).value
            .split(' ')
            .filterTo(mutableSetOf()) { it.isNotEmpty() }
    }

    /**
     * `(posted_at, id)` ascending, which is a **total** order, and has to be.
     *
     * Rule 2 asks "is there an earlier row with this hash and a different slot",
     * and both members of a pair ask it. `posted_at` alone does not settle it: two
     * notifications posted in the same millisecond would each see the other as
     * earlier, each be marked `DUPLICATE_OF` the other, and neither produce a
     * transaction -- the money disappearing with no review item and nothing on
     * screen to notice.
     *
     * `id` is `autoGenerate`, so it breaks the tie in insertion order. It is the
     * tie-breaker rather than the whole comparison because a capture can be
     * inserted out of `posted_at` order -- spec 10.1's catch-up sweep replays
     * whatever is still live -- and there `posted_at` is the truth.
     */
    private val EARLIEST_FIRST: Comparator<RawCapture> =
        compareBy({ it.postedAt }, { it.id })

    private fun isEarlier(other: RawCapture, capture: RawCapture): Boolean =
        EARLIEST_FIRST.compare(other, capture) < 0
}
