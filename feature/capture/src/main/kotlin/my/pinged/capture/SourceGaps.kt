package my.pinged.capture

import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.entity.CaptureSource

/**
 * Spec 10.3's check: "no Touch 'n Go notifications in 14 days, and there were
 * 40 last month". The listener is alive, other apps are notifying, and one
 * bank's channel has been muted -- the failure a global heartbeat cannot see.
 *
 * [SourceLiveness] writes `last_notification_at`; this reads it beside
 * `expected_monthly_count` and decides.
 */
object SourceGaps {
    internal const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /** Spec 10.3's fourteen days. */
    const val QUIET_AFTER_MILLIS = 14 * DAY_MILLIS

    /**
     * Below this many a month, fourteen days of silence is not news.
     *
     * Treating a source's notifications as Poisson, the chance of fourteen
     * silent days at `r` a month is `exp(-r * 14 / 30)`: 3.8% at 7, 2.4% at 8.
     * At 8 a source that is fine is called quiet about one fortnight in forty;
     * below that, two weeks of silence is too ordinary to report.
     */
    const val MIN_EXPECTED_MONTHLY = 8

    /** One quiet source, in the words the check is phrased in. */
    data class Quiet(val pkg: String, val label: String, val days: Int, val expected: Int)

    /**
     * [source], if it is enabled and has gone quiet against its own habit.
     *
     * A disabled source is not watched: it stores nothing, so its silence costs
     * the ledger nothing. A clock behind [CaptureSource.lastNotificationAt] is
     * not a gap.
     */
    fun quiet(source: CaptureSource, now: Long): Quiet? {
        if (!source.enabled) return null
        val last = source.lastNotificationAt ?: return null
        val expected = source.expectedMonthlyCount ?: return null
        val silentFor = now - last
        if (silentFor < QUIET_AFTER_MILLIS || expected < MIN_EXPECTED_MONTHLY) return null
        return Quiet(source.pkg, source.label, (silentFor / DAY_MILLIS).toInt(), expected)
    }

    /** The window `expected_monthly_count` counts over. */
    const val WINDOW_MILLIS = 30 * DAY_MILLIS

    /**
     * Writes `expected_monthly_count` for every enabled source that is still
     * speaking: its captures in the thirty days up to its last notification.
     *
     * **Ending at the last notification, not at [now], and frozen once quiet.**
     * A window ending now shrinks through the silence it is meant to measure,
     * so a source that sent 40 a month would read 22 by day fourteen and its
     * own gap would talk the check out of firing. A quiet source keeps the
     * expectation it went quiet with until it speaks again; one quiet before
     * it was ever measured -- an upgrade, or a daily check that never ran --
     * is measured once, up to where it went quiet.
     *
     * Captures, not notifications: `raw_capture` is the only per-source record
     * of volume there is, and promotional notifications that yield no fields
     * are not stored. So this undercounts a chatty bank, the safe direction.
     *
     * Targeted `UPDATE`s, and only where the value moved, since
     * `capture_source` is observed by the allow-list screen.
     *
     * One scan of `raw_capture` per enabled source still speaking
     * (`RawCaptureDao.countPostedBetween`: 60ms at 50,000 captures), so the
     * caller must be off the main thread, as both are.
     */
    fun refresh(sources: CaptureSourceDao, captures: RawCaptureDao, now: Long) {
        for (source in sources.enabled()) {
            val last = source.lastNotificationAt ?: continue
            if (now - last >= QUIET_AFTER_MILLIS && source.expectedMonthlyCount != null) continue
            val count = captures.countPostedBetween(source.pkg, last - WINDOW_MILLIS, last)
            if (count != source.expectedMonthlyCount) sources.setExpectedMonthlyCount(source.pkg, count)
        }
    }
}
