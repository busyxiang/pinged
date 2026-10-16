package my.pinged.charts

import my.pinged.data.CaptureEvidence
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.MonthlyTotal
import java.time.LocalDate
import java.time.YearMonth

/**
 * What one [monthsRead] read for the `Months` sheet (#75),
 * published in one piece, so a row's total and its flag are from one moment.
 *
 * @property today the day the read was taken; its month is `NOW`.
 * @property historyStart null when nothing has ever been captured (#65).
 * @property totals every month's net per currency, up to [today]'s month.
 * @property captured the captured days over the same span.
 */
data class MonthsRead(
    val today: LocalDate,
    val historyStart: LocalDate?,
    val totals: List<MonthlyTotal>,
    val captured: Set<LocalDate>,
)

/**
 * The `Months` sheet's read as of [today] (#75): the history start, then two
 * statements. Charts and Spending's header (#192) both call it, each inside
 * its own `Databases.inOneTransaction`, so a month's total and the captured
 * days that flag it are of one snapshot. Measured on `ChartsViewModel.refresh`'s
 * ledger: 47ms median, 51ms at the longest of 40, of which two years of
 * `capturedDates` is 20ms.
 *
 * - `monthlyTotals` from the first packed day there is, since a month of
 *   money before the history start lists too (#75), to the end of [today]'s
 *   month. One grouped scan of `local_date`'s range, however long the history.
 * - `capturedDates` from the history start, before which nothing is counted,
 *   to the same end. With no history start nothing is counted, so it is not
 *   read.
 *
 * [midRead] runs between the two statements, for a test to land a write there.
 */
fun monthsRead(db: PingedDatabase, today: LocalDate, midRead: () -> Unit = {}): MonthsRead {
    val captureDays = db.captureDayDao()
    val historyStart = CaptureEvidence.historyStart(captureDays, db.rawCaptureDao())
    val end = YearMonth.from(today).atEndOfMonth()
    val totals = db.txnDao().monthlyTotals(EVERY_DAY, LocalDates.of(end))
    midRead()
    val captured = historyStart?.let { CaptureEvidence.capturedDates(captureDays, it, end) } ?: emptySet()
    return MonthsRead(today, historyStart, totals, captured)
}

/** Below every `yyyymmdd`: the bound that leaves no day out. */
private val EVERY_DAY = my.pinged.data.LocalDate(0)
