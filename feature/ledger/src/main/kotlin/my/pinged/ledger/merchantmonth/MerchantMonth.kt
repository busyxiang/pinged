package my.pinged.ledger.merchantmonth

import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.ledger.MonthFigures
import my.pinged.ledger.monthFigures
import my.pinged.ledger.home.ChooserRead
import my.pinged.ui.RINGGIT
import java.time.LocalDate
import java.time.YearMonth

/**
 * Everything one read of `MerchantMonthViewModel.read` found, in one
 * transaction, for the merchant identity the key resolves to now.
 *
 * @property identity that identity: the key's merge target, or the key.
 * @property name `TxnDao.merchantName`, what the merchant goes by, read only
 *   when [totals] has no ringgit line to name it; null otherwise.
 * @property notCaptured the month's not-captured days, `MonthRecord`'s, the
 *   rule Charts greys `WHO GOT IT` by.
 * @property totals `TxnDao.merchantMonth`, every currency.
 * @property keptOut `TxnDao.merchantMonthKeptOut`, every currency.
 * @property firstHeld the review inbox's first card among the held rows
 *   [keptOut] counts, or null when none is.
 * @property rows `TxnDao.merchantMonthRows` in ringgit: every row [totals]'
 *   ringgit line counts, in the feed's order.
 * @property chooser the category chooser's half, for the rows' chips.
 */
internal data class MerchantMonthRead(
    val identity: String,
    val name: String?,
    val month: YearMonth,
    val today: LocalDate,
    val notCaptured: Int,
    val totals: List<MerchantTotal>,
    val keptOut: List<NotInTotal>,
    val firstHeld: Long?,
    val rows: List<FeedRow>,
    val chooser: ChooserRead,
)

/**
 * What Merchant month draws (spec 9.3, Merchant month): the merchant's name
 * and the header, whose figure and count are its `WHO GOT IT` row's.
 */
internal data class MerchantMonth(val name: String, val figures: MonthFigures)

/**
 * [read] as Merchant month draws it.
 *
 * **Ringgit only, as `WHO GOT IT` is.** The figure and the count are the
 * ringgit line's, SQLite's sums (spec 15.3) over the rows `merchantTotals`
 * counts for the identity, so the header is the row to the sen and `N
 * TRANSACTIONS` counts refunds as `N×` does. No percentages (#67).
 *
 * The name is that line's, as the row names it, and otherwise the one the
 * merchant goes by: a month whose every row was excluded or moved away still
 * opens, at zero, under a name. Never empty: the identity itself at last.
 */
internal fun merchantMonth(read: MerchantMonthRead): MerchantMonth {
    val line = read.totals.firstOrNull { it.currency == RINGGIT }
    return MerchantMonth(
        name = line?.displayName ?: read.name ?: read.identity,
        figures = monthFigures(
            netSen = line?.netSen ?: 0L,
            count = line?.txnCount ?: 0,
            keptOut = read.keptOut,
            month = read.month,
            today = read.today,
            notCaptured = read.notCaptured,
        ),
    )
}
