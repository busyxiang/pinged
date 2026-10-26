package my.pinged.ledger.day

import android.app.Application
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import my.pinged.capture.GuardedRunner
import my.pinged.data.CaptureRecord
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.read.ScreenRead
import my.pinged.ui.RINGGIT
import java.time.LocalDate

/**
 * The `Day` screen's holder (#69, #81): one day's rows and its header, read
 * whole through `:core:read`'s [ScreenRead] (#296).
 *
 * [date] is the whole of what it is built from -- the `Day` key carries
 * nothing else -- so a holder rebuilt after a process death reads the day
 * afresh and shows nothing stale. The question is therefore fixed.
 *
 * **Every write is followed by a re-read.** The rows are a list, not the
 * Ledger's `PagingSource`, so nothing invalidates them: a category saved or a
 * merchant renamed would otherwise not show until the next resume. [rows]
 * writes through [read], which reads it back in the write's own turn.
 * [today] is asked as each read begins, so a test can place the day relative
 * to it.
 */
class DayViewModel(
    app: Application,
    val date: LocalDate,
    private val today: () -> LocalDate = LocalDate::now,
) : AndroidViewModel(app) {

    /** A getter, not a field, for the reason `SourcesViewModel` records. */
    private val context: Context get() = getApplication<Application>()

    private val runner = GuardedRunner(app)

    /** Tests only: runs inside each read's transaction, after the rows are read. */
    @VisibleForTesting
    internal var midRead: () -> Unit = {}

    /**
     * The day, read whole. `DayScreen` reads it again on resume and on
     * `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, DayRead> = ScreenRead.fixed(viewModelScope, runner, "The day cannot be read") { db ->
        readDay(db, today())
    }

    /** The row's category and merchant actions, shared with the Ledger; the day is read again after each write. */
    val rows = RowActions(
        scope = viewModelScope,
        runner = runner,
        screen = read.rowScreen(),
    )

    /**
     * The day's rows, its counted total, #89's "not in the total" aggregate
     * over it, whether Pinged was watching it, and the chooser's half, all
     * through [db] and published in one piece.
     *
     * **In one transaction** (`ReadRunner.read`), so the header and the rows
     * beneath it are one snapshot: a stage-two commit between the rows and the
     * total would otherwise put a total over rows that leave it out. The
     * transaction holds Room's one connection, so a capture waits for it.
     * Measured on emulator-5554 over 20,000 constructed rows across two years,
     * about 27 on the day: 1.6ms median and 2.3ms at the longest of 40, after
     * a first read of 27ms, before the capture record, which adds 1.0 to 1.2ms
     * (`CaptureRecord.read`).
     */
    private fun readDay(db: PingedDatabase, today: LocalDate): DayRead {
        val txns = db.txnDao()
        val day = LocalDates.of(date)
        val dayRows = txns.dayRows(day)
        midRead()
        val counted = txns.dayTotals(day, day).map { CurrencyTotal(it.currency, it.netSen) }
        val keptOut = txns.notInTotal(day, day)
        val status = CaptureRecord.read(db, today, date..date).day(date)
        // Through [db]: the same instance and the same snapshot.
        val chooser = rows.readChooser(db)
        return DayRead(
            rows = dayRows,
            heading = dayHeading(
                rowCount = dayRows.size,
                counted = counted,
                notInTotal = keptOut,
                status = status,
                // [dayTotals]' COUNTED, over the rows already read.
                payments = dayRows.count {
                    it.txn.state == TxnState.COMMITTED && !it.txn.isExcluded && it.txn.currency == RINGGIT
                },
            ),
            chooser = chooser,
        )
    }
}

/**
 * What one read of [DayViewModel.read] found.
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
