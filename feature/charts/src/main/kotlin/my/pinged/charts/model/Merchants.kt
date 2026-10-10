package my.pinged.charts.model

import my.pinged.data.dao.MerchantTotal
import my.pinged.ui.RINGGIT
import my.pinged.ui.ringgitDigits

/**
 * `WHO GOT IT`: the month's top merchants (#81, Merchants; #68).
 *
 * @property rows the top [TOP_MERCHANTS], largest net first.
 * @property rest the merchants at or above zero past them, or null when none are.
 * @property negatives each merchant that netted negative, on its own row
 *   after [rest], signed, never folded into it (#68).
 * @property greyed every total and count takes the hero's grey: the month is
 *   untrusted (#73).
 */
data class Merchants(
    val rows: List<MerchantRow>,
    val rest: MerchantRest?,
    val negatives: List<MerchantRow>,
    val greyed: Boolean,
)

/**
 * One ranked merchant under its identity's name (spec 6.4).
 *
 * @property count its transactions, `18×`.
 * @property amount its signed net without the symbol, `312.40` or `−45.00`.
 */
data class MerchantRow(val name: String, val count: String, val amount: String)

/** `N more merchants` and their summed net, signed, without the symbol. */
data class MerchantRest(val label: String, val amount: String)

/**
 * The ranking from the month's `TxnDao.merchantTotals`, read with no
 * effective limit (#81, The Charts read). Only the ringgit rows are read.
 *
 * The merchants at or above zero rank largest first: the top
 * [TOP_MERCHANTS] are drawn and the rest summed here from SQLite's
 * per-merchant totals, never from `txn` rows (§15.3). Then each merchant that
 * netted negative, on its own row with its sign, last (#68): folded into the
 * rest it would shrink a sum the reader takes for spending, as the bars'
 * `Everything else` never takes a negative category either.
 */
fun merchants(totals: List<MerchantTotal>, trust: MonthTrust): Merchants {
    // Ties by identity, so equal merchants do not swap places between two
    // reads: SQLite's ORDER BY leaves their order unspecified.
    val ordered = totals.filter { it.currency == RINGGIT }
        .sortedWith(compareByDescending<MerchantTotal> { it.netSen }.thenBy { it.identityKey })
    val ranked = ordered.filter { it.netSen >= 0L }
    val rest = ranked.drop(TOP_MERCHANTS)
    return Merchants(
        rows = ranked.take(TOP_MERCHANTS).map(::row),
        rest = rest.takeIf { it.isNotEmpty() }?.let {
            MerchantRest(moreMerchants(it.size), ringgitDigits(it.sumOf { m -> m.netSen }))
        },
        negatives = ordered.filter { it.netSen < 0L }.map(::row),
        greyed = trust.untrusted,
    )
}

/** A merchant under its identity's name, which a row with no name falls back to. */
private fun row(total: MerchantTotal): MerchantRow =
    MerchantRow(total.displayName ?: total.identityKey, "${total.txnCount}×", ringgitDigits(total.netSen))

/** How many merchants the ranking draws by name. */
const val TOP_MERCHANTS = 5

/**
 * `N more merchants`, pluralised as `notCapturedLabel` is: `1 more merchant`
 * rather than the spec's literal `N … merchants`.
 */
private fun moreMerchants(n: Int): String = "$n more ${if (n == 1) "merchant" else "merchants"}"
