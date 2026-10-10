package my.pinged.charts.model

import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgitDigits

/** One line of the "not in the total" block: its label and its signed amount. */
data class KeptOut(val label: String, val amount: String)

/**
 * The "not in the total" block (#89; #81, "Not in the total" block): the MYR
 * rows of `TxnDao.notInTotal`, in the artboard's order. An empty list means
 * the block is not drawn.
 *
 * **A zero line is one whose figure is zero, as the prototype tests it.** The
 * two amount lines drop at a net of zero, so a transfer out and back again
 * never prints `0.00`. The review line's figure is its count, which its label
 * carries, so it stays while anything is pending even at a net of zero: two
 * payments are still waiting for the user, whatever they sum to.
 */
fun notInTotal(rows: List<NotInTotal>): List<KeptOut> {
    val ringgit = rows.filter { it.currency == RINGGIT }.associateBy { it.line }
    return buildList {
        ringgit[NotInTotalLine.TRANSFERS]?.takeIf { it.netSen != 0L }?.let {
            add(KeptOut("Transfers, left out", ringgitDigits(it.netSen)))
        }
        ringgit[NotInTotalLine.USER_EXCLUDED]?.takeIf { it.netSen != 0L }?.let {
            add(KeptOut("You excluded", ringgitDigits(it.netSen)))
        }
        ringgit[NotInTotalLine.PENDING]?.takeIf { it.txnCount != 0 }?.let {
            add(KeptOut("${it.txnCount} awaiting review", ringgitDigits(it.netSen)))
        }
    }
}
