package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import my.pinged.data.entity.Arrival

/**
 * Spec 9.6: for a package the user has not enabled, the app records the package
 * identifier and a seen count. No title, no text, no extras.
 *
 * **A count only, never a per-package last-seen timestamp.** A durable record
 * of which apps the user has and when each one speaks is, for some apps, more
 * sensitive per byte than the ledger itself.
 *
 * **The number means notifications, not deliveries.** The listener is handed
 * the same still-posted notification again on every rebind, and rebinding is
 * routine (spec 10.1), so a counter that moved on every call inflated on its
 * own -- one package walked from 3 to 17 across a morning of app relaunches
 * with nothing new having arrived. A number printed beside "NOT ONE WORD
 * STORED" cannot climb for reasons the user cannot see. [countOne] is the gate.
 *
 * **And it counts for enabled sources too.** Incrementing only on the discarded
 * path left a bank the user had enabled reading `0 SEEN` forever while it was
 * demonstrably capturing. For a package whose every notification is already in
 * `raw_capture` in full, a count is strictly less than what is already kept.
 */
object SourceCounters {
    private const val SEEN_PREFIX = "seen:"

    /**
     * The post time of the newest notification any package has been counted for.
     * One value, device-wide, overwritten in place.
     *
     * **This is the dedupe state, and where it lives is the decision.** A disabled
     * package writes no `raw_capture` row at all, so `sbn_key` in Room cannot answer
     * "have I already counted this one" for the packages that need it most. The
     * alternatives were worse:
     *
     * - Remembering counted `sbn.key`s in memory dies with the process, and the
     *   OEMs this app is built for kill it constantly.
     * - Remembering them durably means storing another app's notification slot
     *   identifiers -- and a slot identifier carries the notification *tag*, an
     *   app-chosen string that can be content.
     * - A per-package high-water mark is `capture_source.last_notification_at`
     *   under another name, which spec 9.6 forbids in as many words.
     *
     * A single unattributed post time is none of those: it cannot say which app
     * spoke, only that something did, which [CaptureHealth]'s
     * `last_any_notification_at` already records for spec 10.2's heartbeat.
     *
     * **What it costs.** A notification posted in the same millisecond as the mark
     * and only ever seen through catch-up is not counted, and after a backwards
     * clock change catch-up counts nothing until real time passes the mark again.
     * Both are undercounts, which is the safe direction: a count that lags is a
     * floor under "N SEEN", where one that climbs on its own makes the privacy
     * claim beside it unbelievable.
     */
    private val NEWEST_COUNTED = longPreferencesKey("newest_counted_post_time")

    private fun key(pkg: String) = intPreferencesKey("$SEEN_PREFIX$pkg")

    private const val FIRST_PREFIX = "first:"

    /**
     * When [pkg] was first counted: spec 9.6's `first_seen_at`, kept here because a
     * package nobody has touched has no `capture_source` row to hold it.
     *
     * Written once and never moved, which is what separates it from the last-seen
     * timestamp spec 9.6 forbids: one datum per package says the app is installed,
     * not when it speaks.
     */
    private fun firstSeen(pkg: String) = longPreferencesKey("$FIRST_PREFIX$pkg")

    /**
     * Android's own packages, which never post a payment and so are never offered
     * as a source (#218). Not counted, and not listed if an older build counted one.
     *
     * Named rather than `ApplicationInfo.FLAG_SYSTEM`: an OEM image can preinstall
     * a bank or e-wallet as a system app, and the flag would hide it.
     */
    private val NEVER_A_SOURCE = setOf("android", "com.android.systemui")

    /** Spec 9.6: how long an untouched package stays in the discovery list. */
    const val DROP_AFTER_MILLIS = 30L * 24 * 60 * 60 * 1000

