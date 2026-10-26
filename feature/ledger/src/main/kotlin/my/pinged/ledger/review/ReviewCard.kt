package my.pinged.ledger.review

import my.pinged.capture.REVIEW_THRESHOLD_SEN
import my.pinged.data.dao.Answered
import my.pinged.data.dao.ReviewRow
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Direction
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
 */
data class ReviewCard(
    val txn: Txn,
    val merchant: String? = txn.merchantDisplay ?: txn.merchantRaw,
    val category: String? = null,
    val title: String? = null,
    val body: String? = null,
    val pair: Txn? = null,
) {
    constructor(row: ReviewRow, pair: Txn? = null) : this(
        txn = row.txn,
        merchant = row.displayName,
        category = row.categoryName,
        title = row.captureTitle,
        body = row.captureBigText?.takeIf { it.isNotEmpty() } ?: row.captureText,
        pair = pair,
    )

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
 * The last answer, on offer for Undo: what the snackbar [said], and every row
 * it wrote, which `TxnDao.unsettle` takes back together.
 */
data class Undoable(val said: String, val rows: List<Answered>)

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
 */
fun reasonSentence(reason: PendingReason?): String = when (reason) {
    PendingReason.OVER_THRESHOLD -> "OVER YOUR ${limit(REVIEW_THRESHOLD_SEN)} CHECK LIMIT"
    PendingReason.MERCHANT_MISSING -> "AMOUNT FOUND, NO NAME ATTACHED"
    PendingReason.RULE_REVIEW -> "THE RULE THAT READ IT ASKS YOU TO CHECK"
    PendingReason.TRANSFER_SUSPECT -> "LOOKS LIKE MOVING MONEY, NOT SPENDING IT"
    PendingReason.DUPLICATE_SUSPECT -> "MAYBE THE SAME PURCHASE TWICE"
    null -> "PINGED DID NOT RECORD WHY"
}

/** The threshold as the board prints it: `RM500`, sen only when it has any. */
internal fun limit(sen: Long): String = ringgit(sen).removeSuffix(".00")
