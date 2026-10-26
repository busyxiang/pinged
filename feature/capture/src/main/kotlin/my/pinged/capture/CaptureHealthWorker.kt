package my.pinged.capture

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import my.pinged.data.Databases

/**
 * Spec 10.2's daily check, which is **best-effort and not a guarantee**: it runs
 * in the process an OEM kills, so on the ROMs that kill it, it does not run.
 * The foreground check and the home screen's "heard" line are the detectors
 * that can be relied on; this sometimes catches a problem earlier.
 */
class CaptureHealthWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val now = System.currentTimeMillis()
        // Before the report, as `AppStatus.sample` orders it: this open
        // is the probe that sets or clears the storage flag the report reads.
        val quiet = CaptureStorage.guarded(
            app,
            what = "The daily check cannot read the sources",
            unavailable = { emptyList() },
            damageStopsCapture = false,
        ) { HealthCheck.quietSources(app, now) }
        HealthCheck.settle(app, ListenerStatus.report(app, now), quiet)
        return Result.success()
    }

    companion object {
        internal const val UNIQUE_NAME = "pinged.health"

        /**
         * `KEEP`, so a foreground does not push the next run a day further
         * out. No constraints: a check that waits for charging is a check that
         * runs less on exactly the phones that kill this app.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CaptureHealthWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}

/** The daily check's reads and its one piece of memory. */
internal object HealthCheck {
    private const val TAG = "PingedHealth"

    /** [HealthAlert.Alert.key] of what is in the shade now, absent for nothing. */
    private val ALERTED = stringPreferencesKey("health_alerted")

    /**
     * Every enabled source gone quiet, after bringing their expectations up to
     * date. Callers run this inside `CaptureStorage.guarded`.
     */
    fun quietSources(context: Context, now: Long): List<SourceGaps.Quiet> {
        val sources = Databases.captureSourceDao(context)
        SourceGaps.refresh(sources, Databases.rawCaptureDao(context), now)
        return sources.enabled().mapNotNull { SourceGaps.quiet(it, now) }
    }

    /**
     * [HealthAlert.decide], delivered.
     *
     * Nothing to say is not "all is well" when the heartbeat could not be
     * read: an alert already up stays up rather than being withdrawn on a file
     * that would not open.
     */
    suspend fun settle(context: Context, report: CaptureReport, quiet: List<SourceGaps.Quiet>) {
        val alert = HealthAlert.decide(report, quiet)
        if (alert != null || !report.healthUnreadable) deliver(context, alert)
    }

    /**
     * Posts [alert] if it is news, and withdraws what was posted once nothing
     * is wrong.
     *
     * **Nothing is recorded when a post cannot be shown**, so the alert goes
     * up the first day after the user allows notifications rather than being
     * remembered as already said.
     */
    private suspend fun deliver(context: Context, alert: HealthAlert.Alert?) {
        try {
            val said = context.captureStore.data.first()[ALERTED]
            if (alert == null) {
                if (said != null) {
                    CaptureAlerts.withdraw(context)
                    context.captureStore.edit { it.remove(ALERTED) }
                }
                return
            }
            if (alert.key == said || !CaptureAlerts.canPost(context)) return
            CaptureAlerts.post(context, alert)
            context.captureStore.edit { it[ALERTED] = alert.key }
        } catch (thrown: IOException) {
            // `capture_health` will not read: the foreground banner says so.
            Log.w(TAG, "Could not read or record what the daily check said", thrown)
        }
    }

    /** Tests only: as if nothing had ever been posted. */
    internal suspend fun forgetAlerted(context: Context) {
        context.captureStore.edit { it.remove(ALERTED) }
    }
}