    /**
     * Counts one notification from [pkg], unless it is a re-delivery of one already
     * counted.
     *
     * [Arrival.POSTED] always counts: `onNotificationPosted` fires once per post.
     * [Arrival.CATCHUP] counts only what is newer than [NEWEST_COUNTED], because
     * `getActiveNotifications()` returns what is *still posted* -- the same
     * notification comes back on every rebind for as long as it sits in the shade.
     * Anything genuinely new is strictly newer than the last thing counted, since
     * everything so far was counted while bound and in real time.
     *
     * One [edit] for both writes: the read of the mark and the increment that
     * depends on it have to be one atomic step, and it is one file rewrite rather
     * than two on a path that runs for every notification on the device.
     *
     * The caller must present a catch-up batch in ascending [postTime] order, or
     * the newest notification in it will advance the mark past the rest.
     * [CaptureIngest.catchUpOrder] is that ordering.
     *
     * [now] and not [postTime] stamps first-seen: a catch-up can hand over a post
     * from days ago, and dating the discovery by it would drop the package early.
     *
     * @return true if the count moved.
     */
    suspend fun countOne(
        context: Context,
        pkg: String,
        arrival: Arrival,
        postTime: Long,
        now: Long,
    ): Boolean {
        if (pkg in NEVER_A_SOURCE) return false
        var counted = false
        context.captureStore.edit { prefs ->
            val newest = prefs[NEWEST_COUNTED]
            // Stated once: the catch-up gate and the high-water mark have to
            // agree about what "newer" means, and they were two copies of the
            // same expression two lines apart.
            val isNewer = newest == null || postTime > newest
            counted = arrival == Arrival.POSTED || isNewer
            if (counted) {
                prefs[key(pkg)] = (prefs[key(pkg)] ?: 0) + 1
                if (prefs[firstSeen(pkg)] == null) prefs[firstSeen(pkg)] = now
            }
            if (isNewer) prefs[NEWEST_COUNTED] = postTime
        }
        return counted
    }

    /**
     * Spec 9.6's drop: forget every package first seen more than
     * [DROP_AFTER_MILLIS] before [now], unless it is in [keep] -- the caller's
     * packages the user has touched, which this store cannot see.
     *
     * A count from before first-seen was recorded has no age. It is stamped [now]
     * rather than dropped, so an upgrade gives it the full 30 days instead of
     * emptying the list at once.
     */
    suspend fun dropExpired(context: Context, now: Long, keep: Set<String>) {
        context.captureStore.edit { prefs ->
            val counted = prefs.asMap().keys
                .map { it.name }
                .filter { it.startsWith(SEEN_PREFIX) }
                .map { it.removePrefix(SEEN_PREFIX) }
            for (pkg in counted) {
                if (pkg in keep) continue
                val first = prefs[firstSeen(pkg)]
                when {
                    first == null -> prefs[firstSeen(pkg)] = now
                    now - first > DROP_AFTER_MILLIS -> {
                        prefs.remove(key(pkg))
                        prefs.remove(firstSeen(pkg))
                    }
                }
            }
        }
    }

    suspend fun seenCount(context: Context, pkg: String): Int =
        context.captureStore.data.first()[key(pkg)] ?: 0

    /**
     * Tests only, and read-only: the mark [countOne] compares against.
     *
     * The instrumented tests share one process and one DataStore with every
     * other capture test, so a test about the catch-up gate has to choose a
     * post time relative to whatever the mark already is rather than assume it
     * starts empty.
     */
    internal suspend fun newestCountedPostTime(context: Context): Long =
        context.captureStore.data.first()[NEWEST_COUNTED] ?: 0L

    /**
     * Every package this device has been heard from, with its count.
     *
     * The allow-list screen (spec 9.6) needs the *set* of discovered packages, and
     * nothing else can produce it: a disabled package is never written to
     * `capture_source`, so Room does not know it exists.
     *
     * The prefix filter is not decoration. This DataStore is shared with
     * [CaptureHealth], whose keys sit alongside these -- as does [NEWEST_COUNTED] --
     * and a screen listing every preference in the file would show the heartbeat as
     * a package.
     */
    suspend fun allSeenCounts(context: Context): Map<String, Int> =
        context.captureStore.data.first().asMap()
            .asSequence()
            .filter { it.key.name.startsWith(SEEN_PREFIX) }
            .mapNotNull { entry ->
                val count = entry.value as? Int ?: return@mapNotNull null
                entry.key.name.removePrefix(SEEN_PREFIX) to count
            }
            .filter { it.first !in NEVER_A_SOURCE }
            .toMap()
}
