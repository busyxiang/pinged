package my.pinged.charts

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
import my.pinged.data.PingedDatabase
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Charts screen's state (#81, The Charts read): one read of the selected
 * month, published as one [ChartsRead].
 *
 * The month is [selectedMonth] of [handle], so it survives a process death;
 * the `Months` sheet changes it through [select].
 */
class ChartsViewModel(app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    private val _month = MutableStateFlow(selectedMonth(handle, YearMonth.now()))

    /** The month every figure on the screen covers. */
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _months = MutableStateFlow<MonthsRead?>(null)

    /** The `Months` sheet's last read; null until [readMonths] lands one. */
    val months: StateFlow<MonthsRead?> = _months.asStateFlow()

    private val _read = MutableStateFlow<ChartsRead?>(null)

    /** The last read that landed; null until the first does. */
    val read: StateFlow<ChartsRead?> = _read.asStateFlow()

    private val _unavailable = MutableStateFlow(false)

    /** The last read could not open the database; the screen says so. */
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    /** Tests only: runs inside each read's transaction, after its first statement or two. */
    @VisibleForTesting
    internal var midRead: () -> Unit = {}

    /**
     * Read the selected month, as of [today].
     *
     * **In one transaction** (`Databases.inOneTransaction`), under the
     * lease `CaptureStorage.guarded` takes: every statement reads one
     * snapshot, so a stage-two commit is wholly in the read or wholly out of
     * it, and the hero's spent less came back is its net, and the grid and
     * bars sum to it. Outside one, each statement would be its own snapshot.
     * The transaction holds Room's one connection, so a capture waits for it.
     * Measured on emulator-5554 over 20,000 constructed rows across 24
     * months, 500 merchants and 730 bound days: 45ms median, 51ms at the
     * longest of 40 -- under stage two's longest single capture (66ms,
     * `Databases.reset`).
     *
     * **One assignment, at the end**, as `LedgerViewModel.refresh` does: a
     * screen that saw half a read would draw a figure beside a trust answer
     * from another moment.
     */
    fun refresh(today: LocalDate = LocalDate.now()): Job = viewModelScope.launch {
        val month = _month.value
        CaptureStorage.guarded(
            context,
            what = "The charts cannot be read",
            unavailable = { _unavailable.value = true },
            damageStopsCapture = false,
        ) guarded@{
            val read = Databases.inOneTransaction(context) { db -> readMonth(db, month, today) }
            // A month picked while this read ran has its own read; this one,
            // landing after it, would put the old month back on screen.
            if (month != _month.value) return@guarded
            _read.value = read
            _unavailable.value = false
        }
    }

    /** [refresh]'s statements, all through [db] inside its transaction. */
    private fun readMonth(db: PingedDatabase, month: YearMonth, today: LocalDate): ChartsRead {
        val range = LocalDates.monthRange(month)
        val txns = db.txnDao()
        val monthTotals = txns.monthTotals(range.start, range.endInclusive)
        midRead()
        val monthSplit = txns.monthSplit(range.start, range.endInclusive)
        val previous = LocalDates.monthRange(month.minusMonths(1))
        val previousMonthTotals = txns.monthTotals(previous.start, previous.endInclusive)
        val notInTotal = txns.notInTotal(range.start, range.endInclusive)
        val dayTotals = txns.dayTotals(range.start, range.endInclusive)

        val captureDays = db.captureDayDao()
        val historyStart = CaptureEvidence.historyStart(captureDays, db.rawCaptureDao())
        val first = month.atDay(1)
        val captured = CaptureEvidence.capturedDates(
            captureDays,
            if (historyStart != null && historyStart < first) historyStart else first,
            month.atEndOfMonth(),
        )

        // The bars (#91): every category, since there are at most fourteen
        // seeded ones and the mapping layer folds the remainder itself.
        val byCategory = txns.monthByCategory(range.start, range.endInclusive, limit = EVERY_CATEGORY)
        val categories = db.categoryDao()
        val categoryNames = categories.all().associate { it.id to it.name }
        val uncategorizedId = categories.uncategorizedIdOrNull()
        // The merchant ranking (#92): every merchant of the month, ranked
        // and its rest summed in the mapping layer. Measured beside
        // `TxnDao.merchantTotals`: 1.74ms for a month of 333 merchants.
        val merchantTotals = txns.merchantTotals(range.start, range.endInclusive, limit = EVERY_MERCHANT)
        // The collecting gate (#66) counts across all history to
        // yesterday, whatever month is selected.
        val capturedSinceStart = if (historyStart != null && historyStart < today) {
            CaptureEvidence.capturedDates(captureDays, historyStart, today.minusDays(1))
        } else {
            emptySet()
        }

        return ChartsRead(
            month = month,
            today = today,
            historyStart = historyStart,
            captured = captured,
            monthTotals = monthTotals,
            monthSplit = monthSplit,
            previousMonthTotals = previousMonthTotals,
            byCategory = byCategory,
            categoryNames = categoryNames,
            uncategorizedId = uncategorizedId,
            capturedSinceStart = capturedSinceStart,
            merchantTotals = merchantTotals,
            notInTotal = notInTotal,
            dayTotals = dayTotals,
        )
    }

    /**
     * Show [month]: kept in [handle] under [SELECTED_MONTH], so a process
     * death comes back to it, then read.
     */
    fun select(month: YearMonth, today: LocalDate = LocalDate.now()): Job {
        handle[SELECTED_MONTH] = month.toString()
        _month.value = month
        return refresh(today)
    }

    /**
     * Read the `Months` sheet as of [today] (#75): the history start, then
     * two statements, in one transaction as [refresh] is, so a month's total
     * and the captured days that flag it are of one snapshot. Measured on
     * [refresh]'s ledger: 47ms median, 51ms at the longest of 40, of which
     * two years of `capturedDates` is 20ms.
     *
     * - `monthlyTotals` from the first packed day there is, since a month of
     *   money before the history start lists too (#75), to the end of
     *   [today]'s month. One grouped scan of `local_date`'s range, however
     *   long the history.
     * - `capturedDates` from the history start, before which nothing is
     *   counted, to the same end. With no history start nothing is counted,
     *   so it is not read.
     */
    fun readMonths(today: LocalDate = LocalDate.now()): Job = viewModelScope.launch {
        CaptureStorage.guarded(
            context,
            what = "The months cannot be read",
            unavailable = { _unavailable.value = true },
            damageStopsCapture = false,
        ) {
            _months.value = Databases.inOneTransaction(context) { db ->
                val captureDays = db.captureDayDao()
                val historyStart = CaptureEvidence.historyStart(captureDays, db.rawCaptureDao())
                val end = YearMonth.from(today).atEndOfMonth()
                val totals = db.txnDao().monthlyTotals(EVERY_DAY, LocalDates.of(end))
                midRead()
                val captured = historyStart?.let { CaptureEvidence.capturedDates(captureDays, it, end) } ?: emptySet()
                MonthsRead(today, historyStart, totals, captured)
            }
        }
    }

    private companion object {
        /** Below every `yyyymmdd`: the bound that leaves no day out. */
        val EVERY_DAY = my.pinged.data.LocalDate(0)

        /** `monthByCategory`'s limit, set past any category count: no effective limit. */
        const val EVERY_CATEGORY = Int.MAX_VALUE

        /** `merchantTotals`' limit, set past any merchant count: no effective limit. */
        const val EVERY_MERCHANT = Int.MAX_VALUE
    }
}
