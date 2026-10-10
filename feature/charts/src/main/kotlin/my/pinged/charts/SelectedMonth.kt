package my.pinged.charts

import androidx.lifecycle.SavedStateHandle
import java.time.YearMonth

/** The [SavedStateHandle] key the selected month is kept under, as `2026-09`. */
const val SELECTED_MONTH = "selectedMonth"

/**
 * The month Charts shows: the one [handle] holds, else [now], which is then
 * written so it is the one held (#81).
 *
 * In the screen's own saved state, never on a navigation key, so a pushed Day
 * screen, a tab switch and a process death all come back to it. Written on
 * first read rather than left to default: a screen restored after a month
 * rolls over shows the month the user was looking at.
 *
 * A `String`, which a `Bundle` holds on every API level.
 */
fun selectedMonth(handle: SavedStateHandle, now: YearMonth): YearMonth {
    handle.get<String>(SELECTED_MONTH)?.let { return YearMonth.parse(it) }
    handle[SELECTED_MONTH] = now.toString()
    return now
}
