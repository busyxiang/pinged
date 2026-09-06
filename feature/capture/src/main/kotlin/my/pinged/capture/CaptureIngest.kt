package my.pinged.capture

import my.pinged.data.CaptureDays
import my.pinged.data.Databases
import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import my.pinged.data.LocalDates
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.RawCapture

/**
 * Stage one, and all of it: extract, filter, one insert.
 *
 * The logic lives here rather than in [PingedNotificationListener] because a
 * `NotificationListenerService` the system has not constructed has no
 * `applicationContext`, so nothing about the listener's own instance is
 * testable. Taking the context as a parameter lets the default-deny invariant
 * be tested directly as well as through a real bind.
 *
 * Nothing here parses: no rule matching, no duplicate detection, no confidence
 * gate. The durable table is the boundary, for spec 3's reason -- a SQLCipher
 * insert costs 5-20ms and duplicate detection is a query, and doing that on the
 * listener's callback thread is an ANR risk that grows with the ledger.
 */
internal object CaptureIngest {
    /**
     * The only *mutable* test seam in this module. The app skips its own package
     * (spec 10.2), which is also the only package an instrumented test can post
     * from -- a test APK cannot post as Maybank -- so flipping this lets
     * default-deny be proved through the real system path.
     *
     * A `var` rather than a `BuildConfig` field: a debug-only flag would make the
     * shipped debug build capture its own notifications, so the behaviour under
     * test would not be the behaviour that ships. `false` in every build.
     */
    internal var captureOwnPackage = false

    /**
     * The order a rebind catch-up batch has to be ingested in: oldest post first.
     *
     * `getActiveNotifications()` promises no order. The seen count's catch-up gate
     * is a single high-water post time (see [SourceCounters]), so a batch handed
     * over newest-first would move the mark past everything else in it. Sorting
     * costs one pass over what is in the shade.
     *
     * Nulls are dropped rather than tolerated: the array comes across a binder and
     * is typed as nullable-element on the framework side.
     */
    internal fun catchUpOrder(live: Array<StatusBarNotification>?): List<StatusBarNotification> =
        live?.filterNotNull()?.sortedBy { it.postTime }.orEmpty()

    /**
     * @param arrival which delivery path produced this capture. A parameter
     *   and not something derived here, because both paths funnel through this
     *   function and nothing observable inside distinguishes them. Nothing can
     *   reconstruct it afterwards either, which is why spec 4 records it on the
     *   row -- and spec 7.2's duplicate rule 1 branches on it.
     * @return the id of the row written, or null if nothing was stored.
     */
    suspend fun ingest(
        context: Context,
        sbn: StatusBarNotification,
        arrival: Arrival,
        now: Long,
    ): Long? {
        val notification = sbn.notification ?: return null

        // Our own notifications reach our own listener (spec 10.2).
        if (!captureOwnPackage && sbn.packageName == context.packageName) return null
        // A group summary carries aggregated or system-generated text, which
        // produces either unread noise or a duplicate of its own child
        // (spec 3).
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        // Liveness, for a notification from any app. Neither of these records
        // a package identifier or a byte of text, which is what lets them run
        // ahead of the allow-list gate.
        CaptureHealth.recordSeen(context, now)

        // Everything below this line needs the database. If its key is gone the
        // whole capture path is dead, and until this catch existed it was dead
        // *silently*: the throw unwound into the listener's
        // CoroutineExceptionHandler, one log line per notification, forever,
        // while `recordSeen` above kept the heartbeat fresh so the banner
        // reported healthy capture. Recording it is what lets the app say so.
        val storageReady = CaptureStorage.guarded(
            context,
            what = "Capture cannot reach the database",
            unavailable = { false },
        ) {
            // No explicit `Databases.shared(context)` here: the probe belongs to
            // `CaptureStorage.guarded`, which opens the database before running this block.
            // It lived here because `CaptureDays.markNotificationSeen` skips the database
            // once the day is marked, so a guarded block round it probed nothing and
            // reported storage healthy -- which was true of every other guarded block too.
            CaptureDays.markNotificationSeen(LocalDates.of(now)) { Databases.captureDayDao(context) }
            true
        }
        // Nothing below can run, and there is nowhere to put the capture. The
        // banner now says so rather than this failing silently once per
        // notification for as long as the state lasts.
        if (!storageReady) return null

        // The seen count (spec 9.6), deliberately on both sides of the gate below.
        //
        // A disabled package needs it because a count and an identifier are all stage
        // one may keep. An enabled package needs it because the screen prints the same
        // number for both, and `0 SEEN` beside a source that is demonstrably capturing
        // costs the privacy claim its credibility as surely as a number that climbs on
        // its own. Past the gate it adds nothing: every notification from an enabled
        // source is stored below, in full.
        //
        // `countOne` and not a bare increment, because the listener is handed the same
        // still-posted notification on every rebind.
        SourceCounters.countOne(context, sbn.packageName, arrival, sbn.postTime)

        val sources = Databases.captureSourceDao(context)
        val source = sources.byPackage(sbn.packageName)
        if (source?.enabled != true) {
            // Default-deny, and the whole basis of the privacy posture:
            // metadata only, and the discard happens before anything
            // content-bearing is written (spec 9.6, spec 11.1). A missing row
            // and a row with `enabled = false` are the same answer -- no.
            return null
        }

        // Per-source liveness (spec 10.3), and it must be here rather than a line
        // earlier. A `last_notification_at` for a package the user has not enabled is
        // exactly the durable record spec 9.6 forbids -- when the user talks to whom,
        // sleep and work patterns read off directly -- which is why the discovery path
        // above keeps a count and no timestamp.
        //
        // Ahead of the field extraction, because liveness is about the source having
        // been heard from, not about whether anything in the notification was readable.
        SourceLiveness.record(sources, source, sbn.postTime)

        val fields = NotificationFields.from(sbn) ?: return null

        // The durable boundary. One insert, and then stage one is done: a row
        // still at `parse_status = NEW` is unfinished work, so process death
        // here loses nothing (spec 3).
        return Databases.rawCaptureDao(context).insert(
            RawCapture(
                sourcePackage = fields.sourcePackage,
                postedAt = fields.postedAt,
                whenMillis = fields.whenMillis,
                capturedAt = now,
                sbnKey = fields.sbnKey,
                notifId = fields.notifId,
                notifTag = fields.notifTag,
                userHandle = fields.userHandle,
                channelId = fields.channelId,
                flags = fields.flags,
                // Required: RawCapture declares `arrival` with no default, on
                // purpose, so a new call site cannot silently omit it.
                arrival = arrival,
                title = fields.title,
                text = fields.text,
                bigText = fields.bigText,
                subText = fields.subText,
                extrasJson = null,
                contentHash = ContentHash.of(
                    fields.sourcePackage,
                    fields.userHandle,
                    fields.normalizedForHash,
                ),
            ),
        )
    }
}
