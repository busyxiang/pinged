package my.pinged.charts.model

import my.pinged.charts.MonthsRead
import my.pinged.ui.CALENDAR
import my.pinged.ui.RINGGIT
import my.pinged.ui.dayMonthYear
import my.pinged.ui.monthYear
import my.pinged.ui.ringgitDigits
import java.time.YearMonth

/** A line of the `Months` sheet: a month to pick, or the history-start divider. */
sealed interface MonthsItem {
    /**
     * A month to pick.
     *
     * @property name `September 2026`, as the artboard names a row.
     * @property net the MYR net with no symbol, signed below zero: `−120.00`.
     * @property now the current month, which carries `NOW`.
     * @property notCaptured `N DAYS NOT CAPTURED`, only when N ≥ 1.
     * @property greyed the total takes the hero's untrusted grey.
     */
    data class Row(
        val month: YearMonth,
        val name: String,
        val net: String,
        val now: Boolean,
        val notCaptured: String?,
        val greyed: Boolean,
    ) : MonthsItem

    /** `NOTHING BEFORE 4 APRIL 2026`, directly under the history start's month. */
    data class Divider(val text: String) : MonthsItem
}

/**
 * The `Months` sheet from [read] (#81, `Months` sheet; #75), newest first.
 *
 * - **No gaps from the history start's month to now.** A month with no
 *   transactions is listed at `0.00`: a wholly not-captured month is exactly
 *   the one the sheet must flag, so it cannot be one that drops out.
 * - **The divider** sits under the history start's month. Below it are only
 *   months that hold money from before the record began (hand-entered, §9.4);
 *   they list where they have totals, with no gap-filling, and never carry a
 *   count, since `notCapturedDays` counts nothing before the history start.
 * - **No history start**: now, and any month with money, with no divider.
 *   Nothing carries a count.
 * - **Never a month after now**, whatever a hand-entered date says.
 *
 * A month whose only rows are outside the total (pending, excluded) has no
 * `monthlyTotals` row, so before the history start it does not list.
 */
fun monthsSheet(read: MonthsRead): List<MonthsItem> {
    val now = YearMonth.from(read.today)
    val nets = read.totals
        .filter { it.currency == RINGGIT }
        .associate { YearMonth.of(it.month / 100, it.month % 100) to it.netSen }
    fun row(month: YearMonth) = row(month, nets[month] ?: 0L, now, monthTrust(month, read.historyStart, read.today, read.captured))

    val historyStart = read.historyStart
    val firstRecorded = historyStart?.let(YearMonth::from) ?: now
    val recorded = generateSequence(now) { it.minusMonths(1) }.takeWhile { it >= firstRecorded }.map(::row)
    val before = nets.keys.filter { it < firstRecorded }.sortedDescending().map(::row)
    val divider = historyStart?.let { MonthsItem.Divider("NOTHING BEFORE ${dayMonthYear(it).uppercase(CALENDAR)}") }
    return recorded.toList() + listOfNotNull(divider) + before
}

private fun row(month: YearMonth, netSen: Long, now: YearMonth, trust: MonthTrust) = MonthsItem.Row(
    month = month,
    name = monthYear(month),
    net = ringgitDigits(netSen),
    now = month == now,
    notCaptured = if (trust.untrusted) notCapturedLabel(trust.notCaptured) else null,
    greyed = trust.untrusted,
)

