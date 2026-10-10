package my.pinged.charts.model

import my.pinged.ui.theme.Separator
import java.time.LocalDate

/**
 * The collecting gate (#81, Collecting; #66), the one piece of state the
 * category bars and the merchant ranking both sit behind. While it is shut,
 * one block stands in for both; the grid, the hero and the "not in the total"
 * block draw regardless.
 *
 * Nothing is stored. [days] can only grow, since capture evidence is deleted
 * only by Delete everything, which also resets the history start; so once
 * [open], it stays open with no flag to keep.
 *
 * @property days captured days from the history start to yesterday.
 */
data class Collecting(val days: Int) {
    /** Fourteen captured days: the bars and the ranking draw. */
    val open: Boolean get() = days >= GATE_DAYS

    /** The block's mono line, `COLLECTING · N OF 14 DAYS`, 0 included. */
    val label: String get() = "COLLECTING$Separator$days OF $GATE_DAYS DAYS"

    /** The block's serif line. */
    val line: String get() = "Category bars and top merchants appear after two weeks of capture."
}

/** Two weeks of capture (#66). */
const val GATE_DAYS = 14

/**
 * The gate as of [today]: the [captured] days in `[historyStart, today − 1]`.
 *
 * Captured days, not calendar days, so a listener dead for ten days does not
 * open it on four days of evidence. Across all history, not the selected
 * month, so every month is not hidden until its 14th, and a restored history
 * counts in full at once. Today is never counted: it is not a captured day
 * until it ends (#64). [captured] may hold days outside the range.
 */
fun collecting(historyStart: LocalDate?, today: LocalDate, captured: Set<LocalDate>): Collecting {
    if (historyStart == null) return Collecting(0)
    return Collecting(captured.count { it >= historyStart && it < today })
}
