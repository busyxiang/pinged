package my.pinged.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The locale month and day names are spelled in: English, as every label in
 * the app is. **Never `Locale.ROOT`**, which carries no calendar names --
 * `Month.getDisplayName(FULL, Locale.ROOT)` answers `M09`, and that shipped to
 * the emulator as "M09 2026" across the top of the Ledger, past a test that
 * built its expected string with the same function. Nor the device's, so what
 * is drawn does not depend on a setting.
 */
val CALENDAR: Locale = Locale.ENGLISH

/** `SEPTEMBER 2026`: the Ledger's and Charts' month heading. */
fun monthName(month: YearMonth): String = monthYear(month).uppercase(CALENDAR)

/** `September 2026`. */
fun monthYear(month: YearMonth): String = month.month.getDisplayName(TextStyle.FULL, CALENDAR) + " " + month.year

/** `3 September`. */
fun dayMonth(date: LocalDate): String = "${date.dayOfMonth} " + date.month.getDisplayName(TextStyle.FULL, CALENDAR)

/** `3 September 2026`. */
fun dayMonthYear(date: LocalDate): String = dayMonth(date) + " " + date.year

/** `Thursday 3 September 2026`: the `Day` screen's title. */
fun weekdayDate(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, CALENDAR) + " " + dayMonthYear(date)
