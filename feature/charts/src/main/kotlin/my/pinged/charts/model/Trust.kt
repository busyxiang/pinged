package my.pinged.charts.model

import my.pinged.data.notCapturedDays
import java.time.LocalDate
import java.time.YearMonth

/**
 * Whether the month's figures can be stood behind: [notCaptured] is
 * `notCapturedDays`, the one rule the Ledger and `Months` grey on too (#81).
 * One day is enough; there is no threshold (#73).
 */
data class MonthTrust(val notCaptured: Int) {
    /** The month has at least one not-captured day, and every figure greys. */
    val untrusted: Boolean get() = notCaptured > 0
}

/** [MonthTrust] for [month] from the read's evidence. */
fun monthTrust(month: YearMonth, historyStart: LocalDate?, today: LocalDate, captured: Set<LocalDate>): MonthTrust =
    MonthTrust(notCapturedDays(month, historyStart, today, captured))

/** `3 DAYS NOT CAPTURED`, or `1 DAY …`: the chip's and the hero line's words. */
fun notCapturedLabel(days: Int): String = "$days ${if (days == 1) "DAY" else "DAYS"} NOT CAPTURED"
