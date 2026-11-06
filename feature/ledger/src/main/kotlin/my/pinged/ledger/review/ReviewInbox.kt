package my.pinged.ledger.review

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.Graph
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.Answered
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.StandingAnswer
import my.pinged.data.entity.TxnState
import my.pinged.read.LedgerAccess
import my.pinged.read.LedgerReplacement
import my.pinged.read.LedgerReplacements
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ScreenRead

/**
 * Spec 9.2's review inbox: every transaction awaiting review, newest first,
 * and the answers that settle one (#109, #112).
 *
 * Shaped like `RowActions`: owned by a view model, which hands over its
 * [scope]. The cards are `:core:read`'s [ScreenRead] (#296), and every answer
 * is its `write`: leased and guarded, a database that will not open is
 * `LedgerAccess.Unreadable` rather than a process kill on a tap, and the cards
 * are read again in the answer's own turn, which is how a missed answer
 * redraws its row as it now is.
 *
 * **Each answer is one targeted `UPDATE` guarded on the card**
 * (`TxnDao.settle`): it writes only while the row is still `PENDING` for the
 * reason the card was drawn with, so an answer to a card a Corrections re-read
 * has since changed writes nothing. One answer settles a row: only the
 * highest-precedence reason is stored, and lower ones that also fired are not
 * asked separately.
 *
 * **During a restore or a delete-everything nothing is answered.** [read]
 * refuses an answer asked for then on the spot, not queued behind the gate
 * (#376): it was chosen against a ledger that is about to be replaced. The
 * screen disables its buttons while its read is `LedgerAccess.Withholding`,
 * so a refusal is only reachable by a tap racing the operation's start.
 *
 * **Every answer can be undone, the last one only** ([undoable], [undo]).
 * An answer that wrote replaces whatever was on offer; one that wrote nothing
 * is not a later answer and leaves it. Undo is `TxnDao.unsettle`: guarded on
 * the state the answer wrote, all rows or none.
 *
 * **Leave out also offers Always** ([always], spec 7.3): stored, it is a
 * standing answer for the card's rule, app and counterparty, and the offer
 * becomes one whose Undo deletes only that answer.
 *
 * **A restore or a delete-everything withdraws the offer**, as [read] says
 * one has started (withholding, or turned away by its gate) and again when
 * `Databases.rewrites` moves. The offer names rows by id, and
 * a restore keeps ids while a delete restarts them, so afterwards an id may
 * name another payment that the guard cannot tell from the one answered: a
 * committed row with no reason, not excluded.
 *
 * [replacement] is the claim [read] refuses answers on: `LedgerReplacements.current`
 * in production.
 *
 * [checkLimit] is the check limit now, `CheckLimit.sen` in production, read
 * with every read of the cards and carried on each
 * ([ReviewCard.checkLimitSen]), so a card's label, the Accept all button and
 * the batch it writes are all one read's limit (#362). [dispatcher] is where
 * the read runs; a JVM test passes the one it drives.
 */
