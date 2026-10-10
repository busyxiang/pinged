package my.pinged.ledger.day

import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgit

/**
 * The `Day` screen's header below the date (#69, #81's `Day` screen).
 *
 * [total] is the day's counted ringgit, or null when nothing may be said about
 * it. [keptOut] is the "kept out of the total" line, or null when nothing is.
 * [notWatching] is whether the "Pinged was not watching" line is drawn.
 */
internal data class DayHeading(val total: String?, val keptOut: String?, val notWatching: Boolean)

/**
 * [DayHeading] from the day's reads: [counted] is `dayTotals` over the day,
 * [notInTotal] is #89's aggregate over the day, and [notWatching] is
 * `notCaptured` for it.
 *
 * Ringgit only, as the grid cell the day was opened from is (#81). The total is
 * signed, so a refund-only day reads below zero as its cell does.
 *
 * **A day Pinged was not watching, with no rows, has no total.** `RM0.00` there
 * would be "nothing spent", which is the one thing the grid's hatching exists
 * not to claim (#69); the line stands alone. With rows it keeps its total, the
 * sum of what Pinged has, beside the line saying that is all it has.
 *
 * The kept-out line is drawn when the day has any ringgit row kept out, that
 * is N + M above zero, where N is #89's two excluded lines and M its pending
 * line. Not on a non-zero net: an excluded RM50 purchase and a pending RM50
 * refund net to zero, and would hide each other.
 */
internal fun dayHeading(
    rowCount: Int,
    counted: List<CurrencyTotal>,
    notInTotal: List<NotInTotal>,
    notWatching: Boolean,
): DayHeading {
    val total = if (notWatching && rowCount == 0) {
        null
    } else {
        ringgit(counted.filter { it.currency == RINGGIT }.sumOf { it.netSen })
    }
    val myr = notInTotal.filter { it.currency == RINGGIT }
    val keptOutSen = myr.sumOf { it.netSen }
    val excluded = myr.filter { it.line != NotInTotalLine.PENDING }.sumOf { it.txnCount }
    val pending = myr.filter { it.line == NotInTotalLine.PENDING }.sumOf { it.txnCount }
    val keptOut = if (excluded + pending == 0) {
        null
    } else {
        ringgit(keptOutSen) + " kept out of the total: $excluded excluded, $pending pending"
    }
    return DayHeading(total, keptOut, notWatching)
}
