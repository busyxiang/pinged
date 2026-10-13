package my.pinged.ledger.entry

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import my.pinged.ui.dayMonth
import my.pinged.ui.dayMonthYear

/**
 * The date chip's rules (#103, spec 9.4): an entry is dated now unless a past
 * day is chosen, never a future day, and a backdated entry keeps the time of
 * day it was entered, so it sorts sensibly among that day's rows.
 *
 * Days before the history start are allowed: cash spent before Pinged was
 * installed is still spending, and a manual entry is never evidence of
 * capture, so it cannot move that start.
 */
internal object EntryDate {

    /**
     * The entry's `occurred_at`: [now] when [day] is null (`Today`), else
     * [day] at [now]'s time of day in [zone]. `local_date` is not computed
     * here; the `Txn` constructor derives it from this, as for every writer.
     */
    fun occurredAt(day: LocalDate?, now: Long, zone: ZoneId): Long {
        if (day == null) return now
        val time = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
        return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** The chip's text: `Today`, `30 August`, or `30 August 2025` outside [today]'s year. */
    fun chip(day: LocalDate?, today: LocalDate): String = when {
        day == null || day == today -> TODAY
        day.year == today.year -> dayMonth(day)
        else -> dayMonthYear(day)
    }
}

/**
 * This draft dated [picked] on the chip, given [today]: today itself is
 * `Today` (a null day, dated at Save), a past day is kept, and a future day
 * is refused, leaving the draft as it was.
 */
fun ManualDraft.dated(picked: LocalDate, today: LocalDate): ManualDraft = when {
    picked.isAfter(today) -> this
    picked == today -> copy(day = null)
    else -> copy(day = picked)
}

/** The date chip's text for an entry dated now. */
const val TODAY = "Today"
