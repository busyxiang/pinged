package my.pinged.ledger.review

import my.pinged.capture.CheckLimit
import my.pinged.data.dao.Answered
import my.pinged.data.dao.GatewayMerge
import my.pinged.data.dao.ReviewRow
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
import my.pinged.parse.Merchant
import my.pinged.parse.MerchantNormalization
import my.pinged.ui.ringgit

/**
 * One card of the review inbox: the transaction awaiting review, Pinged's
 * reading of it, and the notification it read, verbatim (§9.2's hard
 * requirement).
 *
 * @property merchant spec 6.4's resolved name, as the list shows it.
 * @property category the name of the row's category.
 * @property title the capture's `title`; null with [body] for a row with no
 *   capture.
 * @property body the capture's `big_text` when it has one, else its `text`.
 * @property pair for a duplicate card, the transaction it may duplicate
 *   (`TxnDao.duplicatePair`); null for every other card, and for a duplicate
 *   whose pair cannot be found, which is then a plain card.
 * @property merge which row Merge dismisses; see [MergeWay].
 * @property checkLimitSen the check limit when the card was read
 *   (`CheckLimit.sen`), the same for every card of one read: what Accept all
 *   leaves out by ([isBatchable]) and what [reason] names. The limit a row
 *   arrived under is not stored (#362).
 */
data class ReviewCard(
    val txn: Txn,
    val merchant: String? = txn.merchantDisplay ?: txn.merchantRaw,
    val category: String? = null,
    val title: String? = null,
    val body: String? = null,
    val pair: Txn? = null,
    val merge: MergeWay = MergeWay.HELD,
    val checkLimitSen: Long = CheckLimit.DEFAULT_SEN,
) {
    constructor(row: ReviewRow, checkLimitSen: Long, pair: Txn? = null, merge: MergeWay = MergeWay.HELD) : this(
        txn = row.txn,
        merchant = row.displayName,
        category = row.categoryName,
        title = row.captureTitle,
        body = row.captureBigText?.takeIf { it.isNotEmpty() } ?: row.captureText,
        pair = pair,
        merge = merge,
        checkLimitSen = checkLimitSen,
    )

    /** Why the card is held, in words: [reasonSentence] for this card's amount and [checkLimitSen]. */
    val reason: String get() = reasonSentence(txn.pendingReason, txn.amountSen, checkLimitSen)

    /**
     * Which answers the card offers; see [CardKind]. A duplicate with no
     * [pair] is a plain card: Merge would have nothing to keep.
     */
    val kind: CardKind get() = CardKind.of(txn.pendingReason).let { if (it == CardKind.DUPLICATE && pair == null) CardKind.PLAIN else it }

    /**
     * Whether the card asks which category the money counts against: a plain
     * card of money coming back. A friend paying a bill back arrives under the
     * friend's name, which files under `Uncategorized`, while the bill it
     * lowers is under Food; filing it now keeps that bill's category honest
     * without a trip to the ledger. A payment out is not asked: its merchant
     * says where it belongs, and a transfer or duplicate card is about
     * something else.
     */
    val asksCategory: Boolean get() = kind == CardKind.PLAIN && txn.direction == Direction.REFUND
}

/**
 * The three kinds of card §9.2 answers differently.
 *
 * [answers] is what each offers, the first drawn as the primary button.
 */
enum class CardKind(val answers: List<ReviewAnswer>) {
    /** Rule review, merchant missing, over threshold, or no reason recorded. */
    PLAIN(listOf(ReviewAnswer.ACCEPT, ReviewAnswer.NOT_A_PAYMENT)),
    /** Leave out first: the default answer, drawn as the primary button (§7.3). */
    TRANSFER(listOf(ReviewAnswer.LEAVE_OUT, ReviewAnswer.KEEP_AS_EXPENSE, ReviewAnswer.NOT_A_PAYMENT)),
    /** Drawn only beside a pair; see [ReviewCard.kind]. Merge keeps the pair. */
    DUPLICATE(listOf(ReviewAnswer.MERGE, ReviewAnswer.KEEP_BOTH)),
    ;

