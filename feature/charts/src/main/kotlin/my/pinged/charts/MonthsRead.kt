package my.pinged.charts

import my.pinged.data.dao.MonthlyTotal
import java.time.LocalDate

/**
 * What one `ChartsViewModel.readMonths` read for the `Months` sheet (#75),
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
