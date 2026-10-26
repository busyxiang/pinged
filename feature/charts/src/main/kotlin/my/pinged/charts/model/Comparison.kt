package my.pinged.charts.model

import my.pinged.data.CaptureRecord
import my.pinged.ui.monthName
import my.pinged.ui.ringgit
import java.time.YearMonth
import kotlin.math.abs

/**
 * The line under the hero that sets [month]'s net against the month directly
 * before it (#81, Comparison line; #67), or null when it is held back, which
 * the screen draws as nothing at all.
 *
 * **Both months must be comparable** (`CaptureRecord.month`), and the month before is the only one
 * ever used: when it is not comparable there is no line, never one against an
 * older month, which would hide that the two are not next to each other. An
 * untrusted month is never comparable, so this line and the hero's
 * `MAY BE INCOMPLETE` cannot both be drawn.
 *
 * **A running month** (today's) is not comparable yet, and says when it will
 * be, `COMPARED WITH SEPTEMBER ONCE OCTOBER ENDS` (`ChartsMonth.dc.html`), so
 * the missing line reads as deliberate. Only when that is a promise it can
 * keep: the month before is comparable and this one has no not-captured day
 * so far. A gap already in it means it never will be, and the hero's
 * `MAY BE INCOMPLETE` says why.
 *
 * **The wording** compares the two signed nets, the figures each month's hero
 * shows. The difference is unsigned and the direction word carries the sign;
 * the other month's figure keeps its sign. No percentage: a zero or negative
 * base makes one meaningless.
 *
 * [record] must have been read for both months, or `CaptureRecord.month`
 * throws; `ChartsViewModel` reads it from the month before.
 */
fun comparison(
    month: YearMonth,
    record: CaptureRecord,
    netSen: Long,
    previousNetSen: Long,
): String? {
    val before = month.minusMonths(1)
    if (!record.month(before).comparable) return null
    val selected = record.month(month)
    if (month == YearMonth.from(record.today)) {
        if (selected.untrusted) return null
        return "COMPARED WITH ${shortName(before)} ONCE ${shortName(month)} ENDS"
    }
    if (!selected.comparable) return null
    val previous = shortName(before) + "'S " + ringgit(previousNetSen)
    return when {
        netSen > previousNetSen -> "${ringgit(abs(netSen - previousNetSen))} ABOVE $previous"
        netSen < previousNetSen -> "${ringgit(abs(netSen - previousNetSen))} BELOW $previous"
        else -> "SAME AS $previous"
    }
}

/** `SEPTEMBER`, the month's name without its year. */
private fun shortName(month: YearMonth): String = monthName(month).substringBefore(' ')
