package my.pinged.ledger.detail

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import my.pinged.capture.GuardedRunner
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.TxnDao
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.read.Outcome
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
class DetailViewModel(app: Application, val txnId: Long, val createdAt: Long) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    private val runner = GuardedRunner(app)

    /**
     * The row and the chooser's half, in one transaction so the chooser marks
     * what the row carries, and published in one piece. `DetailScreen` reads
     * it again on resume and on `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, DetailRead> = ScreenRead.fixed(viewModelScope, runner, "The payment cannot be read") { db ->
        val row = db.txnDao().detail(txnId)?.takeIf { it.row.txn.createdAt == createdAt }
        DetailRead(row = row, chooser = rows.readChooser())
    }

    /** The category chooser and the merchant sheet, shared with the list; the row is read again after each write. */
    val rows = RowActions(
        context = { context },
        scope = viewModelScope,
        runner = runner,
        screen = read.rowScreen(),
    )

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
     * a write refused is not a row written.
     */
    private fun write(what: String, after: (Boolean) -> Unit = {}, statement: (TxnDao) -> Int): Job = viewModelScope.launch {
        val outcome = read.write(what) { db -> statement(db.txnDao()) }.await()
        after((outcome as? Outcome.Done)?.value == 1)
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * What one read of [DetailViewModel.read] found: [row], null when the row is gone,
 * and the chooser's half.
 */
internal data class DetailRead(
    val row: DetailRow?,
    val chooser: ChooserRead,
)
