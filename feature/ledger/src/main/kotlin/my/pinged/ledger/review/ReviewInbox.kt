package my.pinged.ledger.review

import kotlinx.coroutines.CoroutineScope
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
import my.pinged.data.entity.TxnState
import my.pinged.ledger.LedgerBusy
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ReadState
import my.pinged.read.ScreenRead
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferState
import my.pinged.ledger.settings.Transfers

/**
 * Spec 9.2's review inbox: every transaction awaiting review, newest first,
 * and the answers that settle one (#109, #112).
 *
 * Shaped like `RowActions`: owned by a view model, which hands over its
 * [scope]. The cards are `:core:read`'s [ScreenRead] (#296), and every answer
 * is its `write`: leased and guarded, a database that will not open is
 * [ReadState.unavailable] rather than a process kill on a tap, and the cards
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
 * **During a restore or a delete-everything nothing is answered** ([busy]).
 * An answer asked for then is dropped on the spot, not queued behind the gate:
 * it was chosen against a ledger that is about to be replaced. The screen
 * disables its buttons on the same flow, so a drop is only reachable by a tap
 * racing the operation's start.
 *
 * **Every answer can be undone, the last one only** ([undoable], [undo]).
 * An answer that wrote replaces whatever was on offer; one that wrote nothing
 * is not a later answer and leaves it. Undo is `TxnDao.unsettle`: guarded on
 * the state the answer wrote, all rows or none.
 *
 * **A restore or a delete-everything withdraws the offer**, as it starts
 * and again when `Databases.rewrites` moves. The offer names rows by id, and
 * a restore keeps ids while a delete restarts them, so afterwards an id may
 * name another payment that the guard cannot tell from the one answered: a
 * committed row with no reason, not excluded.
 *
 * [transfers] is a parameter so the busy state has one source in production,
 * `Transfers.state`, the same flow the settings screen draws its notice from.
 */
class ReviewInbox(
    private val scope: CoroutineScope,
    private val runner: ReadRunner,
    private val transfers: StateFlow<TransferState> = Transfers.state,
) {
    private val gate = LedgerBusy(scope, transfers)

    /**
     * Every card awaiting review, newest first. `ReviewScreen` reads it on
     * resume and on `Databases.rewrites` through `rememberReadState`, and it
     * is not read yet until the first read lands. Public for the tests of
     * other modules that drive an inbox; a screen reads through `ReviewScreen`.
     */
    val read: ScreenRead<Unit, List<ReviewCard>> =
        ScreenRead.fixed(scope, runner, "The review inbox cannot be read") { db ->
            val txns = db.txnDao()
            txns.awaitingReview().map { row ->
                ReviewCard(row, pair = row.txn.takeIf { it.pendingReason == PendingReason.DUPLICATE_SUSPECT }?.let(txns::duplicatePair))
            }
        }

    /** The cards from the last read, newest first; null before one lands. */
    val cards: List<ReviewCard>? get() = read.state.value.answer

    /** The restore or delete-everything running, while one is; see the class KDoc. */
    val busy: StateFlow<Operation?> = gate.busy

    /** The last answer, while it can still be undone; see the class KDoc. */
    private val _undoable = MutableStateFlow<Undoable?>(null)
    val undoable: StateFlow<Undoable?> = _undoable.asStateFlow()

    init {
        scope.launch {
            transfers.collect { if (it.replacingLedger != null) _undoable.value = null }
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
        (runner.read("The categories could not be read") { db ->
            val categories = db.categoryDao()
            val unsorted = categories.uncategorizedIdOrNull()
            categories.all().filter { it.id != unsorted }
        } as? Outcome.Done)?.value

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
     */
    fun leaveOut(card: ReviewCard): Job = scope.launch {
        val wrote = writtenOn("The payment could not be left out", unavailable = emptyList()) { db, now ->
            val capture = card.txn.rawCaptureId?.let { db.rawCaptureDao().byId(it) }
            val exclusion = Graph.suggestedExclusion(capture?.sourcePackage, capture?.matchedRuleId)
            if (db.txnDao().leaveOut(card.txn.id, card.txn.pendingReason, exclusion, now) == 1) {
                listOf(Answered(card.txn.id, card.txn.pendingReason, TxnState.COMMITTED, exclusion))
            } else {
                emptyList()
            }
        } ?: return@launch
        answered(wrote.takeIf { it.isNotEmpty() }?.let { Undoable(said = "LEFT OUT · " + amountOf(card), rows = it) })
    }

    /**
     * Merge a duplicate card: dismiss the row awaiting review and keep its
     * [ReviewCard.pair] untouched (`TxnDao.merge`). Writes nothing for a card
     * with no pair, or once the pair has been dismissed.
     */
    fun merge(card: ReviewCard): Job {
        val pair = card.pair ?: return scope.launch {}
        return settle(card, TxnState.REJECTED, "The payments could not be merged", line = "MERGED · " + amountOf(card)) { now ->
            merge(card.txn.id, card.txn.pendingReason, pair.id, now)
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
        written("The answer could not be undone", unavailable = false) { now -> unsettle(offered.rows, now) }
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
     * One write of an answer or an Undo: [write] on the ledger at `now`, or
     * null without running it while a restore or a delete runs (see the class
     * KDoc), and [unavailable] when the database will not open. It is [read]'s
     * `write`, so the cards are read again in its turn before this returns.
     */
    private suspend fun <T : Any> writtenOn(what: String, unavailable: T, write: (PingedDatabase, now: Long) -> T): T? {
        if (gate.replacing) return null
        val outcome = read.write(what) { db -> write(db, System.currentTimeMillis()) }.await()
        return (outcome as? Outcome.Done)?.value ?: unavailable
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
}
