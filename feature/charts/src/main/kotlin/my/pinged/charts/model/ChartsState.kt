package my.pinged.charts.model

import my.pinged.charts.ChartsRead
import my.pinged.ui.RINGGIT
import my.pinged.ui.monthName

/** What the Charts screen draws, from its last read. */
sealed interface ChartsState {
    /** The first read is in flight. Never drawn as empty. */
    data object Reading : ChartsState

    /**
     * No history start: Pinged has no capture evidence at all (#65). It never
     * falls back to today, which would draw a month of not-captured days.
     */
    data class Empty(val month: String) : ChartsState {
        val label: String get() = "NOTHING CAPTURED YET"
        val line: String get() = "Charts begin on the first day Pinged sees a notification."
    }

    /** A month to draw. */
    data class Month(val view: MonthView) : ChartsState
}

/**
 * One month's sections, each built by its own function of the read.
 *
 * A section a later ticket adds -- the grid, the bars, the merchants, the "not
 * in the total" block, the comparison -- is a field here, a line in
 * [monthView], and a slot in `ChartsScreen`'s column.
 *
 * @property comparison the line against the month before, null when held back.
 * @property grid the daily rhythm grid, drawn while collecting too (#66).
 * @property bars the category bars (#91).
 * @property merchants the top-merchants ranking (#92).
 * @property collecting the gate the bars and the merchant ranking sit behind:
 *   while it is shut, one block draws in place of both.
 * @property notInTotal the "not in the total" block's lines; empty draws no block.
 */
data class MonthView(
    val trust: MonthTrust,
    val header: Header,
    val hero: Hero,
    val comparison: String?,
    val grid: RhythmGrid,
    val bars: Bars,
    val merchants: Merchants,
    val collecting: Collecting,
    val notInTotal: List<KeptOut>,
)

/** [read] as the screen draws it; null is a read not yet landed. */
fun chartsState(read: ChartsRead?): ChartsState = when {
    read == null -> ChartsState.Reading
    read.historyStart == null -> ChartsState.Empty(monthName(read.month))
    else -> ChartsState.Month(monthView(read))
}

/** The sections of a month that has a history start. */
fun monthView(read: ChartsRead): MonthView {
    val trust = monthTrust(read.month, read.historyStart, read.today, read.captured)
    val split = read.monthSplit.firstOrNull { it.currency == RINGGIT }
    return MonthView(
        trust = trust,
        header = header(read.month, read.today, trust),
        hero = hero(
            netSen = read.monthTotals.firstOrNull { it.currency == RINGGIT }?.netSen ?: 0L,
            spentSen = split?.spentSen ?: 0L,
            cameBackSen = split?.cameBackSen ?: 0L,
            trust = trust,
        ),
        comparison = comparison(
            read.month,
            read.today,
            read.historyStart,
            read.captured,
            read.monthTotals.firstOrNull { it.currency == RINGGIT }?.netSen ?: 0L,
            read.previousMonthTotals.firstOrNull { it.currency == RINGGIT }?.netSen ?: 0L,
        ),
        grid = rhythmGrid(read.month, read.today, read.historyStart, read.captured, read.dayTotals),
        bars = bars(read.byCategory, read.categoryNames, read.uncategorizedId, trust),
        merchants = merchants(read.merchantTotals, trust),
        collecting = collecting(read.historyStart, read.today, read.capturedSinceStart),
        notInTotal = notInTotal(read.notInTotal),
    )
}
