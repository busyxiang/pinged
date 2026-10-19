package my.pinged.ledger.detail

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.pinged.data.LocalDate
import my.pinged.capture.CaptureStorage
import my.pinged.data.Databases
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.RetroPreview
import my.pinged.data.dao.TxnDao
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.RowActions

/**
 * The transaction detail screen's holder (#153): one captured row, what Pinged
 * read for it, and the writes the screen offers.
 *
 * [txnId] and [createdAt] are the whole of what it is built from, as the
 * `Detail` key carries them: `created_at` tells this row from a later one a
 * delete-everything or a restore gives the same id, as the entry screen's
 * `EditTarget` does, and such a row reads as gone.
 *
 * **Every write is followed by a re-read**, for the reason `DayViewModel`
 * gives: nothing invalidates a one-shot read.
 */
class DetailViewModel(app: Application, val txnId: Long, val createdAt: Long) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    /** The last successful read, or null before the first one lands. */
    private val _read = MutableStateFlow<DetailRead?>(null)
    internal val read: StateFlow<DetailRead?> = _read.asStateFlow()

    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    /** The category chooser and the merchant sheet, shared with the list. */
    private val rows = RowActions(
        context = { context },
        scope = viewModelScope,
        unavailable = { _storageUnavailable.value = true },
    )

    val merchantSheet: StateFlow<MerchantSheetState?> = rows.merchantSheet

    /**
     * Read the row and the chooser's half under one lease, published in one
     * assignment, in one transaction so the chooser marks what the row carries.
     */
    fun refresh(): Job = viewModelScope.launch {
        CaptureStorage.guarded(
            context,
            what = "The payment cannot be read",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            _read.value = Databases.inOneTransaction(context) { db ->
                val row = db.txnDao().detail(txnId)?.takeIf { it.row.txn.createdAt == createdAt }
                DetailRead(row = row, chooser = rows.readChooser())
            }
            _storageUnavailable.value = false
        }
    }

    /** See [RowActions.assignCategory]; the row is re-read after. */
    fun assignCategory(categoryId: Long, teach: Boolean): Job =
        rows.assignCategory(txnId, categoryId, teach) { refresh().join() }

    /** See [RowActions.retroPreview]. */
    suspend fun retroPreview(categoryId: Long): RetroPreview? = rows.retroPreview(txnId, categoryId)

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

    fun openMerchant(ownKey: String, on: LocalDate): Job = rows.openMerchant(ownKey, on)

    fun closeMerchant() = rows.closeMerchant()

    fun renameMerchant(name: String): Job = thenReread(rows.renameMerchant(name))

    fun mergeMerchant(targetKey: String): Job = thenReread(rows.mergeMerchant(targetKey))

    fun separateMerchant(key: String): Job = thenReread(rows.separateMerchant(key))

    /**
     * One guarded statement, then a re-read, then [after] with whether the
     * statement wrote a row. A 0 from it means the row moved underneath, and
     * the re-read is the honest answer to that.
     */
    private fun write(what: String, after: (Boolean) -> Unit = {}, statement: (TxnDao) -> Int): Job = viewModelScope.launch {
        val written = CaptureStorage.guarded(
            context,
            what = what,
            unavailable = { _storageUnavailable.value = true; 0 },
            damageStopsCapture = false,
        ) { statement(Databases.txnDao(context)) }
        refresh().join()
        after(written == 1)
    }

    /** [write], then a re-read: the screen names the merchant it changed. */
    private fun thenReread(write: Job): Job = viewModelScope.launch {
        write.join()
        refresh().join()
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * What one [DetailViewModel.refresh] read: [row], null when the row is gone,
 * and the chooser's half.
 */
internal data class DetailRead(
    val row: DetailRow?,
    val chooser: ChooserRead,
)
