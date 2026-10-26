package my.pinged

import android.app.Application
import android.database.SQLException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.pinged.capture.CaptureReport
import my.pinged.capture.GuardedRunner
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ReadState
import my.pinged.read.ScreenRead

/**
 * The app's status (#304): which capture banner the strip above every screen
 * shows, Spending's heard line and backup reminder, the tab bar's count of
 * what awaits review, and whether first run's onboarding is drawn in place of
 * all of it. `MainActivity` renders [state] and hands in what only it sees:
 * [resumed], [settingsRead], [dismissBackupReminder] and [barMayShow].
 *
 * **Probe before report.** `CaptureHealth`'s storage flag is written by
 * whatever opens the database, so a report read before the database is
 * tried describes the previous foreground. [sample] is the one caller of
 * both, and runs them in that order.
 *
 * **Why the flag is sampled so often.** The banner is drawn over Settings,
 * the screen it sends the user to, and nothing there is a resume. So a
 * sample also follows each Settings read of storage ([settingsRead]: keyed
 * on the read, not on what it found, because a capture refused as a gate
 * lifts can raise the flag between a sample's probe and its report, over a
 * ledger Settings reads as healthy every time), each change of the stored
 * flag (the clear after a sample taken inside a refusal's window: issue #16),
 * and each rewrite of the ledger (a restore from key-gone, which no resume
 * follows).
 *
 * **Watchers run while [state] is collected**, which `MainActivity` does
 * while it is started, and only changes wake them: the [resumed] after every
 * start reads the values for itself. The backup reminder is watched rather
 * than sampled because its own action settles it inside one foreground: the
 * export that "Export" leads to records its timestamp with no resume after
 * it, and so does a delete or a restore that replaces the ledger and fails
 * before `TransferStore.forget`.
 *
 * Nothing the samples read may throw into [scope], which in production is
 * `viewModelScope` with no `CoroutineExceptionHandler`: a throw there kills
 * the process on the home screen, on every foreground, for as long as the
 * fault lasts. Each read carries its own guard; see [exportOverdue]. The tab
 * bar's count is a [ScreenRead], and lets through what every screen's read
 * does: an instance closed under it is `CaptureStorage.guarded`'s to retry
 * and then refuse, and only a failure that is not the database being
 * unreadable escapes.
 *
 * @param scope where samples and watchers run; its thread is the one the
 *   inputs are called from, which [ScreenRead] requires.
 * @param runner the ledger: `GuardedRunner`, or `ScriptedRunner` in a test.
 */
