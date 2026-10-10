package my.pinged.data

import my.pinged.data.dao.CaptureDayDao
import my.pinged.data.dao.RawCaptureDao
import java.time.YearMonth
import java.time.ZoneId

/**
 * How many days of [month] have no capture evidence: the days in
 * `[max(month start, historyStart), min(month end, today - 1)]` missing from
 * [captured]. A month is **untrusted** exactly when this is > 0 (#81, Capture
 * evidence; #75).
 *
 * The one rule Charts, `Months` and the Ledger grey on, so the three cannot
 * disagree about whether a month is whole.
 *
 * - **Today is never counted.** Its evidence may not have landed yet -- the
 *   first foreground of a day writes today's `capture_day` row launched, not
 *   awaited (#82), so a read on resume can run first -- and counting it greyed
 *   a correct month total every morning (#64).
 * - **Nothing before [historyStart]**, and nothing at all without one: a day
 *   before the record began is outside it, not a not-captured day (#65).
 *
 * `today` is an argument rather than a clock read, so the rule is a pure
 * function and `NotCapturedDaysTest` pins it on the JVM.
 */
fun notCapturedDays(
    month: YearMonth,
    historyStart: java.time.LocalDate?,
    today: java.time.LocalDate,
    captured: Set<java.time.LocalDate>,
): Int {
    if (historyStart == null) return 0
    val first = maxOf(month.atDay(1), historyStart)
    val last = minOf(month.atEndOfMonth(), today.minusDays(1))
    var missing = 0
    var day = first
    while (day <= last) {
        if (notCaptured(day, historyStart, today, captured)) missing++
        day = day.plusDays(1)
    }
    return missing
}

/**
 * Whether [day] is one of the days [notCapturedDays] counts: past, on or
 * after [historyStart], and missing from [captured]. The `Day` screen's
 * "Pinged was not watching" line (#69), on the rule the month greys by, so a
 * day can never be not captured on one screen and captured on another.
 */
fun notCaptured(
    day: java.time.LocalDate,
    historyStart: java.time.LocalDate?,
    today: java.time.LocalDate,
    captured: Set<java.time.LocalDate>,
): Boolean = historyStart != null && day >= historyStart && day < today && day !in captured

/**
 * The reads behind [notCapturedDays]: which days hold evidence, and where the
 * record begins. Neither is stored anywhere; both are derived from rows a
 * restore carries and Delete everything removes (#65).
 *
 * Blocking, like the DAOs it calls, so a caller runs it under a `Databases`
 * lease (`CaptureStorage.guarded` from a screen).
 */
object CaptureEvidence {

    /**
     * The **history start**: the earlier of the earliest `capture_day` date
     * and the local date of the earliest `raw_capture.posted_at`, or null when
     * neither table has a row (#65).
     *
     * Both, because either alone fails a real case. `capture_day` alone is
     * empty after a salvage restore that could not read that table;
     * `raw_capture` alone misses the days between the first bind and the first
     * allow-listed notification. A hand-entered transaction has no capture and
     * so never moves it.
     *
     * `posted_at` is converted in [zone] as `Txn.localDate` is at parse time
     * (spec 15.7). The `capture_day` date is already a local date, written in
     * the zone current when it was recorded, and is taken as it stands.
     */
    fun historyStart(
        captureDays: CaptureDayDao,
        captures: RawCaptureDao,
        zone: ZoneId = ZoneId.systemDefault(),
    ): java.time.LocalDate? {
        val firstDay = captureDays.earliestDate()?.let(LocalDates::calendarDay)
        val firstCapture = captures.earliestPostedAt()
            ?.let { LocalDates.calendarDay(LocalDates.of(it, zone)) }
        return listOfNotNull(firstDay, firstCapture).minOrNull()
    }

    /** [CaptureDayDao.capturedDates] over `[from, to]`, as calendar days. */
    fun capturedDates(
        captureDays: CaptureDayDao,
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): Set<java.time.LocalDate> =
        captureDays.capturedDates(LocalDates.of(from), LocalDates.of(to))
            .mapTo(HashSet(), LocalDates::calendarDay)
}
