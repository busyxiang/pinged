package my.pinged.charts

import my.pinged.data.CaptureRecord
import my.pinged.data.dao.CategoryTotal
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.DayTotal
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.MonthSplit
import my.pinged.data.dao.NotInTotal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything one read of `ChartsViewModel.read` found, published in one piece (#81,
 * The Charts read), so no section draws a number from a different moment than
 * the chip and the grey beside it.
 *
 * Plain values with no Room in them: the mapping layer in `model` turns this
 * into what the screen draws, and is tested on the JVM from hand-built ones.
 * Each section that needs a new query adds a field here and a line to the read.
 *
 * @property month the selected month, which every figure here covers.
 * @property record the capture record, read for the month before [month]
 *   through its end: the month's trust, the comparison's two months, the
 *   grid's days, the history start and the collecting gate (#305). Its `today`
 *   is the day the read was taken, so a chip and a not-captured count cannot
 *   straddle midnight.
 * @property monthTotals the month's net per currency, the Ledger's figure.
 * @property monthSplit the month's gross out and gross back per currency.
 * @property previousMonthTotals the net per currency of the calendar month
 *   before [month], for the comparison line (#67). Its trust is [record]'s
 *   answer for that month.
 * @property notInTotal the month's money kept out of [monthTotals], per line
 *   and currency, for the "not in the total" block (#89).
 * @property byCategory the month's net per category and currency, every
 *   category: the bars fold the remainder themselves (#81, The Charts read).
 * @property categoryNames each category's name by id, for the bars' labels.
 * @property uncategorizedId Uncategorized's id, drawn last and never ranked;
 *   null if the row is missing, and then nothing is set apart.
 * @property merchantTotals the month's net and count per merchant identity
 *   and currency, every merchant: the ranking sums the rest itself (#92).
 * @property dayTotals the month's `TxnDao.dayTotals`, every currency, for the
 *   daily rhythm grid.
 *
 * **No field has a default**, so a field the read forgets to pass is a
 * compile error rather than an empty section. Tests build one through
 * `chartsRead` in the JVM tests, which names every field in one place.
 */
data class ChartsRead(
    val month: YearMonth,
    val record: CaptureRecord,
    val monthTotals: List<CurrencyTotal>,
    val monthSplit: List<MonthSplit>,
    val previousMonthTotals: List<CurrencyTotal>,
    val notInTotal: List<NotInTotal>,
    val byCategory: List<CategoryTotal>,
    val categoryNames: Map<Long, String>,
    val uncategorizedId: Long?,
    val merchantTotals: List<MerchantTotal>,
    val dayTotals: List<DayTotal>,
) {
    /** The day the read was taken: [record]'s. */
    val today: LocalDate get() = record.today
}