internal class AppStatus(
    private val scope: CoroutineScope,
    private val runner: ReadRunner,
    private val sources: StatusSources,
) {
    /**
     * A grant means no onboarding, so the app is drawn from the first frame;
     * only an install without one waits for [StatusSources.onboards], behind
     * bare Paper.
     */
    private val readings = MutableStateFlow(Readings(onboarding = if (sources.isGranted()) false else null))

    /**
     * The tab bar's count (§9.2's ways in). It duplicates the count
     * `SpendingRead` reads for the pinned row, for a different screen on
     * different triggers.
     */
    private val awaitingReview = ScreenRead.fixed(scope, runner, AWAITING_REVIEW) { db ->
        db.txnDao().awaitingReviewCount()
    }

    /** One [sample] at a time, so two never land out of the order they read in. */
    private val sampling = Mutex()

    /** The decisions `MainActivity` draws. See [AppStatusState]. */
    val state: StateFlow<AppStatusState> = channelFlow {
        launch { watch() }
        combine(readings, awaitingReview.state, minutes()) { readings, review, now -> readings.decide(review, now) }
            .collect { send(it) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), readings.value.decide(awaitingReview.state.value, sources.now()))

    /** The Activity came to the foreground: the user may have changed anything in system settings. */
    fun resumed() {
        awaitingReview.refresh()
        scope.launch { sample() }
    }

    /** Settings read storage: the banner it is drawn under reads it again. */
    fun settingsRead() {
        scope.launch { sample() }
    }

    /**
     * Silence the backup reminder for [NUDGE_DISMISSED_FOR_MILLIS], then read
     * it again: it goes because the stored dismissal says so, so a write that
     * failed leaves it up, which is what the next foreground would show.
     */
    fun dismissBackupReminder() {
        scope.launch {
            try {
                sources.recordReminderDismissed(sources.now())
            } catch (thrown: IOException) {
                Log.w(TAG, "Could not record the backup reminder's dismissal", thrown)
            }
            readBackup()
        }
    }

    /**
     * The tab bar may be about to show its count: a tab change, or a return
     * to a tab's root (the inbox pushed over Charts answers cards). A capture
     * landing while Charts shows is not counted until one of these, a resume
     * or a rewrite; nothing here watches `txn`.
     */
    fun barMayShow() {
        awaitingReview.refresh()
    }

    /**
     * The probe, the backup reminder's count, the report the probe makes
     * true, then first run from that same report.
     */
    private suspend fun sample() = sampling.withLock {
        probe()
        readBackup()
        val report = sources.report()
        // From the same report, so first run cannot end on a grant the banner
        // has not yet heard of.
        val opening = sources.onboards(report.granted)
        readings.update { it.copy(report = report, onboarding = opening) }
    }

    /**
     * Open the database and run nothing, so the runner sets or clears the
     * storage flag for this foreground.
     *
     * **Its own read, and not the backup reminder's count.** A count that
     * throws `SQLiteException` aborts `CaptureStorage.guarded`'s body, and
     * `guarded` clears the flag only after a body that returned; so a probe
     * that counts leaves "CANNOT OPEN YOUR DATA" over a database that opens,
     * for as long as nothing else opens it.
     *
     * Guarded as [exportOverdue] is, for the flag's own write: what it
     * answers is in the flag, which [sample] reads next.
     */
    private suspend fun probe() {
        try {
            runner.read(PROBE) {}
        } catch (thrown: SQLException) {
            Log.w(TAG, "Could not open the ledger to sample capture", thrown)
        } catch (thrown: IOException) {
            Log.w(TAG, "Could not record whether the ledger opens", thrown)
        }
    }

    private suspend fun readBackup() {
        val overdue = exportOverdue()
        readings.update { it.copy(exportOverdue = overdue) }
    }

    /**
     * [exportIsOverdue] over the live numbers, or false if any part of
     * reading them fails: the reminder is the least important thing on the
     * home screen, and false is it saying nothing.
     *
     * **The guard is around the whole computation, and it is wider than
     * [ReadRunner]'s.** That one refuses a database that cannot be opened and
     * lets the rest through. `TxnDao.countAll` raises `SQLiteException` from
     * a scan that aborts mid-count, and the transfer store is a DataStore,
     * which raises `IOException` -- and so does the flag-clearing write
     * `CaptureStorage.guarded` makes *after* the count returns, which is why
     * the `try` is around the runner's call and not inside its body.
     * `GuardedWriteFailureTest` holds that placement.
     *
     * [SQLException], the superclass, not `SQLiteException`: an instance
     * closed under a count past a reset's lease wait answers "Error code: 21,
     * message: connection is closed" as the superclass (measured on
     * emulator-5554).
     */
    private suspend fun exportOverdue(): Boolean = try {
        when (val txns = runner.read(COUNTING) { db -> db.txnDao().countAll() }) {
            Outcome.Refused -> false
            is Outcome.Done -> exportIsOverdue(
                txns = txns.value,
                lastExportAt = sources.lastExportAt(),
                now = sources.now(),
                nudgeDismissedAt = sources.reminderDismissedAt(),
            )
        }
    } catch (thrown: SQLException) {
        Log.w(TAG, "Could not count the ledger for the backup reminder", thrown)
        false
    } catch (thrown: IOException) {
        Log.w(TAG, "Could not read the last export for the backup reminder", thrown)
        false
    }

    /**
     * Each change that the next [resumed] would not hear about in time.
     *
     * **Each `catch` ends only its own watch**, and only for this
     * subscription: the next start subscribes again and the resume after it
     * samples. A store that will not read answers in the direction that
     * makes no claim -- no reminder, the export offered rather than the
     * rescue.
     */
    private suspend fun watch(): Unit = coroutineScope {
        // `drop(1)`: the value on subscription is not a change.
        launch {
            sources.rewrites.drop(1).collect {
                awaitingReview.refresh()
                sample()
            }
        }
        launch {
            sources.storageUnavailableChanges()
                .catch { thrown -> Log.w(TAG, "Could not watch the storage flag", thrown) }
                .collect { sample() }
        }
        launch {
            sources.damagedUpdates()
                .catch { thrown ->
                    Log.w(TAG, "Could not watch the integrity verdict", thrown)
                    readings.update { it.copy(damaged = false) }
                }
                .collect { damaged -> readings.update { it.copy(damaged = damaged) } }
        }
        // The stored timestamp and not a callback on the tap: an export that
        // failed records none, so nothing emits and the reminder stays up.
        sources.lastExportAtChanges()
            .catch { thrown ->
                Log.w(TAG, "Could not watch the last export", thrown)
                readings.update { it.copy(exportOverdue = false) }
            }
            .collect { readBackup() }
    }

    /** The time now and once a minute, so a ledger left open does not say "HEARD 5 MIN" for an hour. */
    private fun minutes() = flow {
        while (true) {
            emit(sources.now())
            sources.sleep(MINUTE_MILLIS)
        }
    }

    /** What the samples and watchers last read, before any decision is made from it. */
    private data class Readings(
        val report: CaptureReport? = null,
        val exportOverdue: Boolean = false,
        val damaged: Boolean = false,
        val onboarding: Boolean?,
    ) {
        fun decide(review: ReadState<Unit, Int>, now: Long) = AppStatusState(
            onboarding = onboarding,
            banner = report?.let(::bannerFor),
            heard = heardLine(report, now),
            backupReminder = when {
                !backupReminderShows(report, exportOverdue) -> null
                damaged -> BackupReminderAction.Rescue
                else -> BackupReminderAction.Export
            },
            awaitingReview = review,
        )
    }

    companion object {
        /** The probe's name in the log. */
        const val PROBE = "The ledger cannot be opened to sample capture"

        /** The backup reminder's count's name in the log. */
        const val COUNTING = "The ledger cannot be counted for the backup reminder"

        /** The tab bar's count's name in the log. */
        const val AWAITING_REVIEW = "What awaits review cannot be counted"

        private const val MINUTE_MILLIS = 60_000L
    }
}

