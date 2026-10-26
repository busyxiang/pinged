package my.pinged

import android.content.Context
import kotlinx.coroutines.flow.Flow
import my.pinged.capture.CaptureReport
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.transfer.TransferStore

/**
 * Everything [AppStatus] reads that is not the ledger (#304): capture's
 * report and its storage flag, the backup reminder's stores, first run, the
 * ledger being replaced, and the clock. An interface so `AppStatusTest` can
 * script each one, and order them against the probe, without a device.
 *
 * **Handed in rather than provoked on disk**, as `StoredSettings` is, because
 * each DataStore here caches its first successful read: a file corrupted from
 * a test is never read again.
 *
 * Each member says whether it may throw. [AppStatus] guards the ones that do.
 */
internal interface StatusSources {
    /** `ListenerStatus.isGranted`: whether onboarding can be ruled out before the first [report]. Never throws. */
    fun isGranted(): Boolean

    /** `ListenerStatus.report`. Never throws: a store that will not read is `healthUnreadable`. */
    suspend fun report(): CaptureReport

    /** The storage flag each time it changes, without its value on subscription. May fail. */
    fun storageUnavailableChanges(): Flow<Boolean>

    /** When the last export was recorded, 0 for never. Throws `IOException` for a store that will not read. */
    suspend fun lastExportAt(): Long

    /** [lastExportAt] each time it changes, without its value on subscription. May fail. */
    fun lastExportAtChanges(): Flow<Long>

    /** When the backup reminder was last dismissed, 0 for never. Throws `IOException`. */
    suspend fun reminderDismissedAt(): Long

    /** Record a dismissal of the backup reminder at [at]. Throws `IOException`. */
    suspend fun recordReminderDismissed(at: Long)

    /** Whether damage is on record, now and on each change. May fail. */
    fun damagedUpdates(): Flow<Boolean>

    /** [onboards]: whether first run shows onboarding, given the grant. Never throws. */
    suspend fun onboards(granted: Boolean): Boolean

    /** `Databases.rewrites`: a delete or a restore has replaced the ledger. Its current value is not a change. */
    val rewrites: Flow<Long>

    /** The time, in epoch millis. */
    fun now(): Long

    /** Wait [millis] of [now]'s time: what [AppStatus] re-measures the heard line by. */
    suspend fun sleep(millis: Long)

    /** The stores themselves. */
    class OnDisk(private val context: Context) : StatusSources {
        override fun isGranted() = ListenerStatus.isGranted(context)
        override suspend fun report() = ListenerStatus.report(context)
        override fun storageUnavailableChanges() = ListenerStatus.storageUnavailableChanges(context)
        override suspend fun lastExportAt() = TransferStore.lastExportAt(context)
        override fun lastExportAtChanges() = TransferStore.lastExportAtChanges(context)
        override suspend fun reminderDismissedAt() = TransferStore.nudgeDismissedAt(context)
        override suspend fun recordReminderDismissed(at: Long) = TransferStore.recordNudgeDismissed(context, at)
        override fun damagedUpdates() = IntegrityStore.damagedUpdates(context)
        override suspend fun onboards(granted: Boolean) = onboards(context, granted)
        override val rewrites: Flow<Long> = Databases.rewrites
        override fun now() = System.currentTimeMillis()
        override suspend fun sleep(millis: Long) = kotlinx.coroutines.delay(millis)
    }
}