class ReviewInbox(
    private val scope: CoroutineScope,
    runner: ReadRunner,
    private val checkLimit: suspend () -> Long,
    replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Every card awaiting review, newest first. `ReviewScreen` reads it on
     * resume and on `Databases.rewrites` through `rememberReadState`, and it
     * is not read yet until the first read lands. Public for the tests of
     * other modules that drive an inbox; a screen reads through `ReviewScreen`.
     */
    val read: ScreenRead<Unit, List<ReviewCard>> =
        ScreenRead.fixedStaged(scope, runner, "The review inbox cannot be read", dispatcher, replacement) {
            // Outside the transaction: a DataStore read has no place in one.
            val limitSen = checkLimit()
            transaction { db ->
                val txns = db.txnDao()
                val normalization = Graph.ruleMatcher().merchantNormalization
                txns.awaitingReview().map { row ->
                    val pair = row.txn.takeIf { it.pendingReason == PendingReason.DUPLICATE_SUSPECT }?.let(txns::duplicatePair)
                    ReviewCard(
                        row,
                        checkLimitSen = limitSen,
                        pair = pair,
                        merge = pair?.let { MergeWay.of(row.txn, it, normalization) } ?: MergeWay.HELD,
                    )
                }
            }
        }

    /** The cards from the last read, newest first; null before one lands. */
    val cards: List<ReviewCard>? get() = read.state.value.answer

    /** The last answer, while it can still be undone; see the class KDoc. */
    private val _undoable = MutableStateFlow<Undoable?>(null)
    val undoable: StateFlow<Undoable?> = _undoable.asStateFlow()

    init {
        scope.launch {
            read.state.collect {
                if (it.ledger is LedgerAccess.Withholding || it.ledger == LedgerAccess.BeingReplaced) _undoable.value = null
            }
        }
        // Read here, not on the collector's first value: a collector started
        // a dispatch later would take a ledger replaced meanwhile as its own.
        var file = Databases.rewrites.value
        scope.launch {
            Databases.rewrites.collect {
                if (it != file) {
                    file = it
                    _undoable.value = null
                }
            }
        }
    }

    /**
     * The categories a money-back card's sheet offers, in `sort_order`, or null
     * when the ledger cannot be read. Read when the sheet opens, not with the
     * cards: only a money-back card asks, and Settings may have edited the table
     * since the cards were read. Without `Uncategorized`, for the reason
     * `chooserChoices` gives.
     */
    suspend fun choices(): List<Category>? =
        (read.lookup("The categories could not be read") { db ->
            val categories = db.categoryDao()
            val unsorted = categories.uncategorizedIdOrNull()
            categories.all().filter { it.id != unsorted }
        }.await() as? Outcome.Done)?.value

    /**
     * File [card]'s payment under [categoryId] (§9.2), as a one-off, and leave
     * it awaiting review: `TxnDao.fileAwaiting`, guarded on the card as every
     * answer is. **Not an answer**, so it offers nothing for Undo and leaves
     * what is on offer. The cards are read again before it returns, which is how
     * the card shows its new category, or the row it now is when it had moved.
     */
    fun file(card: ReviewCard, categoryId: Long): Job = scope.launch {
        written("The category could not be set", unavailable = 0) { now ->
            fileAwaiting(card.txn.id, card.txn.pendingReason, categoryId, now)
        }
    }

    /** Count [card]'s payment as it stands: `COMMITTED`, no reason. */
    fun accept(card: ReviewCard): Job = settle(card, TxnState.COMMITTED, "The payment could not be accepted")

    /** Not a payment: `REJECTED`, no reason. The capture is kept as evidence. */
    fun reject(card: ReviewCard): Job = settle(card, TxnState.REJECTED, "The payment could not be dismissed")

    /**
     * Accept all (§9.2): accept every batchable card of the last read
     * ([isBatchable]) in one transaction (`TxnDao.settleAll`), each
     * guarded as [accept] is, and offer the rows it wrote as one Undo. No
     * transfer or duplicate card is ever in it, nor any over the check limit.
     * The cards are read again before it returns.
     */
    fun acceptAll(): Job = scope.launch {
        val batch = cards.orEmpty().filter { it.isBatchable }
        if (batch.isEmpty()) return@launch
        val wrote = written("The payments could not be accepted", unavailable = emptyList()) { now ->
            settleAll(
                batch.map { Answered(it.txn.id, it.txn.pendingReason, TxnState.COMMITTED, it.txn.exclusionReason) },
                now,
            )
        } ?: return@launch
        val ids = wrote.map { it.id }.toSet()
        answered(
            AcceptAll.of(batch.filter { it.txn.id in ids })?.let { Undoable(said = it.said, rows = wrote) },
        )
    }

    /**
     * Keep as expense (§7.3): count a transfer card's payment as spending,
     * exactly as [accept] counts a plain one.
     */
    fun keepAsExpense(card: ReviewCard): Job = settle(
        card,
        TxnState.COMMITTED,
        "The payment could not be kept as an expense",
        line = "KEPT AS EXPENSE · " + amountOf(card),
    )

    /**
     * Leave out (§7.3): `COMMITTED`, no reason, excluded with the reason the
     * pack's rule suggests -- re-derived from the capture's package and
     * `matched_rule_id` by `Graph.suggestedExclusion`, since parsing never
     * stored it, and `TRANSFER` when that rule is gone. Guarded on the card
     * as every answer is (`TxnDao.leaveOut`).
     *
     * Its Undo offer carries Always ([always]) for a transfer card whose
     * capture names its rule, unless a standing answer of that key is
     * already stored: then the user has said always, and this card was held
     * for something the answer does not cover, or arrived before it.
     */
    fun leaveOut(card: ReviewCard): Job = scope.launch {
        val wrote = writtenOn("The payment could not be left out", unavailable = emptyList()) { db, now ->
            val capture = card.txn.rawCaptureId?.let { db.rawCaptureDao().byId(it) }
            val exclusion = Graph.suggestedExclusion(capture?.sourcePackage, capture?.matchedRuleId)
            if (db.txnDao().leaveOut(card.txn.id, card.txn.pendingReason, exclusion, now) == 1) {
                val ruleId = capture?.matchedRuleId
                val always = if (card.kind == CardKind.TRANSFER && ruleId != null) {
                    StandingAnswer(matchedRuleId = ruleId, sourcePackage = capture.sourcePackage, merchantKey = card.txn.merchantKey, createdAt = now)
                        .takeIf { db.standingAnswerDao().idFor(it.matchedRuleId, it.sourcePackage, it.merchantKey) == null }
                } else {
                    null
                }
                listOf(
                    Undoable(
                        said = "LEFT OUT · " + amountOf(card),
                        rows = listOf(Answered(card.txn.id, card.txn.pendingReason, TxnState.COMMITTED, exclusion)),
                        always = always?.let { AlwaysOffer(it, said = "ALWAYS LEAVING OUT · " + amountOf(card)) },
                    ),
                )
            } else {
                emptyList()
            }
        } ?: return@launch
        answered(wrote.firstOrNull())
    }

    /**
     * Always (§7.3), on the Leave out on offer: store its standing answer,
     * keep the Leave out, and offer in its place an Undo that deletes only
     * the answer. Nothing while no Always is on offer; and the offer stands,
     * to be tapped again, when the database will not open. When an answer of
     * the key was stored meanwhile, this tap stored nothing to undo: Always
     * is withdrawn, and the Leave out's own Undo stays on offer.
     */
    fun always(): Job = scope.launch {
        val offered = _undoable.value ?: return@launch
        val always = offered.always ?: return@launch
        val stored = writtenOn("The answer could not be kept", unavailable = UNSTORED) { db, now ->
            db.standingAnswerDao().answer(always.answer.copy(createdAt = now)) ?: ALREADY_STORED
        } ?: return@launch
        _undoable.compareAndSet(
            offered,
            when (stored) {
                UNSTORED -> return@launch
                ALREADY_STORED -> offered.copy(always = null)
                else -> Undoable(said = always.said, rows = emptyList(), standing = stored)
            },
        )
    }

    /**
     * Merge a duplicate card: dismiss the row awaiting review and keep its
     * [ReviewCard.pair] untouched (`TxnDao.merge`). Writes nothing for a card
     * with no pair, or once the pair has been dismissed.
     *
     * A gateway pair ([ReviewCard.merge]) dismisses the gateway row instead,
     * whichever it is, commits the other and moves the category to it
     * (`TxnDao.mergeGateway`); it writes nothing once either row has moved
     * since the card was drawn.
     */
    fun merge(card: ReviewCard): Job {
        val pair = card.pair ?: return scope.launch {}
        val line = "MERGED · " + amountOf(card)
        val (dismiss, keep) = when (card.merge) {
            MergeWay.HELD -> return settle(card, TxnState.REJECTED, "The payments could not be merged", line = line) { now ->
                merge(card.txn.id, card.txn.pendingReason, pair.id, now)
            }
            MergeWay.GATEWAY_HELD -> card.txn to pair
            MergeWay.GATEWAY_PAIR -> pair to card.txn
        }
        return scope.launch {
            val wrote = writtenOn("The payments could not be merged", unavailable = emptyList()) { db, now ->
                listOfNotNull(db.txnDao().mergeGateway(dismiss, keep, db.categoryDao().uncategorizedIdOrNull(), now))
            } ?: return@launch
            answered(wrote.firstOrNull()?.let { Undoable(said = line, rows = emptyList(), merged = it) })
        }
    }

    /** Keep both: count the row awaiting review beside its pair. */
    fun keepBoth(card: ReviewCard): Job =
        settle(card, TxnState.COMMITTED, "The payment could not be kept", line = "KEPT BOTH · " + amountOf(card))

    /**
     * Take back the last answer: every row it wrote returns to awaiting review
     * for the reason it had, or none does when another writer has changed one
     * since. Spent either way. Refused while a restore or a delete runs,
     * which withdraws the offer itself (see the class KDoc).
     */
    fun undo(): Job = scope.launch {
        val offered = _undoable.value ?: return@launch
        _undoable.compareAndSet(offered, null)
        writtenOn("The answer could not be undone", unavailable = false) { db, now ->
            when {
                offered.standing != null -> db.standingAnswerDao().delete(offered.standing) == 1
                offered.merged != null -> db.txnDao().unmergeGateway(offered.merged, now)
                else -> db.txnDao().unsettle(offered.rows, now)
            }
        }
            ?: return@launch
    }

    /**
     * One answer: [write] (by default `TxnDao.settle` to [to]) unless a
     * restore or delete runs, which has read the cards again by the time it
     * returns, then the Undo offer, said as [line]. [write] returns the rows it wrote.
     */
    private fun settle(
        card: ReviewCard,
        to: TxnState,
        what: String,
        line: String = said(to, card),
        write: TxnDao.(now: Long) -> Int = { now -> settle(card.txn.id, card.txn.pendingReason, to, now) },
    ): Job = scope.launch {
        val wrote = written(what, unavailable = 0, write) ?: return@launch
        // The exclusion columns are recorded as the card drew them: this
        // statement leaves them as they were.
        answered(
            if (wrote == 1) {
                Undoable(
                    said = line,
                    rows = listOf(Answered(card.txn.id, card.txn.pendingReason, to, card.txn.exclusionReason)),
                )
            } else {
                null
            },
        )
    }

    /** [writtenOn] for a [write] that is one `TxnDao` statement. */
    private suspend fun <T : Any> written(what: String, unavailable: T, write: TxnDao.(now: Long) -> T): T? =
        writtenOn(what, unavailable) { db, now -> db.txnDao().write(now) }

    /**
     * One write of an answer or an Undo: [write] on the ledger at `now`; null
     * when a restore or a delete turned it away (`Outcome.Replacing`, from the
     * claim or the gate: see the class KDoc), so the caller writes nothing
     * more; and [unavailable] when the database will not open. It is [read]'s
     * `write`, so the cards are read again in its turn before this returns,
     * unless the claim refused it.
     */
    private suspend fun <T : Any> writtenOn(what: String, unavailable: T, write: (PingedDatabase, now: Long) -> T): T? =
        when (val outcome = read.write(what) { db -> write(db, System.currentTimeMillis()) }.await()) {
            is Outcome.Done -> outcome.value
            Outcome.Refused -> unavailable
            Outcome.Replacing -> null
        }

    /**
     * Offer [answer] for Undo in place of the last; null, an answer that
     * wrote nothing, leaves the last on offer (§9.2: only a later answer
     * replaces it). The one way an answer reaches [undoable]: Leave out,
     * Merge, Keep both and Accept all record what they wrote here too.
     */
    private fun answered(answer: Undoable?) {
        if (answer != null) _undoable.value = answer
    }

    private companion object {
        /** [always]'s write when the database would not open: the offer stands. */
        const val UNSTORED = -1L

        /** [always]'s write when an answer of the key was already stored: nothing of its own to undo. */
        const val ALREADY_STORED = 0L
    }
}
