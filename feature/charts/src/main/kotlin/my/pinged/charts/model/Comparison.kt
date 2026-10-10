package my.pinged.charts.model

import my.pinged.data.notCapturedDays
import my.pinged.ui.monthName
import my.pinged.ui.ringgit
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * Whether [month] is a **comparable month** (`CONTEXT.md`; #81, Capture
 * evidence): it has ended by [today], it lies wholly on or after
 * [historyStart], and it has no not-captured day.
 *
 * A null [historyStart] makes nothing comparable: with no record there is no
 * month the record wholly covers, and `notCapturedDays`' 0 for it means
 * "nothing to count", not "whole".
 */
fun comparableMonth(month: YearMonth, historyStart: LocalDate?, today: LocalDate, captured: Set<LocalDate>): Boolean =
    historyStart != null &&
        month < YearMonth.from(today) &&
        month.atDay(1) >= historyStart &&
        notCapturedDays(month, historyStart, today, captured) == 0

/**
 * The line under the hero that sets [month]'s net against the month directly
 * before it (#81, Comparison line; #67), or null when it is held back, which
 * the screen draws as nothing at all.
 *
 * **Both months must be comparable**, and the month before is the only one
 * ever used: when it is not comparable there is no line, never one against an
 * older month, which would hide that the two are not next to each other. An
 * untrusted month is never comparable, so this line and the hero's
 * `MAY BE INCOMPLETE` cannot both be drawn.
 *
 * **The wording** compares the two signed nets, the figures each month's hero
 * shows. The difference is unsigned and the direction word carries the sign;
 * the other month's figure keeps its sign. No percentage: a zero or negative
 * base makes one meaningless.
 *
 * [captured] must cover both months from the history start on, as
 * `ChartsRead.captured` does whenever the month before could be comparable.
 */
fun comparison(
    month: YearMonth,
    today: LocalDate,
    historyStart: LocalDate?,
    captured: Set<LocalDate>,
    netSen: Long,
    previousNetSen: Long,
): String? {
    val before = month.minusMonths(1)
    if (!comparableMonth(month, historyStart, today, captured)) return null
    if (!comparableMonth(before, historyStart, today, captured)) return null
    val previous = monthName(before).substringBefore(' ') + "'S " + ringgit(previousNetSen)
    return when {
        netSen > previousNetSen -> "${ringgit(abs(netSen - previousNetSen))} ABOVE $previous"
        netSen < previousNetSen -> "${ringgit(abs(netSen - previousNetSen))} BELOW $previous"
        else -> "SAME AS $previous"
    }
}
