package my.pinged.charts.model

import my.pinged.data.LocalDates
import my.pinged.data.dao.DayTotal
import my.pinged.ui.RINGGIT
import my.pinged.ui.dayMonthYear
import my.pinged.ui.ringgit
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The daily rhythm grid (spec 8, #81 Cell classifier and Grid ramp): one
 * [GridDay] per day of the month, Monday first, and the legend beside it.
 *
 * @property leadingBlanks the empty squares before the 1st, so the 1st sits
 *   under its weekday: 0 when the month starts on a Monday.
 * @property footer `Nothing before 7 September 2026` in the month the history
 *   start falls in (#65), else null.
 */
data class RhythmGrid(
    val leadingBlanks: Int,
    val days: List<GridDay>,
    val legend: GridLegend,
    val footer: String?,
)

/**
 * One day's square. [today] carries the today marker (#64), whatever the
 * state, which is never [CellState.NotCaptured] for today.
 */
data class GridDay(val date: LocalDate, val state: CellState, val today: Boolean) {
    /** Every state but blank opens the day (#69); a blank is outside the record. */
    val opensDay: Boolean get() = state != CellState.Blank
}

/** What a square draws. Spec 8's table, with #68's fourth state. */
sealed interface CellState {
    /** Net spent > 0, filled with `GridRamp[step]` (palest first). */
    data class Spent(val netSen: Long, val step: Int) : CellState

    /** More came back than went out (#68): outlined, with a dot. */
    data class NetNegative(val netSen: Long) : CellState

    /** Watched, and nothing counted, or a day that nets exactly zero: outlined. */
    data object NothingSpent : CellState

    /** A past day on or after the history start with no evidence: hatched. */
    data object NotCaptured : CellState

    /** After today, or before the history start with nothing to draw. */
    data object Blank : CellState
}

/**
 * The legend beside the grid (#68).
 *
 * @property steps the `GridRamp` indexes the month's spent days use, palest
 *   first; empty when nothing was spent.
 * @property range `RM33 → RM237 A DAY`, or `RM12 EVERY DAY YOU SPENT` beside
 *   one swatch; null with no spent day.
 * @property moreCameBack whether `MORE CAME BACK` is listed, which is only
 *   when a day nets negative.
 */
data class GridLegend(val steps: List<Int>, val range: String?, val moreCameBack: Boolean)

/**
 * The grid for [month] seen on [today].
 *
 * [dayTotals] are `TxnDao.dayTotals` over the month, every currency; only
 * MYR's are drawn (#81, Out of scope). [captured] is the read's captured set,
 * which already counts a day with a capture-backed transaction (#64).
 */
fun rhythmGrid(
    month: YearMonth,
    today: LocalDate,
    historyStart: LocalDate?,
    captured: Set<LocalDate>,
    dayTotals: List<DayTotal>,
): RhythmGrid {
    val nets = dayTotals.filter { it.currency == RINGGIT }
        .associate { LocalDates.calendarDay(it.localDate) to it.netSen }
    val states = (1..month.lengthOfMonth()).map { n ->
        val date = month.atDay(n)
        date to classify(date, nets[date], captured, historyStart, today)
    }
    val ramp = Ramp(states.mapNotNull { (_, s) -> (s as? CellState.Spent)?.netSen })
    val days = states.map { (date, state) ->
        val stepped = if (state is CellState.Spent) state.copy(step = ramp.step(state.netSen)) else state
        GridDay(date, stepped, date == today)
    }
    return RhythmGrid(
        leadingBlanks = month.atDay(1).dayOfWeek.value - 1,
        days = days,
        legend = GridLegend(
            steps = days.mapNotNull { (it.state as? CellState.Spent)?.step }.distinct().sorted(),
            range = ramp.range(),
            moreCameBack = days.any { it.state is CellState.NetNegative },
        ),
        footer = historyStart
            ?.takeIf { YearMonth.from(it) == month }
            ?.let { "Nothing before ${dayMonthYear(it)}" },
    )
}

/**
 * The grid's ramp over the month's spent days (#68, #81 Grid ramp): `k`, the
 * number of distinct amounts up to four, steps, the darkest `k` of `GridRamp`.
 *
 * #68's property: **the largest day is always the darkest step**, and equal
 * amounts always share a step. A day's step is `floor(r × k / d)`, where `d`
 * is the number of distinct positive amounts and `r` its amount's index
 * among them, ascending; with `d ≤ 4` that is one step per amount. The
 * largest has `r = d − 1`, and `floor((d − 1) × k / d) = k − 1` for every
 * `d ≥ k`. The prototype's formula ranked by position among the days, which
 * put a largest amount tied across many days below the darkest step
 * (`aTieAtTheTopIsStillTheDarkest`).
 *
 * Ranking rather than scaling, so one large day does not wash the rest pale.
 * The colour is relative to the month; [range] prints the absolute anchors
 * beside it (spec 8: never let the colour be the only reading).
 *
 * @param spent the spent days' net sen, every one > 0.
 */
private class Ramp(spent: List<Long>) {
    private val distinct = spent.distinct().sorted()
    private val k = minOf(GRID_STEPS, distinct.size)

    /** The `GridRamp` index, palest first, for a day that spent [sen]. */
    fun step(sen: Long): Int = GRID_STEPS - k + distinct.indexOf(sen) * k / distinct.size

    /**
     * `RMmin → RMmax A DAY`, in whole ringgit rounded half up as the artboard
     * prints RM32.50 as `RM33`; or `RMx EVERY DAY YOU SPENT` for one amount.
     * Two amounts that round to one figure print in sen instead, so the range
     * never reads from a number to itself (#68).
     */
    fun range(): String? {
        if (distinct.isEmpty()) return null
        val lo = distinct.first()
        val hi = distinct.last()
        return when {
            lo == hi -> "${wholeRinggit(lo)} EVERY DAY YOU SPENT"
            wholeRinggit(lo) == wholeRinggit(hi) -> "${ringgit(lo)} → ${ringgit(hi)} A DAY"
            else -> "${wholeRinggit(lo)} → ${wholeRinggit(hi)} A DAY"
        }
    }

    private fun wholeRinggit(sen: Long): String = String.format(Locale.ROOT, "RM%,d", (sen + 50) / 100)
}

/**
 * `GridRamp`'s size. The mapping layer names a step by its index and leaves
 * the colour to the composable; `GridRampTest` holds the two equal.
 */
internal const val GRID_STEPS = 4

/**
 * One day's state (#81's table, in its order of precedence).
 *
 * A day after today is blank whatever it holds. A day with a counted total
 * draws it, captured or not: a day with a capture-backed transaction is
 * captured anyway (#64), and a hand-entered total before the history start
 * still draws though it is never counted as captured or not (#65). Only then
 * does evidence decide between outlined and hatched, and today is never
 * hatched, since its evidence may not exist yet (#64).
 */
private fun classify(
    date: LocalDate,
    netSen: Long?,
    captured: Set<LocalDate>,
    historyStart: LocalDate?,
    today: LocalDate,
): CellState = when {
    date > today -> CellState.Blank
    netSen != null && netSen > 0 -> CellState.Spent(netSen, step = 0)
    netSen != null && netSen < 0 -> CellState.NetNegative(netSen)
    date == today || date in captured -> CellState.NothingSpent
    historyStart != null && date >= historyStart -> CellState.NotCaptured
    else -> CellState.Blank
}
