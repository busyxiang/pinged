package my.pinged.ledger.detail

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.pinged.capture.GuardedRunner
import my.pinged.data.Databases
import my.pinged.data.dao.BillChoice
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.TxnDao
import my.pinged.ledger.bill.BillFor
import my.pinged.ledger.bill.BillOffer
import my.pinged.ledger.bill.offerBills
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.read.LedgerReplacement
import my.pinged.read.LedgerReplacements
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ScreenRead

/**
 * The transaction detail screen's holder (#153): one captured row, what Pinged
 * read for it, and the writes the screen offers.
 *
 * [txnId] and [createdAt] are the whole of what it is built from, as the
 * `Detail` key carries them: `created_at` tells this row from a later one a
 * delete-everything or a restore gives the same id, as the entry screen's
 * `EditTarget` does, and such a row reads as gone. The question is therefore
 * fixed, and the read is `:core:read`'s [ScreenRead] (#296).
 *
 * **Every write is followed by a re-read**, for the reason `DayViewModel`
 * gives: nothing invalidates a one-shot read. It is [read]'s own `write`, so
 * the re-read is in the write's turn.
 */
class DetailViewModel internal constructor(
    app: Application,
    val txnId: Long,
    val createdAt: Long,
    runner: ReadRunner,
    replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
) : AndroidViewModel(app) {
    constructor(app: Application, txnId: Long, createdAt: Long) :
        this(app, txnId, createdAt, GuardedRunner(app))

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    /**
     * The row and the chooser's half, in one transaction so the chooser marks
     * what the row carries, and published in one piece. `DetailScreen` reads
     * it again on resume and on `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, DetailRead> = ScreenRead.fixed(viewModelScope, runner, "The payment cannot be read", replacement) { db ->
        val row = db.txnDao().detail(txnId)?.takeIf { it.row.txn.createdAt == createdAt }
        DetailRead(row = row, chooser = rows.readChooser(db), bill = row?.row?.txn?.billTxnId?.let(db.txnDao()::billChoice))
    }

    /** The category chooser and the merchant sheet, shared with the list; the row is read again after each write. */
    val rows = RowActions(
        scope = viewModelScope,
        runner = runner,
        screen = read.rowScreen(),
    )

    /**
     * The bill Not a payback took off, while its Undo is on offer (spec 9.7):
     * until it is used, a later pick replaces it, or a restore or a
     * delete-everything rewrites the ledger -- a delete restarts ids, so the
     * id could name another payment afterwards.
     */
    private val _unlinked = MutableStateFlow<Long?>(null)
    val unlinked: StateFlow<Long?> = _unlinked.asStateFlow()

    init {
        // Read here, not on the collector's first value, for `ReviewInbox`'s reason.
        var file = Databases.rewrites.value
        viewModelScope.launch {
            Databases.rewrites.collect {
                if (it != file) {
                    file = it
                    _unlinked.value = null
                }
            }
        }
    }

    /** What the bill chooser lists for [payback] and [query], or null when it cannot be read, which closes it. */
    suspend fun bills(payback: BillFor, query: String): BillOffer? =
        (read.lookup("The bills could not be read") { db -> offerBills(db.txnDao(), payback, query) }.await() as? Outcome.Done)?.value

    /**
     * The chooser's pick (spec 7.4): `TxnDao.setBill`, whose guards a bill
     * that stopped counting since the list was drawn meets; No bill (null)
     * is [notAPayback].
     */
    fun pickBill(billId: Long?): Job {
        if (billId == null) return notAPayback()
        _unlinked.value = null
        return write("The bill could not be set") { it.setBill(txnId, billId, now()) }
    }

    /**
     * Not a payback (spec 9.7): `TxnDao.clearBill`, so the row is plain money
     * received, counted on its own date, category and merchant, which the
     * link never changed. Offers [undo] once written.
     */
    fun notAPayback(): Job {
        val bill = read.state.value.answer?.row?.row?.txn?.billTxnId
        return write("The payment could not be unlinked", after = { wrote -> if (wrote && bill != null) _unlinked.value = bill }) {
            it.clearBill(txnId, now())
        }
    }

    /**
     * Undo Not a payback: the bill goes back on by `TxnDao.setBill`, held to
     * its guards, so a bill left out meanwhile is not linked again behind the
     * user's back. Spent either way.
     */
    fun undo(): Job {
        val bill = _unlinked.value ?: return viewModelScope.launch {}
        _unlinked.value = null
        return write("The payback could not be put back") { it.setBill(txnId, bill, now()) }
    }

    /** `TxnDao.setCapturedNote`. */
    fun saveNote(note: String): Job = write("The note could not be saved") { it.setCapturedNote(txnId, note, now()) }

    /** Not spending: `TxnDao.excludeByUser`. */
    fun notSpending(): Job = write("The payment could not be left out") { it.excludeByUser(txnId, now()) }

    /** Count as spending: `TxnDao.countAsSpending`. */
    fun countAsSpending(): Job = write("The payment could not be counted") { it.countAsSpending(txnId, now()) }

    /**
     * Delete this entry: Not a payment (#132), `TxnDao.removeCaptured`, then
     * [then] -- the screen's way off a row the list no longer shows -- **only
     * if the row was written**. A write that did not reach the database, or
     * found the row moved, stays on the screen and re-reads it, so the user
     * sees what is actually stored rather than a list the row is still on.
     */
    fun remove(then: () -> Unit): Job = write("The payment could not be removed", after = { removed -> if (removed) then() }) {
        it.removeCaptured(txnId, now())
    }

    /**
     * One statement as [read]'s write, which reads the row again in its turn,
     * then [after] with whether the statement wrote a row. A 0 from it means
     * the row moved underneath, and the re-read is the honest answer to that;
     * a write refused is not a row written, nor is one [read] refuses while
     * the ledger is being replaced (#331, #376).
     */
    private fun write(what: String, after: (Boolean) -> Unit = {}, statement: (TxnDao) -> Int): Job = viewModelScope.launch {
        val outcome = read.write(what) { db -> statement(db.txnDao()) }.await()
        after((outcome as? Outcome.Done)?.value == 1)
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * What one read of [DetailViewModel.read] found: [row], null when the row is gone,
 * the chooser's half, and the [bill] the row pays back, null for none.
 */
internal data class DetailRead(
    val row: DetailRow?,
    val chooser: ChooserRead,
    val bill: BillChoice? = null,
)
