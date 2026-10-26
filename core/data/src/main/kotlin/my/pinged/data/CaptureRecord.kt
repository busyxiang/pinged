package my.pinged.data

import my.pinged.data.entity.ParseStatus
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Two weeks of capture (#66): the collecting gate. */
const val GATE_DAYS = 14

/**
 * The **collecting** count (#81, Collecting; #66): captured days from the
 * history start to yesterday, across all history and whatever month a screen
 * shows. A captured day, not a calendar day, so a listener dead for ten days
 * does not open the gate on four days of evidence.
 *
 * Nothing is stored. [days] can only grow, since capture evidence is deleted
 * only by Delete everything, which also resets the history start; so once
 * [open], it stays open with no flag to keep.
 */
data class Collecting(val days: Int) {
    /** Fourteen captured days: the bars and the ranking draw. */
    val open: Boolean get() = days >= GATE_DAYS
}

/**
 * What the record says of one day (CONTEXT.md, Capture record). Exactly one
 * of four, so a screen cannot be told a day is neither captured nor not
 * captured and then guess which.
 */
enum class DayStatus {
    /** A past day on or after the history start with capture evidence. */
    CAPTURED,

    /** A past day on or after the history start with none: nothing is known of its spending. */
    NOT_CAPTURED,

    /** Before the history start, or there is none: not missing from the record, never in it. */
    OUTSIDE_RECORD,

    /** Today or later. Its evidence may not have landed yet, so it is neither captured nor not (#64). */
    NOT_ENDED,
}

/**
 * What the record says of one month.
 *
 * @property notCaptured the month's not-captured days. A month is
 *   **untrusted** exactly when this is above zero; there is no threshold (#73).
 * @property comparable the month has ended, lies wholly on or after the
 *   history start, and is not untrusted (CONTEXT.md, Comparable month).
 */
data class MonthRecord(val notCaptured: Int, val comparable: Boolean) {
    /** At least one not-captured day, so every figure drawn from the month greys. */
    val untrusted: Boolean get() = notCaptured > 0
}

/**
 * The **capture record** (CONTEXT.md; #81, Capture evidence; #305): where
 * Pinged's history starts, and which days since then it captured. The one
 * place Charts, `Months`, the Ledger's summary and the Day header get "not
 * captured", "untrusted", "comparable" and "collecting" from, so they cannot
 * disagree about a day or a month.
 *
 * **Capture evidence** is a day with `capture_day.listener_bound`, or a kept
 * notification, whatever became of it: a transaction, an unread capture, or
 * one that is never a transaction. A manual entry never is.
 *
 * - **A transaction keeps its `local_date`**, fixed at parse (spec 15.7). A
 *  kept notification with no transaction adds its `posted_at` in the zone
 *   current *now*, as the history start does. After a zone change a
 *   notification near midnight may therefore vouch for a neighbouring day;
 *   extra evidence can only remove a not-captured day, never add one.
 * - **Every notification writes a bound `capture_day` row** before the
 *   allow-list gate (`CaptureIngest`), so the two sources differ only once
 *   `capture_day` is lost: a salvage restore that could not read it. Then a
 *   day whose only capture was unread or rejected is still captured, and can
 *   still be the history start.
 * - **Today is never captured or not captured**: the first foreground of a day
 *   writes today's `capture_day` row launched, not awaited (#82), so a read on
 *   resume can run first, and counting it greyed a correct month every
 *   morning (#64). Nothing before the history start is either (#65).
 *
 * **One snapshot.** [read] takes the read's own [PingedDatabase], so it runs
 * in the transaction that read opened and never touches `Databases`; it
 * cannot see a second snapshot. A question about a day or month outside the
 * span it read **throws** rather than answering "not captured".
 *
 * `today` is an argument rather than a clock read, so the rules are pure and
 * `CaptureRecordTest` pins them on the JVM through [of].
 */
class CaptureRecord private constructor(
    /** The first day with capture evidence, or null: there is then no record (#65). */
    val historyStart: LocalDate?,
    /** The day the read was taken, so a figure and its grey cannot straddle midnight. */
    val today: LocalDate,
    private val span: ClosedRange<LocalDate>,
    private val captured: Set<LocalDate>,
    private val collectingDays: Int?,
) {
    /**
     * The gate the category bars and the merchant ranking sit behind (#66).
     * Throws [IllegalStateException] on a record read without it: the count
     * needs every captured day from the history start, which a one-month read
     * does not fetch.
     */
    val collecting: Collecting
        get() = Collecting(checkNotNull(collectingDays) { "this record was read without collecting" })

    /**
     * [day]'s status. Throws [IllegalArgumentException] when [day] is outside
     * the span this record was read for.
     */
    fun day(day: LocalDate): DayStatus {
        require(day in span) { "$day is outside the span $span this record was read for" }
        val start = historyStart
        return when {
            start == null || day < start -> DayStatus.OUTSIDE_RECORD
            day >= today -> DayStatus.NOT_ENDED
            day in captured -> DayStatus.CAPTURED
            else -> DayStatus.NOT_CAPTURED
        }
    }

    /**
     * [month]'s record: the days in `[max(month start, history start),
     * min(month end, today - 1)]` that are not captured. Throws
     * [IllegalArgumentException] when any of [month] is outside the span.
     */
    fun month(month: YearMonth): MonthRecord {
        require(month.atDay(1) in span && month.atEndOfMonth() in span) {
            "$month is not within the span $span this record was read for"
        }
        val start = historyStart ?: return MonthRecord(notCaptured = 0, comparable = false)
        val first = maxOf(month.atDay(1), start)
        val last = minOf(month.atEndOfMonth(), today.minusDays(1))
        var missing = 0
        var day = first
        while (day <= last) {
            if (day !in captured) missing++
            day = day.plusDays(1)
        }
        val comparable = month < YearMonth.from(today) && month.atDay(1) >= start && missing == 0
        return MonthRecord(missing, comparable)
    }

    companion object {
        /**
         * Every day a record can be asked about: for a read whose span the
         * caller does not know, the `Months` sheet's, which lists a month of
         * hand-entered money from any year.
         */
        val EVERY_DAY: ClosedRange<LocalDate> = LocalDate.MIN..LocalDate.MAX

        /**
         * A record of what is already known, for a test to hand-build one.
         * [captured] is every captured day, and may hold days the record would
         * not count (before [historyStart], or today and after).
         */
        fun of(
            historyStart: LocalDate?,
            today: LocalDate,
            span: ClosedRange<LocalDate>,
            captured: Set<LocalDate>,
        ): CaptureRecord = CaptureRecord(
            historyStart, today, span, captured,
            collectingDays = historyStart?.let { start -> captured.count { it >= start && it < today } } ?: 0,
        )

        /**
         * The record as of [today], read through [db], which a read passes so
         * the evidence is of its snapshot. Blocking, like the DAOs it calls.
         *
         * [span] is the days the caller will ask about; asking about one
         * outside it throws. The evidence is read only over the span, up to
         * yesterday, unless [collecting]: that count is every captured day from
         * the history start, and asking for it reads them all.
         *
         * **[collecting] is an option because its cost grows with the ledger.**
         * Measured on emulator-5554 over two years, 730 `capture_day` rows and
         * 20,000 captures (`CaptureRecordTimingTest`), the whole history to
         * yesterday took 8.8ms median (10.0ms longest of 40) with 5,000
         * captures `MATCHED` and 15,000 kept without a transaction, and 19.9ms
         * (21.9ms) with 15,000 `MATCHED`: the `capture_day` and `txn` half is
         * 14.2ms at 15,000 captured transactions, against 1.0ms at 5,000, and
         * the kept notifications 6.3ms at 15,000 rows, against 1.3ms at 5,000.
         * The bound was about 2ms, so it is not free. A month reads 1.3ms and
         * 1.1ms in the two ledgers, and a day 1.2ms and 1.0ms, most of it the
         * history start's two statements.
         */
        fun read(
            db: PingedDatabase,
            today: LocalDate,
            span: ClosedRange<LocalDate>,
            collecting: Boolean = false,
            zone: ZoneId = ZoneId.systemDefault(),
        ): CaptureRecord {
            val historyStart = historyStart(db, zone)
            val first = historyStart?.let { if (collecting) it else maxOf(it, span.start) }
            val last = today.minusDays(1).let { if (collecting) it else minOf(it, span.endInclusive) }
            val captured = if (first != null && first <= last) evidence(db, first, last, zone) else emptySet()
            return CaptureRecord(historyStart, today, span, captured, collectingDays = if (collecting) captured.size else null)
        }

        /**
         * The **history start**: the earlier of the earliest `capture_day`
         * date and the local date of the earliest `raw_capture.posted_at`, or
         * null when neither table has a row (#65).
         *
         * Both, because either alone fails a real case. `capture_day` alone is
         * empty after a salvage restore that could not read that table;
         * `raw_capture` alone misses the days between the first bind and the
         * first allow-listed notification. A hand-entered transaction has no
         * capture and so never moves it.
         *
         * `posted_at` is converted in [zone] as `Txn.localDate` is at parse
         * time (spec 15.7). The `capture_day` date is already a local date,
         * written in the zone current when it was recorded, and is taken as it
         * stands.
         */
        private fun historyStart(db: PingedDatabase, zone: ZoneId): LocalDate? {
            val firstDay = db.captureDayDao().earliestDate()?.let(LocalDates::calendarDay)
            val firstCapture = db.rawCaptureDao().earliestPostedAt()
                ?.let { LocalDates.calendarDay(LocalDates.of(it, zone)) }
            return listOfNotNull(firstDay, firstCapture).minOrNull()
        }

        /**
         * The days in `[from, to]` with capture evidence: `capture_day` and
         * capture-backed `txn` by `local_date`, plus every kept notification
         * with no transaction by its `posted_at` in [zone].
         *
         * The last is a range on `(parse_status, posted_at)`, once per status
         * that leaves no transaction, so `MATCHED`'s rows, the majority, are
         * never touched. A day is `[start of day, start of next day)` in
         * [zone], so DST needs no special case.
         */
        private fun evidence(db: PingedDatabase, from: LocalDate, to: LocalDate, zone: ZoneId): Set<LocalDate> {
            val days = HashSet<LocalDate>()
            db.captureDayDao().evidenceDates(LocalDates.of(from), LocalDates.of(to))
                .mapTo(days, LocalDates::calendarDay)
            // One start-of-day instant per day of the range, found by [zone] once,
            // so each of the thousands of rows is a binary search and not a
            // conversion through `ZonedDateTime`.
            val count = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
            val starts = LongArray(count + 1) { from.plusDays(it.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() }
            val seen = BooleanArray(count)
            for (postedAt in db.rawCaptureDao().postedAtWithoutTxn(WITHOUT_TXN, starts[0], starts[count])) {
                val at = java.util.Arrays.binarySearch(starts, postedAt)
                seen[if (at >= 0) at else -at - 2] = true
            }
            seen.forEachIndexed { i, hit -> if (hit) days += from.plusDays(i.toLong()) }
            return days
        }

        /**
         * Every status but `MATCHED`: the captures with no `txn` of their own.
         * `DUPLICATE_OF` and `UPDATE_OF` are folded into another capture's
         * transaction, so they have none either, and are notifications Pinged
         * kept all the same. Derived, so a ninth status is evidence until
         * someone says it is not.
         */
        internal val WITHOUT_TXN: List<ParseStatus> = ParseStatus.entries - ParseStatus.MATCHED
    }
}
