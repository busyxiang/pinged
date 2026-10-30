package my.pinged.ledger.breakdown

import my.pinged.charts.model.MonthTrust
import my.pinged.charts.model.notCapturedLabel
import my.pinged.data.dao.BreakdownLine
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ui.RINGGIT
import my.pinged.ui.monthName
import my.pinged.ui.ringgit
import my.pinged.ui.ringgitDigits
import my.pinged.ui.theme.Separator
import java.time.LocalDate
import java.time.YearMonth

/**
 * One line of Breakdown, as its expansion is saved: a merchant identity, or
 * the rows with no merchant. [saved] is what the screen's saved state holds,
 * and a prefix keeps the one from ever being read as the other.
 */
@JvmInline
value class LineKey(val saved: String) {
    /** The identity the line is, or null for `No merchant`. */
    val identity: String? get() = if (saved == NO_MERCHANT_KEY) null else saved.removePrefix(MERCHANT_PREFIX)

    companion object {
        /** The line [identity] heads, null for `No merchant`. */
        fun of(identity: String?): LineKey = LineKey(identity?.let { MERCHANT_PREFIX + it } ?: NO_MERCHANT_KEY)

        private const val MERCHANT_PREFIX = "m:"
        private const val NO_MERCHANT_KEY = "none"
    }
}

/**
 * Everything one read of `BreakdownViewModel.read` found, in one transaction.
 *
 * @property category the category as it is now, or null when it no longer
 *   exists: deleted, or merged into another. The screen then leaves.
 * @property notCaptured the month's not-captured days, `MonthRecord`'s, the
 *   rule Charts greys the bar by.
 * @property lines `TxnDao.breakdownLines`, every currency.
 * @property keptOut `TxnDao.breakdownKeptOut`, every currency.
 * @property firstHeld the review inbox's first card among the held rows
 *   [keptOut] counts, or null when none is.
 * @property rows the rows behind each expanded line that exists, by its key.
 * @property chooser the category chooser's half, for the rows' chips.
 */
internal data class BreakdownRead(
    val category: Category?,
    val month: YearMonth,
    val today: LocalDate,
    val notCaptured: Int,
    val lines: List<BreakdownLine>,
    val keptOut: List<NotInTotal>,
    val firstHeld: Long?,
    val rows: Map<LineKey, List<FeedRow>>,
    val chooser: ChooserRead,
)

/**
 * What Breakdown draws (spec 9.3, Breakdown).
 *
 * @property label `SPENT IN SEPTEMBER 2026`, as Charts' hero words it.
 * @property figure the category's ringgit net, signed: the bar's figure.
 * @property count `56 TRANSACTIONS`, the lines' counts summed.
 * @property incomplete the one line that says why an untrusted month greys.
 * @property keptOut `RM45.00 kept out: 0 excluded, 1 awaiting review`, or
 *   null when nothing was; it ends with [awaitingReview] whenever that is not null.
 * @property awaitingReview the part that opens the review inbox, or null
 *   when nothing is held.
 * @property greyed every total and count greys; names stay in ink (#73).
 */
internal data class Breakdown(
    val name: String,
    val iconKey: String,
    val label: String,
    val figure: String,
    val count: String,
    val incomplete: String?,
    val keptOut: String?,
    val awaitingReview: String?,
    val lines: List<Line>,
    val greyed: Boolean,
)

/** One merchant line: the identity's name, `14×` and its signed net without the symbol. */
internal data class Line(val key: LineKey, val name: String, val noMerchant: Boolean, val count: String, val amount: String)

/**
 * [read] as Breakdown draws it, or null when its category is gone.
 *
 * **Ringgit only, as the bar is.** The header is the lines' sum, not a figure
 * of its own, so they cannot disagree, and the lines are SQLite's sums
 * (spec 15.3), so the header is the bar to the sen. No percentages (#67): a
 * refund can make the base zero or negative.
 *
 * The lines are `WHO GOT IT`'s order: largest net first, the net-negative
 * last and signed, ties by identity so two reads do not swap them, with
 * `No merchant` ranked by its net like any other and its tie last.
 *
 * The label is Charts' hero's: `NET SPENT` at or below zero, and `SO FAR`
 * in the month still running.
 */
internal fun breakdown(read: BreakdownRead): Breakdown? {
    val category = read.category ?: return null
    val ringgit = read.lines.filter { it.currency == RINGGIT }
        .sortedWith(compareByDescending<BreakdownLine> { it.netSen }.thenBy(nullsLast()) { it.identityKey })
    val net = ringgit.sumOf { it.netSen }
    val count = ringgit.sumOf { it.txnCount }
    val running = read.month == YearMonth.from(read.today)
    val spent = if (net > 0) "SPENT" else "NET SPENT"
    val keptOut = read.keptOut.filter { it.currency == RINGGIT }
    val excluded = keptOut.filter { it.line != NotInTotalLine.PENDING }.sumOf { it.txnCount }
    val pending = keptOut.filter { it.line == NotInTotalLine.PENDING }.sumOf { it.txnCount }
    val awaitingReview = "$pending awaiting review"
    // Charts' own rule for the month, so the bar and this screen grey together.
    val trust = MonthTrust(read.notCaptured)
    return Breakdown(
        name = category.name,
        iconKey = category.iconKey,
        label = spent + (if (running) " SO FAR" else "") + " IN " + monthName(read.month),
        figure = ringgit(net),
        count = if (count == 1) "1 TRANSACTION" else "$count TRANSACTIONS",
        incomplete = if (trust.untrusted) "MAY BE INCOMPLETE$Separator${notCapturedLabel(trust.notCaptured)}" else null,
        // On a count, not a net, as Day's header is: an excluded purchase and
        // a held refund of one amount would hide each other.
        keptOut = if (excluded + pending == 0) {
            null
        } else {
            ringgit(keptOut.sumOf { it.netSen }) + " kept out: $excluded excluded, " + awaitingReview
        },
        awaitingReview = awaitingReview.takeIf { pending > 0 },
        lines = ringgit.map {
            val identity = it.identityKey
            Line(
                key = LineKey.of(identity),
                name = if (identity == null) NO_MERCHANT else it.displayName ?: identity,
                noMerchant = identity == null,
                count = "${it.txnCount}×",
                amount = ringgitDigits(it.netSen),
            )
        },
        greyed = trust.untrusted,
    )
}

/** The line for the rows with no merchant key, manual entries among them. */
internal const val NO_MERCHANT = "No merchant"
