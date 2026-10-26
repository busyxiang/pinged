package my.pinged.charts

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import my.pinged.capture.GuardedRunner
import my.pinged.data.CaptureRecord
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.read.ScreenRead
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Charts screen's reads (#81, The Charts read): one read of the selected
 * month, published as one [ChartsRead], and the `Months` sheet's read.
 *
 * Both are `:core:read`'s [ScreenRead], which owns what they have in
 * common: the order of the reads, the answer kept while another is read, the
 * answer dropped when its question has changed, and the database that cannot be
 * opened. This class says what each reads.
 *
 * The month is [read]'s question, and is also kept in [handle] under
 * [SELECTED_MONTH], so it survives a process death; the `Months` sheet changes
 * it through [select]. [today] is asked when each read begins, so a read after
 * midnight is of the new day.
 */
class ChartsViewModel(
    app: Application,
    private val handle: SavedStateHandle,
    private val today: () -> LocalDate = LocalDate::now,
) : AndroidViewModel(app) {

    private val runner = GuardedRunner(app)

    /**
     * The selected month's read: the question is the month every figure on the
     * screen covers. `ChartsScreen` reads it again on resume and on
     * `Databases.rewrites` through `rememberReadState`.
     */
    val read: ScreenRead<YearMonth, ChartsRead> = ScreenRead(
        scope = viewModelScope,
        runner = runner,
        what = "The charts cannot be read",
        initial = selectedMonth(handle, YearMonth.now()),
    ) { db, month -> readMonth(db, month, today()) }

    /**
     * The `Months` sheet's read (#75). The question is the opening, numbered
     * from [openMonths]: a sheet opening is a new question, so an opening's
     * read keeps the last opening's months under `UPDATING` (#285).
     */
    val months: ScreenRead<Int, MonthsRead> = ScreenRead(
        scope = viewModelScope,
        runner = runner,
        what = "The months cannot be read",
        initial = 0,
    ) { db, _ -> monthsRead(db, today(), midRead) }

    private var openings = 0

    /** Tests only: runs inside each read's transaction, after its first statement or two. */
    @VisibleForTesting
    internal var midRead: () -> Unit = {}

    /**
     * The statements of one read of [month], as of [today], all through [db]
     * inside its transaction.
     *
     * **In one transaction** (`Databases.inOneTransaction`, which `GuardedRunner`
     * runs under the lease `CaptureStorage.guarded` takes): every statement reads
     * one snapshot, so a stage-two commit is wholly in the read or wholly out of
     * it, and the hero's spent less came back is its net, and the grid and bars
     * sum to it. Outside one, each statement would be its own snapshot. The
     * transaction holds Room's one connection, so a capture waits for it.
     * Measured on emulator-5554 over 20,000 constructed rows across 24 months,
     * 500 merchants and 730 bound days: 45ms median, 51ms at the longest of 40
     * -- under stage two's longest single capture (66ms, `Databases.reset`).
     */
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

        // The comparison asks about the month before too, and the collecting
        // gate (#66) counts across all history whatever month is selected.
        val record = CaptureRecord.read(db, today, month.minusMonths(1).atDay(1)..month.atEndOfMonth(), collecting = true)

        // The bars (#91): every category, since there are at most sixteen
        // seeded ones and the mapping layer folds the remainder itself.
        val byCategory = txns.monthByCategory(range.start, range.endInclusive, limit = EVERY_CATEGORY)
        val categories = db.categoryDao()
        val categoryNames = categories.all().associate { it.id to it.name }
        val uncategorizedId = categories.uncategorizedIdOrNull()
        // The merchant ranking (#92): every merchant of the month, ranked
        // and its rest summed in the mapping layer. Measured beside
        // `TxnDao.merchantTotals`: 1.74ms for a month of 333 merchants.
        val merchantTotals = txns.merchantTotals(range.start, range.endInclusive, limit = EVERY_MERCHANT)

        return ChartsRead(
            month = month,
            record = record,
            monthTotals = monthTotals,
            monthSplit = monthSplit,
            previousMonthTotals = previousMonthTotals,
            byCategory = byCategory,
            categoryNames = categoryNames,
            uncategorizedId = uncategorizedId,
            merchantTotals = merchantTotals,
            notInTotal = notInTotal,
            dayTotals = dayTotals,
        )
    }

    /**
     * Show [month]: kept in [handle] under [SELECTED_MONTH], so a process
     * death comes back to it, then read.
     */
    fun select(month: YearMonth): Job {
        handle[SELECTED_MONTH] = month.toString()
        return read.ask(month)
    }

    /**
     * The `Months` sheet has opened: read it as of now, as a new question
     * whatever the last opening's answer was.
     */
    fun openMonths(): Job = months.ask(++openings)

    private companion object {
        /** `monthByCategory`'s limit, set past any category count: no effective limit. */
        const val EVERY_CATEGORY = Int.MAX_VALUE

        /** `merchantTotals`' limit, set past any merchant count: no effective limit. */
        const val EVERY_MERCHANT = Int.MAX_VALUE
    }
}
