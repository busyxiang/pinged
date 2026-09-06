package my.pinged.capture

import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.entity.CaptureSource

/**
 * Spec 10.3's per-source liveness, which catches the likelier failure.
 *
 * A global heartbeat catches a dead listener. It cannot catch the case that
 * actually happens: the listener is alive and one bank's transaction channel
 * has been muted -- by the user, an app update, or the OEM's notification
 * manager -- so capture health reports green while the ledger quietly loses one
 * source. `last_notification_at` is what lets the check be "no Touch 'n Go
 * notifications in 14 days, and there were 40 last month".
 *
 * Spec 10.3 says "the data is already being written"; until this object it was
 * not, and `CaptureSourceDao.setLastNotificationAt` had no caller anywhere.
 *
 * ### Two rules, and both matter
 *
 * **Throttled, like the global heartbeat.** A phone posts 100-300 notifications
 * a day and Room's invalidation tracker is table-granular -- the entire reason
 * spec 10.2 keeps the global heartbeat out of Room. `capture_source` is observed
 * by the allow-list screen, so an unthrottled write here would re-emit it.
 */
internal object SourceLiveness {

    /**
     * The same five minutes as [CaptureHealth.THROTTLE_MILLIS], deliberately.
     * The two are answering the same question at different granularities and a
     * per-source window narrower than the global one would be an odd claim to
     * defend; referencing the constant rather than repeating the number keeps
     * them from drifting.
     */
    internal const val THROTTLE_MILLIS = CaptureHealth.THROTTLE_MILLIS

    /**
     * Records that [source] spoke at [at], unless it spoke recently enough that
     * the write buys nothing.
     *
     * @param at the notification's own post time, not the moment it was
     *   ingested. The distinction is not pedantry: [PingedNotificationListener]
     *   re-reads still-live notifications on every connect, and rebinding is
     *   routine, so ingest time would stamp a source as alive *now* on the
     *   strength of a banner it posted three days ago -- which is precisely the
     *   silence spec 10.3 exists to notice.
     * @return true if the row was written. Only the tests care; the call site
     *   has nothing to do with the answer.
     */
    fun record(dao: CaptureSourceDao, source: CaptureSource, at: Long): Boolean {
        val stored = source.lastNotificationAt ?: 0L
        // One comparison, two jobs. Forwards it is the throttle. Backwards it
        // stops a catch-up delivery of an older notification, or a clock the
        // system has just corrected, from moving liveness into the past and
        // manufacturing a gap that never happened.
        if (at - stored < THROTTLE_MILLIS) return false
        dao.setLastNotificationAt(source.pkg, at)
        return true
    }
}
