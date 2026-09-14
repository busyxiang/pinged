package my.pinged.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * A calendar day as `yyyymmdd`, the form the `local_date` column holds.
 *
 * A value class rather than a bare `Int` because of what a wrong value costs:
 * `TxnDao.feed` orders on `local_date` first, the list's day headings break on
 * it, and every month aggregate ranges over it -- so one bad write misplaces
 * the row, mislabels the day it is drawn under, and moves the money into
 * another month. An epoch-millis or a month number satisfies an `Int`
 * parameter; neither satisfies this.
 *
 * Room stores it as the underlying `INTEGER`.
 *
 * Comparable because the zero-padded packing orders days the way the calendar
 * does. That is not decoration: `TxnDao`'s aggregates bound a month with
 * `BETWEEN` and the feed orders on the raw column, so both already rely on it,
 * and [LocalDates.monthRange] returns a `ClosedRange` that says so in a type.
 */
@JvmInline
value class LocalDate(val yyyymmdd: Int) : Comparable<LocalDate> {
    override fun compareTo(other: LocalDate): Int = yyyymmdd.compareTo(other.yyyymmdd)
}

/**
 * Everything that packs or unpacks a [LocalDate].
 *
 * The arithmetic appears exactly twice, once per direction, and belongs
 * nowhere else: a caller that writes it out again buys the costs [LocalDate]
 * documents, with no compile error to warn it. `DaoTest`'s local-date tests
 * pin both directions against literal `yyyymmdd` decimals, and record why a
 * round trip through the pair would pin nothing.
 */
object LocalDates {
    /**
     * `yyyymmdd` for an instant, in [zone].
     *
     * Spec 15.7: the zone is the device's current zone, resolved **once** at parse
     * time and written into the column -- "one fixed zone" means fixed at the
     * moment of the write, not a hardcoded region. The alternative it rules out is
     * deriving the day at query time through `strftime(..., 'localtime')`, which
     * depends on the process zone, cannot use the index, and reshuffles history
     * when the user travels.
     *
     * [zone] is a parameter so tests can assert day boundaries deterministically.
     */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        of(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate())

    /** The packing, for a day already resolved to a calendar. */
    fun of(day: java.time.LocalDate): LocalDate =
        LocalDate(day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth)

    /**
     * The first and last day of [month], inclusive at both ends -- which is
     * what the aggregates' `BETWEEN :from AND :to` wants, and why this is a
     * `ClosedRange` rather than a pair the caller has to read an order into.
     *
     * The last day comes off the calendar, so February and a 30-day month are
     * not a caller's problem to remember.
     */
    fun monthRange(month: YearMonth): ClosedRange<LocalDate> =
        of(month.atDay(1))..of(month.atEndOfMonth())

    /**
     * The stored day back as a calendar date, for a caller that needs a weekday
     * or a month name out of it -- there is no arithmetic in `yyyymmdd` that
     * answers either.
     *
     * Throws `DateTimeException` on an `Int` that is not a date, which is the
     * useful answer: a value that reached the column by some other arithmetic
     * has already misfiled a row.
     */
    fun calendarDay(date: LocalDate): java.time.LocalDate =
        java.time.LocalDate.of(
            date.yyyymmdd / 10_000,
            date.yyyymmdd / 100 % 100,
            date.yyyymmdd % 100,
        )
}
