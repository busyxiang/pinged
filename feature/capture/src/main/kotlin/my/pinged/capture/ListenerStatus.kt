package my.pinged.capture

import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * What the app can honestly say about capture right now.
 *
 * The grant and the binding are independent (spec 10.1), and one boolean could
 * not express the OEM-kill signature -- access granted, nothing bound, nothing
 * arriving -- which is the most common way this app dies. [looksDead] is the
 * name of that combination.
 *
 * There is deliberately no "bound" field: Android exposes no public way to ask
 * whether a `NotificationListenerService` is bound, and the only honest proxy
 * is [lastNotificationAt].
 */
data class CaptureReport(
    /** `isNotificationListenerAccessGranted` for the frozen component. */
    val granted: Boolean,
    /**
     * The throttled heartbeat of spec 10.2: any notification from any app, so
     * liveness is observable whether or not the user spent money. `0` means
     * nothing has ever been seen, which is not the same as "seen at the epoch".
     */
    val lastNotificationAt: Long,
    /** `Long.MAX_VALUE` when nothing has ever been seen. */
    val staleForMillis: Long,
    /**
     * The database cannot be opened because its Keystore-wrapped key is gone (spec
     * 11.1's D2D-transfer and invalidated-key states).
     *
     * Without this field the report cannot express the one state where capture is
     * dead and every other signal says it is fine: the heartbeat is written before
     * the first database call, so `lastNotificationAt` stays fresh and [looksDead]
     * stays false while nothing is being stored.
     */
    val storageUnavailable: Boolean = false,
) {
    /**
     * Grant present, nothing arriving. This is the shape of an OEM process
     * kill and of spec 10.5's force-stop state.
     *
     * It is a *suspicion*, not a diagnosis. A phone genuinely posting no
     * notifications for a day looks identical from in here, and there is no
     * API that tells the two apart.
     */
    val looksDead: Boolean get() = granted && staleForMillis > ListenerStatus.STALE_AFTER_MILLIS

    /**
     * Nothing further is automatic; the user has to be told (spec 10.5).
     *
     * Both spec 10.5 states -- force stop, where manifest broadcasts including
     * `MY_PACKAGE_REPLACED` and `BOOT_COMPLETED` stop being delivered until the
     * user launches the app, and a grant never given or revoked -- are outside
     * anything this process can repair, and all three rebind paths have already
     * been tried by the time this is true.
     */
    val needsUserAction: Boolean get() = !granted || looksDead || storageUnavailable
}

/**
 * Grant detection and the two rebind calls.
 *
 * [PingedComponents] is not declared here. It exists once, at
 * `PingedComponents.kt`, because the notification-access grant is stored
 * against the listener's flattened `ComponentName` and a second copy of that
 * string is a second thing to forget to change.
 *
 * **The `setComponentEnabledSetting` fallback of spec 10.1 is deliberately
 * absent, and that is a decision rather than an omission.** The toggle is two
 * writes, `DISABLED` then `ENABLED`, and a process killed between them -- on
 * exactly the OEM ROMs that section exists for -- leaves the listener component
 * disabled, which removes it from the notification-access screen altogether.
 * That is a third state with no automatic recovery, invented to paper over the
 * two in spec 10.5, and worse than either because the user cannot see the
 * toggle they would need to flip. Adding it needs a durable marker written
 * before the first write and cleared after the second, so that a later start
 * can finish the job; until that exists, it is not safe to ship.
 */
object ListenerStatus {
    private const val TAG = "PingedRebind"

    /**
     * Spec 10.2's threshold for the capture-stopped banner: a day with nothing
     * at all from any app on the device.
     */
    const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * The grant, which is not the binding.
     *
     * Worth asking even though it answers the less interesting half, because
     * it is the only thing that detects a voided `ComponentName` -- a rename or
     * a move of the listener class leaves the old grant pointing at a component
     * that no longer exists, with no callback and nothing visible to the user
     * (spec 10.1).
     *
     * API 27 -- `isNotificationListenerAccessGranted` arrived in 8.1, which is
     * this project's `minSdk`, so there is no version guard.
     */
    fun isGranted(context: Context): Boolean =
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(PingedComponents.listener(context))
        }.getOrElse {
            // A missing NotificationManager means a context that cannot answer
            // the question, not an answer of "no". Reporting false is still the
            // safe direction: it raises the banner rather than claiming health.
            Log.w(TAG, "Could not read the notification-access grant", it)
            false
        }

    /**
     * Ask the system to bind the listener again.
     *
     * Swallowing the failure is deliberate and is not the same as ignoring it.
     * `requestRebind` throws when the grant is absent, and every caller here is
     * a broadcast receiver or an app-foreground hook where throwing would take
     * the process with it. There is also no return value and no callback: the
     * system either rebinds or it does not, and the only observable
     * consequence is whether notifications resume. That is what
     * [CaptureReport] measures.
     */
    fun requestRebind(context: Context) {
        runCatching {
            NotificationListenerService.requestRebind(PingedComponents.listener(context))
        }.onFailure { Log.w(TAG, "requestRebind did not take", it) }
    }

    /**
     * The third rebind path (spec 10.1): every app foreground, while the grant
     * is present.
     *
     * The receiver covers reinstall and reboot and `onListenerDisconnected`
     * covers a process that survived its unbinding, which leaves the case that
     * matters most in practice -- `requestRebind` was called and simply did not
     * take. Nothing reports that, so the only remedy is to ask again at the one
     * moment the user is present to notice if it still has not worked.
     *
     * Stage two is scheduled alongside, because a reinstall or a kill can leave
     * rows sitting at `NEW` that no later notification would schedule a run
     * for.
     */
    fun onAppForeground(context: Context) {
        if (!isGranted(context)) return
        requestRebind(context)
        ParseWorker.enqueue(context)
    }

    /**
     * A snapshot for the capture-stopped banner (spec 9, `Stopped`).
     *
     * `suspend` because the heartbeat lives in `DataStore`, which is where spec
     * 10.2 puts it and not in Room: the value changes 100-300 times a day and
     * Room's invalidation tracker is table-granular.
     */
    suspend fun report(context: Context, now: Long = System.currentTimeMillis()): CaptureReport {
        val last = CaptureHealth.lastSeenAt(context)
        return CaptureReport(
            granted = isGranted(context),
            lastNotificationAt = last,
            storageUnavailable = CaptureHealth.storageUnavailable(context),
            // Never seen is not "seen at the epoch". Reporting `now` minus zero
            // would be a stale-by-56-years answer that happens to be right by
            // accident; MAX_VALUE says the thing that is actually known.
            staleForMillis = if (last == 0L) Long.MAX_VALUE else now - last,
        )
    }
}
