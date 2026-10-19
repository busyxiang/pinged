package my.pinged.ledger.day

import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgit
import my.pinged.ui.theme.Separator

/**
 * The `Day` screen's header below the date (#69, #81's `Day` screen).
 *
 * [total] is the day's counted ringgit, or null when nothing may be said about
 * it. [keptOut] is the "kept out of the total" line, or null when nothing is.
 * [notWatching] is whether the "Pinged was not watching" line is drawn.
 * [awaitingReview] is the *M awaiting review* that ends [keptOut] and opens
 * the review inbox (§9.1), or null when M is zero and there is nothing to open.
 * [keptOut] ends with it whenever it is non-null; the screen relies on that to
 * make only that part tappable. [captureLine] is the board's line under the
 * total (#228), or null on a day [notWatching] already speaks for.
 */
internal data class DayHeading(
    val total: String?,
    val keptOut: String?,
    val notWatching: Boolean,
    val awaitingReview: String? = null,
    val captureLine: String? = null,
)

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
 *
 * The capture line counts [payments], the ringgit rows in the total, and says
 * Pinged was watching only when [watched], the day is in `capturedDates`. A
 * day neither watched nor [notWatching] -- before the history starts, or
 * today before a bind -- gets the count alone, a claim about neither. The
 * board's "WATCHED ALL DAY" is not drawn: `capture_day` records that the
 * listener was bound at some point in the day, not for all of it.
 */
internal fun dayHeading(
    rowCount: Int,
    counted: List<CurrencyTotal>,
    notInTotal: List<NotInTotal>,
    notWatching: Boolean,
    payments: Int = 0,
    watched: Boolean = false,
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
    val awaitingReview = "$pending awaiting review"
    val keptOut = if (excluded + pending == 0) {
        null
    } else {
        ringgit(keptOutSen) + " kept out of the total: $excluded excluded, " + awaitingReview
    }
    val captureLine = if (notWatching) {
        null
    } else {
        (if (payments == 1) "1 PAYMENT" else "$payments PAYMENTS") + (if (watched) Separator + WATCHED else "")
    }
    return DayHeading(total, keptOut, notWatching, awaitingReview.takeIf { pending > 0 }, captureLine)
}

/** The capture line's second half on a day `capture_day` holds. */
internal const val WATCHED = "PINGED WAS WATCHING"
