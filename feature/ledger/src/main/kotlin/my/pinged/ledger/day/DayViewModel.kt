package my.pinged.ledger.day

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.CaptureStorage
import my.pinged.data.CaptureEvidence
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.RetroPreview
import my.pinged.data.notCaptured
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.RowActions
import java.time.LocalDate

/**
 * The `Day` screen's holder (#69, #81): one day's rows and its header, read
 * whole on every [refresh].
 *
 * [date] is the whole of what it is built from -- the `Day` key carries
 * nothing else -- so a holder rebuilt after a process death reads the day
 * afresh and shows nothing stale.
 *
 * **Every write is followed by a re-read.** The rows are a list, not the
 * Ledger's `PagingSource`, so nothing invalidates them: a category saved or a
 * merchant renamed would otherwise not show until the next resume.
 */
class DayViewModel(app: Application, val date: LocalDate) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    /** The last successful read, or null before the first one lands. */
    private val _read = MutableStateFlow<DayRead?>(null)
    internal val read: StateFlow<DayRead?> = _read.asStateFlow()

    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    /** The row's category and merchant actions, shared with the Ledger. */
    private val rows = RowActions(
        context = { context },
        scope = viewModelScope,
        unavailable = { _storageUnavailable.value = true },
    )

    val merchantSheet: StateFlow<MerchantSheetState?> = rows.merchantSheet

    /** Tests only: runs inside [refresh]'s transaction, after the rows are read. */
    @VisibleForTesting
    internal var midRead: () -> Unit = {}

    /**
     * Read the day: its rows, its counted total, #89's "not in the total"
     * aggregate over it, whether Pinged was watching it, and the chooser's
     * half, under one `guarded` lease and published in one assignment.
     *
     * **In one transaction** (`Databases.inOneTransaction`), so the header
     * and the rows beneath it are one snapshot: a stage-two commit between
     * the rows and the total would otherwise put a total over rows that
     * leave it out. The transaction holds Room's one connection, so a
     * capture waits for it. Measured on emulator-5554 over 20,000 constructed
     * rows across two years, about 27 on the day: 1.6ms median and 2.3ms at
     * the longest of 40, after a first read of 27ms.
     *
     * [today] is a parameter so a test can place the day relative to it.
     */
    fun refresh(today: LocalDate = LocalDate.now()): Job = viewModelScope.launch {
        CaptureStorage.guarded(
            context,
            what = "The day cannot be read",
            unavailable = { _storageUnavailable.value = true },
            damageStopsCapture = false,
        ) {
            _read.value = Databases.inOneTransaction(context) { db ->
                val txns = db.txnDao()
                val day = LocalDates.of(date)
                val dayRows = txns.dayRows(day)
                midRead()
                val counted = txns.dayTotals(day, day).map { CurrencyTotal(it.currency, it.netSen) }
                val keptOut = txns.notInTotal(day, day)
                val historyStart = CaptureEvidence.historyStart(db.captureDayDao(), db.rawCaptureDao())
                val captured = CaptureEvidence.capturedDates(db.captureDayDao(), date, date)
                // Through `Databases`' DAOs, which inside the transaction's
                // monitor are [db]'s: the same instance and the same snapshot.
                val chooser = rows.readChooser()
                DayRead(
                    rows = dayRows,
                    heading = dayHeading(
                        rowCount = dayRows.size,
                        counted = counted,
                        notInTotal = keptOut,
                        notWatching = notCaptured(date, historyStart, today, captured),
                    ),
                    chooser = chooser,
                )
            }
            _storageUnavailable.value = false
        }
    }

    /** See [RowActions.assignCategory]; the day is re-read after. */
    fun assignCategory(txnId: Long, categoryId: Long, teach: Boolean): Job =
        rows.assignCategory(txnId, categoryId, teach) { refresh().join() }

    /** See [RowActions.retroPreview]. */
    suspend fun retroPreview(txnId: Long, categoryId: Long): RetroPreview = rows.retroPreview(txnId, categoryId)

    fun openMerchant(ownKey: String): Job = rows.openMerchant(ownKey)

    fun closeMerchant() = rows.closeMerchant()

    fun renameMerchant(name: String): Job = thenReread(rows.renameMerchant(name))

    fun mergeMerchant(targetKey: String): Job = thenReread(rows.mergeMerchant(targetKey))

    fun separateMerchant(key: String): Job = thenReread(rows.separateMerchant(key))

    /** [write], then a re-read: the rows name the merchant it changed. */
    private fun thenReread(write: Job): Job = viewModelScope.launch {
        write.join()
        refresh().join()
    }
}

/**
 * What one [DayViewModel.refresh] read.
 *
 * [rows] are `TxnDao.dayRows`, the Ledger's rows for the day; [heading] is
 * what the header says beneath the date; [chooser] is the category chooser's
 * half, read in the same transaction so it marks what these rows carry.
 */
internal data class DayRead(
    val rows: List<FeedRow>,
    val heading: DayHeading,
    val chooser: ChooserRead,
)
