package my.pinged.charts

import my.pinged.data.CaptureRecord
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.MonthlyTotal
import java.time.LocalDate
import java.time.YearMonth

/**
 * What one [monthsRead] read for the `Months` sheet (#75),
 * published in one piece, so a row's total and its flag are from one moment.
 *
 * @property record the capture record over every month the sheet lists; its
 *   `today` is the day the read was taken, and its month is `NOW`.
 * @property totals every month's net per currency, up to the record's month.
 */
data class MonthsRead(
    val record: CaptureRecord,
    val totals: List<MonthlyTotal>,
) {
    /** The day the read was taken: [record]'s. */
    val today: LocalDate get() = record.today
}

/**
 * The `Months` sheet's read as of [today] (#75): the capture record, then one
 * statement. Charts and Spending's header (#192) both call it, each inside
 * its own `Databases.inOneTransaction`, so a month's total and the record
 * that flags it are of one snapshot.
 *
 * - `monthlyTotals` from the first packed day there is, since a month of
 *   money before the history start lists too (#75), to the end of [today]'s
 *   month. One grouped scan of `local_date`'s range, however long the history.
 * - The record over [CaptureRecord.EVERY_DAY], so any month the sheet lists
 *   may be asked of it. That reads every captured day from the history
 *   start: 8.8ms median over two years with 5,000 captured transactions and
 *   19.9ms with 15,000 (`CaptureRecord.read`), on top of the one grouped scan.
 *
 * [midRead] runs between the two, for a test to land a write there.
 */
fun monthsRead(db: PingedDatabase, today: LocalDate, midRead: () -> Unit = {}): MonthsRead {
    val end = YearMonth.from(today).atEndOfMonth()
    val totals = db.txnDao().monthlyTotals(EVERY_DAY, LocalDates.of(end))
    midRead()
    val record = CaptureRecord.read(db, today, CaptureRecord.EVERY_DAY)
    return MonthsRead(record, totals)
}

/** Below every `yyyymmdd`: the bound that leaves no day out. */
private val EVERY_DAY = my.pinged.data.LocalDate(0)
