package my.pinged.charts.model

import my.pinged.ui.monthName
import java.time.LocalDate
import java.time.YearMonth

/**
 * The header: the mono month name and the status chip on its right (#81,
 * Header chip). A null [chip] is drawn as no chip.
 */
data class Header(val month: String, val chip: Chip?)

/** The chip's words, and whether it is the accent's alert. */
data class Chip(val text: String, val alert: Boolean)

/**
 * The header for [month] seen on [today].
 *
 * The chip's precedence, decided by #81 rather than a ticket: an untrusted
 * month says so whatever else is true, since that is the one fact that changes
 * how every figure below reads. Otherwise a month before today's is
 * `MONTH ENDED`, and today's month is `DAY N OF M`. The plain chip says only
 * the date: which apps the month's total covers is the sources line's
 * (#365), and which days were captured is the alert chip's.
 */
fun header(month: YearMonth, today: LocalDate, trust: MonthTrust): Header {
    val chip = when {
        trust.untrusted -> Chip(notCapturedLabel(trust.notCaptured), alert = true)
        month < YearMonth.from(today) -> Chip("MONTH ENDED", alert = false)
        else -> Chip("DAY ${today.dayOfMonth} OF ${month.lengthOfMonth()}", alert = false)
    }
    return Header(monthName(month), chip)
}