/**
 * [AppStatus]'s decisions, for `MainActivity` to draw.
 *
 * @property onboarding first run's onboarding is drawn instead of the app,
 *   or null before the first sample has said, drawn as bare Paper.
 * @property banner the capture banner, or none. See [bannerFor].
 * @property heard the ledger's heard line, or none. See [heardLine].
 * @property backupReminder Spending's backup reminder and where its action
 *   leads, or none. See [backupReminderShows].
 * @property awaitingReview the tab bar's count of what awaits review.
 */
internal data class AppStatusState(
    val onboarding: Boolean?,
    val banner: BannerKind?,
    val heard: String?,
    val backupReminder: BackupReminderAction?,
    val awaitingReview: ReadState<Unit, Int>,
)

/**
 * Where the backup reminder's action leads. On a damaged ledger it is the
 * rescue: design 6 withdraws the export there, and Settings' recovery notice
 * offers the rescue in its place.
 */
internal enum class BackupReminderAction { Export, Rescue }

/**
 * Holds `MainActivity`'s [AppStatus] across a rotation, scoped to the
 * Activity rather than to a destination.
 */
internal class AppStatusViewModel(app: Application) : AndroidViewModel(app) {
    val status = AppStatus(viewModelScope, GuardedRunner(app), StatusSources.OnDisk(app))
}

/**
 * **Thirty days is a guess**, with no evidence behind it, for a ledger that
 * gains roughly 112 transactions a month (spec 15).
 *
 * The 850 in the same sentence of spec 15 counts *captured notifications*, and
 * is not the figure this window is measured against: what [exportIsOverdue]
 * weighs is `TxnDao.countAll()` over the `txn` table, so thirty days here is
 * about a hundred transactions at risk rather than eight hundred rows.
 */
internal const val EXPORT_STALE_AFTER_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * How long the backup reminder's X silences it. Seven days because that is
 * what was asked for; like the thirty above, nothing measured it.
 */
internal const val NUDGE_DISMISSED_FOR_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Whether to tell the user their data is not backed up anywhere.
 *
 * There has to be something to lose: a banner on an empty ledger is the app
 * inventing a problem.
 *
 * [txns] is `countAll()` and so includes `REJECTED` rows, unlike the delete
 * sheet's count, which excludes them to agree with the feed. The question is
 * different: the sheet itemises what the user would recognise losing, and this
 * asks only whether an export file would carry anything at all.
 */
internal fun exportIsOverdue(txns: Int, lastExportAt: Long, now: Long, nudgeDismissedAt: Long = 0L): Boolean {
    if (txns == 0) return false
    // `now >= nudgeDismissedAt`: a dismissal in the future is a clock set
    // back, and silencing until the clock catches up could be years.
    if (now >= nudgeDismissedAt && now - nudgeDismissedAt < NUDGE_DISMISSED_FOR_MILLIS) return false
    return now - lastExportAt > EXPORT_STALE_AFTER_MILLIS
}

/** What the banner strip says, or nothing at all. One is drawn at a time. */
internal enum class BannerKind {
    STORAGE_UNAVAILABLE,
    NOT_GRANTED,
    HEALTH_UNREADABLE,
    NOTHING_SEEN,
    STOPPED,
}

