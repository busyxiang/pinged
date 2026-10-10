package my.pinged.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import my.pinged.data.LocalDates
import my.pinged.data.CaptureDays
import my.pinged.data.Databases
import my.pinged.data.entity.Arrival

/**
 * STAGE ONE ONLY. Extract, filter, one insert, then ask WorkManager to run
 * stage two. Everything else -- matching, both duplicate layers, the
 * confidence gate -- is [ParseWorker]'s, reading the table this writes.
 *
 * DO NOT RENAME OR MOVE THIS CLASS. The notification-access grant is stored by
 * the system against its flattened `ComponentName`; renaming voids the grant
 * silently, with no callback and nothing visible to the user -- capture just
 * stops. See [PingedComponents].
 */
class PingedNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        ListenerBinding.connected = true
        scope.launch {
            // The system calls this only when it has bound the service, which
            // makes it an honest witness that capture was alive today. The
            // foreground check is the other, through ListenerBinding -- see
            // CaptureDays.markListenerBound.
            CaptureStorage.guarded(
                applicationContext,
                what = "Cannot record that the listener bound",
                unavailable = {},
            ) {
                CaptureDays.markListenerBound(
                    LocalDates.of(System.currentTimeMillis()),
                ) { Databases.captureDayDao(applicationContext) }
            }
            // Narrows the gap after a kill: bank notifications often sit in the shade for
            // hours (spec 3). This does not close it -- history cannot be recovered beyond
            // what is still posted.
            //
            // `Arrival.CATCHUP` is recorded and no longer changes how a capture is
            // deduplicated; `Duplicates.slotRefreshOutcome` records why the exemption lost
            // money. The column stays because it is a fact about how the row arrived, and
            // nothing can recover it later.
            val live = runCatching { activeNotifications }
                .onFailure { Log.w(TAG, "Could not read active notifications on connect", it) }
                .getOrNull()
            // Oldest post first. This batch is re-delivered in full on every
            // rebind, and the seen count's catch-up gate is a single high-water
            // post time -- so an unordered batch would let its newest member
            // move the mark past the rest. See CaptureIngest.catchUpOrder.
            CaptureIngest.catchUpOrder(live).forEach { ingest(it, Arrival.CATCHUP) }
        }
    }

    override fun onListenerDisconnected() {
        ListenerBinding.connected = false
        // Covers only the case where the process survives; spec 10.1's other
        // two paths are the receiver and the app-foreground call.
        ListenerStatus.requestRebind(applicationContext)
    }

    override fun onDestroy() {
        ListenerBinding.connected = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ingest(sbn, Arrival.POSTED)
    }

    private fun ingest(sbn: StatusBarNotification, arrival: Arrival) {
        scope.launch {
            val written = CaptureIngest.ingest(
                context = applicationContext,
                sbn = sbn,
                arrival = arrival,
                now = System.currentTimeMillis(),
            )
            // Stage two, and the only thing this class knows about it: a row was written,
            // so there is work. Conditional on the insert having happened, so a package the
            // user has not enabled schedules nothing -- 100-300 notifications a day reach
            // this listener and almost none are money.
            //
            // This runs on the capture dispatcher and only schedules. WorkManager decides
            // when the worker starts, which is the point of the durable boundary: the row
            // is already safe, so stage two is free to be late.
            if (written != null) {
                ParseWorker.enqueue(applicationContext)
            }
        }
    }

    private companion object {
        private const val TAG = "PingedListener"

        /**
         * One thread, so inserts arrive in the order the notifications did. A
         * single-thread executor rather than `limitedParallelism`, which is still
         * opt-in experimental.
         *
         * Process-wide rather than per-instance: the system constructs a new service
         * instance on every rebind, so a per-instance executor would either leak a
         * thread per rebind or be shut down in `onDestroy` while it still owed an
         * insert.
         */
        private val dispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "pinged-capture").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        /**
         * The handler is not decoration. An uncaught exception in a coroutine
         * launched without one reaches Android's default handler and kills the
         * process, so a database that fails to open -- spec 11.1's
         * D2D-transfer and invalidated-Keystore-key states -- would turn every
         * notification on the device into a crash. Spec 11.1: it never
         * crash-loops.
         */
        private val errors = CoroutineExceptionHandler { _, thrown ->
            Log.e(TAG, "Stage one failed for one notification", thrown)
        }

        private val scope = CoroutineScope(SupervisorJob() + dispatcher + errors)
    }
}