    companion object {
        fun of(reason: PendingReason?): CardKind = when (reason) {
            PendingReason.TRANSFER_SUSPECT -> TRANSFER
            PendingReason.DUPLICATE_SUSPECT -> DUPLICATE
            else -> PLAIN
        }
    }
}

/**
 * Which row of a duplicate card Merge dismisses (§7.2). Decided once, when
 * the card is read, so the card's headings and the write cannot disagree.
 */
enum class MergeWay {
    /** The row awaiting review, its pair untouched: every pair but a gateway one. */
    HELD,

    /** The row awaiting review, which is gateway-only; its category moves to the pair. */
    GATEWAY_HELD,

    /**
     * The pair, which is gateway-only and usually already counted; the row
     * awaiting review is committed and takes the pair's category (#255).
     */
    GATEWAY_PAIR,
    ;

    companion object {
        /**
         * [GATEWAY_HELD] or [GATEWAY_PAIR] when exactly one of [held] and
         * [pair] is gateway-only ([Merchant.isGatewayOnly]) and not
         * `user_edited`, so a person's edit is never the row dismissed in its
         * place; [HELD] for every other pair.
         */
        fun of(held: Txn, pair: Txn, normalization: MerchantNormalization): MergeWay {
            val heldGateway = Merchant.isGatewayOnly(held.merchantRaw, normalization)
            val pairGateway = Merchant.isGatewayOnly(pair.merchantRaw, normalization)
            return when {
                pairGateway && !heldGateway && !pair.userEdited -> GATEWAY_PAIR
                heldGateway && !pairGateway && !held.userEdited -> GATEWAY_HELD
                else -> HELD
            }
        }
    }
}

/**
 * The last answer, on offer for Undo: what the snackbar [said], and every row
 * it wrote, which `TxnDao.unsettle` takes back together -- or, for a gateway
 * Merge, the two rows it wrote ([merged]), which `TxnDao.unmergeGateway`
 * takes back instead.
 */
data class Undoable(val said: String, val rows: List<Answered>, val merged: GatewayMerge? = null)

/** The snackbar's line for an answer that wrote [to] on [card]'s row: `ACCEPTED · RM650.00`. */
internal fun said(to: TxnState, card: ReviewCard): String =
    when (to) {
        TxnState.REJECTED -> "NOT A PAYMENT"
        else -> "ACCEPTED"
    } + " · " + amountOf(card)

/** An answer a card offers, by the label its button carries. */
enum class ReviewAnswer(val label: String) {
    ACCEPT("Accept"),
    NOT_A_PAYMENT("Not a payment"),
    MERGE("Merge"),
    KEEP_BOTH("Keep both"),
    LEAVE_OUT("Leave out"),
    KEEP_AS_EXPENSE("Keep as expense"),
}

/**
 * The label a card gives for why it is held: the fact the gate saw, not
 * advice, in `design/Inbox.dc.html`'s words. Capitals in the string, so what
 * TalkBack reads is what is drawn. The board draws no rule-review card; its
 * line is written in the same voice.
 *
 * Over the check limit names [limitSen], the limit now, only while
 * [amountSen] is still over it, `>` as the gate tests. Under it the card was
 * held by a limit since raised, which is not stored (#362), so the line says
 * when rather than naming an amount that would not hold it.
 */
fun reasonSentence(reason: PendingReason?, amountSen: Long, limitSen: Long): String = when (reason) {
    PendingReason.OVER_THRESHOLD ->
        if (amountSen > limitSen) "OVER YOUR ${limit(limitSen)} CHECK LIMIT" else "OVER YOUR CHECK LIMIT WHEN IT ARRIVED"
    PendingReason.MERCHANT_MISSING -> "AMOUNT FOUND, NO NAME ATTACHED"
    PendingReason.RULE_REVIEW -> "THE RULE THAT READ IT ASKS YOU TO CHECK"
    PendingReason.TRANSFER_SUSPECT -> "LOOKS LIKE MOVING MONEY, NOT SPENDING IT"
    PendingReason.DUPLICATE_SUSPECT -> "MAYBE THE SAME PURCHASE TWICE"
    null -> "PINGED DID NOT RECORD WHY"
}

/** A check limit as the boards print it, on a card and in Settings: `RM500`, sen only when it has any. */
internal fun limit(sen: Long): String = ringgit(sen).removeSuffix(".00")