/**
 * Which banner the strip shows, as a value so the order can be asserted without
 * a device.
 *
 * All five are about capture, and spec 10.2 -- "Detecting a dead listener,
 * honestly" -- is where they come from. [BannerKind.HEALTH_UNREADABLE] is that
 * section read strictly: a report that could not be taken is not a report of
 * health, and drawing nothing would be the one outcome the section exists to
 * prevent.
 *
 * The order is not a formatting choice, and it is the reason this is a
 * function rather than a `when` inside the composable that draws it: a fresh
 * install told its listener died, or a file that would not read drawn as
 * either, is a claim about a past that did not happen.
 */
internal fun bannerFor(report: CaptureReport): BannerKind? {
    val neverSeen = report.lastNotificationAt == 0L
    return when {
        // First, because it is the only state here where capture is certainly dead
        // rather than suspected dead -- and the only one every other signal
        // contradicts. The heartbeat is written before the first database call, so a
        // phone in this state reads as granted, recently active and not stale while
        // storing nothing.
        report.storageUnavailable -> BannerKind.STORAGE_UNAVAILABLE
        !report.granted -> BannerKind.NOT_GRANTED
        // Below `granted`, which is still measured when this is true, and above
        // everything else, all of which is not. `CaptureReport.healthUnreadable`
        // means `capture_health` would not read, and the never-seen encoding the
        // report then carries would otherwise be drawn as one of the two
        // branches below -- telling a phone that has captured for months either
        // that it is a new install or that its listener died, from a file that
        // was never read.
        report.healthUnreadable -> BannerKind.HEALTH_UNREADABLE
        // Before `looksDead`, and that is why there are three capture states here
        // rather than two. `looksDead` is true the moment the grant is given and
        // before anything has arrived, which is correct as a *report* -- nothing has
        // been seen, and no API distinguishes a silent phone from a dead listener --
        // and wrong as a *banner*, where it claims a fresh install's phone shut Pinged
        // down and lost spending, both claims about a past that does not exist yet.
        // The "stopped" copy is reserved for a report that has seen something and then
        // stopped.
        neverSeen -> BannerKind.NOTHING_SEEN
        report.looksDead -> BannerKind.STOPPED
        else -> null
    }
}

/**
 * Whether Spending's backup reminder (#252) may show: the export is overdue
 * and the strip above has nothing to say, or nothing yet to say it from.
 *
 * **Never beside a capture banner.** Each of those says capture is broken
 * now; the reminder is about the past, and an export can be taken at any
 * later moment while spending going unrecorded cannot be recovered. Beside
 * "CAPTURE STOPPED" it reads as the thing to fix. `BannerPriorityTest` holds
 * it under each.
 *
 * The spec says nothing about backup staleness, so the threshold behind it
 * ([EXPORT_STALE_AFTER_MILLIS]) and the reminder itself are this file's, not
 * the spec's.
 */
internal fun backupReminderShows(report: CaptureReport?, exportOverdue: Boolean): Boolean =
    report != null && bannerFor(report) == null && exportOverdue

/**
 * Spec 10.2's always-visible signal, for the ledger's top bar: when Pinged last
 * heard a notification from any app, or null where [bannerFor] has something
 * truer to say.
 *
 * **"Heard", not the spec's "captured".** The heartbeat counts every app's
 * notifications, and almost none of them are captured; a user who reads
 * "captured 2 min ago" checks for a transaction that was never there.
 *
 * Measured from [now], not from the report's `staleForMillis`, so a screen
 * left open keeps counting. The heartbeat is written at most once per
 * `CaptureHealth.THROTTLE_MILLIS`, so every answer is up to five minutes
 * staler than the truth, never fresher, and nothing finer than five minutes
 * is claimed.
 *
 * **No "ago".** The chip shares the top bar with the month and the magnifier,
 * and at CI's 320dp "HEARD IN THE LAST 5 MIN" wrapped to three lines;
 * `LedgerArtboardTest` holds the widest of these to one. Nor the board's
 * "LISTENING", or a "LIVE": each claims what [CaptureReport.looksDead] says
 * this app cannot know.
 */
internal fun heardLine(report: CaptureReport?, now: Long): String? {
    if (report == null || !report.granted || report.storageUnavailable || report.healthUnreadable) return null
    if (report.lastNotificationAt == 0L) return null
    val minutes = (now - report.lastNotificationAt) / 60_000L
    return when {
        minutes < 5 -> "HEARD <5 MIN"
        minutes < 60 -> "HEARD $minutes MIN"
        minutes < 24 * 60 -> "HEARD ${minutes / 60} HR"
        minutes < 2 * 24 * 60 -> "HEARD 1 DAY"
        else -> "HEARD ${minutes / (24 * 60)} DAYS"
    }
}
