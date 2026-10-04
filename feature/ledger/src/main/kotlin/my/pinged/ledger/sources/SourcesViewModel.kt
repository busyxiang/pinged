package my.pinged.ledger.sources

import androidx.annotation.VisibleForTesting
import my.pinged.data.Databases
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.pinged.capture.Graph
import my.pinged.capture.CaptureStorage
import my.pinged.capture.SourceCounters
import my.pinged.data.entity.CaptureSource

/**
 * One row of the allow-list, and deliberately **not** the `CaptureSource`
 * entity.
 *
 * `seen_count` lives in `DataStore` and not in that table (spec 9.6), so a row
 * is a join of two stores. Saying so in the type is cheaper than a screen that
 * pretends one query produced it.
 */
data class SourceRow(
    val pkg: String,
    val label: String,
    val seenCount: Int,
    val enabled: Boolean,
    /**
     * Spec 9.6's `is_authoritative` flag. Defaulted because nothing writes the
     * column yet -- `setAuthoritative` is a settings toggle this milestone does
     * not have -- so the chip is currently never drawn on a device.
     */
    val authoritative: Boolean = false,
    /**
     * Whether this package's notification text is in `raw_capture` -- not the
     * same question as whether it is being captured *now*. Disabling a source
     * stops the next capture and deletes nothing, so a source the user enabled,
     * let run and switched off again still has its text on disk.
     *
     * `capture_source.last_notification_at` answers it exactly: `SourceLiveness`
     * writes it past the allow-list gate, one statement before the capture, so
     * the one direction it can be wrong in withholds a true claim rather than
     * making a false one.
     */
    val textStored: Boolean = false,
)

/**
 * What the screen draws. Two lists, because spec 9.6 names two sections and
 * they mean different things.
 */
data class SourcesState(
    /** Pack packages that `PackageManager` can confirm are on this device. */
    val suggested: List<SourceRow> = emptyList(),
    /** Discovered by posting something, and storing nothing (spec 9.6). */
    val seenNotCaptured: List<SourceRow> = emptyList(),
    /** False until the first read completes, so an empty screen is not a lie. */
    val loaded: Boolean = false,
    /**
     * The database could not be opened at all (spec 11.1). The screen draws
     * `CANNOT READ YOUR DATA` in place of each empty list rather than an empty
     * allow-list, which would read as "no app has ever notified you" -- a
     * confident answer this state cannot support.
     */
    val storageUnavailable: Boolean = false,
)

/**
 * Reads the allow-list, writes the allow-list, and holds nothing else.
 *
 * An `AndroidViewModel` because `NavDisplay` owns which screens exist -- a
 * holder per Activity field does not scale past one -- and because
 * `viewModelScope` outlives a configuration change, which an Activity's
 * `lifecycleScope` cannot. The context it reads is the `Application`, so
 * surviving a configuration change leaks nothing.
 */
class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * A getter, not a field: `private val context: Context = app` is what lint's
     * `StaticFieldLeak` fails the build on, and it cannot see that this one is
     * the `Application`. [AndroidViewModel] holds the same reference anyway, so
     * reading it back costs nothing and stores nothing.
     */
    private val context: Context get() = getApplication<Application>()

    /**
     * `viewModelScope`: `SupervisorJob() + Dispatchers.Main.immediate`, which
     * is what puts every queued body on the main thread.
     * `MainThreadRefreshTest.enqueueStillRunsOnTheMainThread` is the assertion
     * that it still does. It says nothing about the thread [tail] is written
     * on; see there.
     */
    private val scope: CoroutineScope get() = viewModelScope
    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    /**
     * The tail of the chain every read and every write joins, so they run in the
     * order they were asked for.
     *
     * Unordered, two taps on one toggle -- on, then off -- race, and SQLite
     * serialising the statements does not help: it serialises them in whatever
     * order the threads arrive, so the write the user made *first* can land last.
     * The row then says `enabled = 1` and the last thing the user did was switch
     * it off, which is spec 9.6 consent inverted and invisible to them. The same
     * chain stops a read publishing a snapshot older than a write that landed,
     * which shows up as a toggle flipping back under the finger.
     *
     * Written and read on the thread that *calls* [enqueue], not inside the
     * coroutine it launches -- and every caller in the app is the main thread:
     * `SourcesScreen`'s `LifecycleResumeEffect` and a Compose click. So the
     * chaining needs no lock. What it must not do is claim its place inside the
     * new coroutine, which would move the race rather than close it. No test
     * holds the caller-side half of this; the tests call [enqueue] from the
     * instrumentation thread, which is safe only because each of them is the
     * only caller.
     */
    private var tail: CompletableDeferred<Unit>? = null

    /**
     * Queue [block] behind everything already asked for.
     *
     * **The wait is [NonCancellable], and the chain is a signal rather than the
     * predecessor's own `Job`.** Both are the fix for one hole: `Job.join()` is
     * cancellable and a cancelled `Job` *completes*, so a chain built on
     * `previous.join()` severs the moment anything cancels a link that is still
     * waiting, and everything queued behind it runs concurrently with it.
     *
     * Waiting inside [NonCancellable] means cancelling *this* coroutine cannot
     * skip the wait; it still cancels promptly at [block]'s first suspension
     * point. The signal completes in a `finally`, so a link that throws or is
     * cancelled releases the queue rather than deadlocking it -- but only after
     * it has itself waited, which is what the `Job` version lost.
     */
    private fun enqueue(block: suspend () -> Unit): Job {
        val previous = tail
        val mine = CompletableDeferred<Unit>()
        tail = mine
        return scope.launch {
            try {
                withContext(NonCancellable) { previous?.await() }
                block()
            } finally {
                mine.complete(Unit)
            }
        }
    }

    /**
     * Tests only: a suspension point at the head of each queued operation. The
     * ordering is invisible when every operation takes the same few milliseconds,
     * so holding the first one open is what makes the second's position
     * observable.
     */
    @VisibleForTesting
    internal var beforeEachOperation: suspend () -> Unit = {}

    /**
     * Read everything the screen draws, and publish it.
     *
     * Returns its `Job` so a caller can order work after it -- the guarantee
     * the ordering tests hold, and the one anything sequencing a write after a
     * read depends on.
     *
     * **It does not cancel the read in flight.** Cancel-and-restart would save
     * one read per cold launch and cost the ordering entirely: `Job.join()` on
     * a cancelled job returns immediately, so a `join()` on a refresh becomes a
     * no-op the instant a later one cancels the job it is waiting on. Two
     * refreshes queue instead, the later publishing last.
     */
    fun refresh(): Job = enqueue {
        beforeEachOperation()
        _state.value = readOrReportStorage()
    }

    /**
     * [read], with the one failure that is not this screen's to survive.
     *
     * `read` opens the database through `Databases`, and `DatabaseFactory.build`
     * is eager, so spec 11.1's device-transfer state throws
     * `DatabaseKeyUnavailableException` out of the first line. `viewModelScope`
     * is a bare `SupervisorJob` with no CoroutineExceptionHandler, so unguarded
     * that reaches the thread's uncaught handler and kills the process on every
     * launch -- the outcome spec 11.1 names as the one that must not happen.
     *
     * Recording it also feeds the banner, though the banner does not wait on
     * it: `MainActivity.onResume` runs its own `CaptureStorage.guarded` probe,
     * because this screen may not be composed at all.
     */
    private suspend fun readOrReportStorage(): SourcesState =
        CaptureStorage.guarded(
            context,
            what = "The allow-list cannot be read",
            unavailable = { SourcesState(loaded = true, storageUnavailable = true) },
            damageStopsCapture = false,
        ) { read() }

    /**
     * The allow-list write, and the only one in the app. Three statements, in
     * this order, none optional.
     *
     * `insertIfNew` first, because a package discovered through the counters
     * alone has no `capture_source` row -- stage 1 never writes a disabled
     * package -- so `setEnabled` would be an UPDATE matching nothing, leaving a
     * user certain they enabled their bank.
     *
     * `setLabel` next, so a row inserted under a bare package identifier picks up
     * the real application label once `PackageManager` can resolve it.
     *
     * `setEnabled` last, and a targeted single-column UPDATE: `CaptureSourceDao`
     * has no whole-row upsert, because a partial write through `@Insert(REPLACE)`
     * reverted `enabled` to false and destroyed consent unrecoverably.
     *
     * **Guarded**, as every other database access on this screen is. On a
     * connection another read has poisoned, the first statement answers code
     * 26; unguarded that reached `viewModelScope` with no handler -- the app
     * killed by a tap -- and a toggle OFF that died there left the source
     * enabled and its text still being stored. The guard runs the three again
     * on a new connection, which is safe because each is: an insert that
     * ignores an existing row, and two updates to a value. A write it refuses
     * leaves the row as it was, and the read after it draws that.
     */
    fun setEnabled(pkg: String, on: Boolean): Job =
        enqueue {
            beforeEachOperation()
            CaptureStorage.guarded(
                context,
                what = "The allow-list cannot be changed",
                unavailable = {},
                damageStopsCapture = false,
            ) {
                val dao = Databases.captureSourceDao(context)
                val label = label(InstalledApps(context.packageManager), pkg, known = null, usePackLabel = true)
                dao.insertIfNew(
                    CaptureSource(
                        pkg = pkg,
                        label = label,
                        enabled = false,
                        firstSeenAt = System.currentTimeMillis(),
                    ),
                )
                dao.setLabel(pkg, label)
                dao.setEnabled(pkg, on)
            }
            _state.value = readOrReportStorage()
        }

    /**
     * No `withContext(Dispatchers.IO)` of its own: every caller reaches this
     * through [readOrReportStorage], and `CaptureStorage.guarded` dispatches
     * its whole body -- which `GuardedDispatchTest` pins and
     * `MainThreadRefreshTest` exercises from the thread that matters.
     */
    private suspend fun read(): SourcesState {
        val known: Map<String, CaptureSource> =
            Databases.captureSourceDao(context).all().associateBy { it.pkg }
        // The count means the same thing on both sides of the allow-list gate:
        // notifications this listener has been handed from that package. Counting
        // only the discarded side draws `0 SEEN` beside a bank that is visibly
        // capturing, which reads as broken rather than as restraint.
        val counts = SourceCounters.allSeenCounts(context)
        val apps = InstalledApps(context.packageManager)

        // Every pack package this device can confirm, plus every source the user has
        // actually enabled. The second half is not decoration: a bank outside the
        // pack is enabled from the lower list, and without it would stay there under
        // a heading saying NOT CAPTURED while its text was being written.
        val upper: List<String> =
            (Graph.parsePack().packages.filter { apps.isInstalled(it.pkg) }.map { it.pkg } +
                known.values.filter { it.enabled }.map { it.pkg }).distinct()

        // One row shape for both sections, and one answer to "what do we call this
        // package": a second spelling of the label fallback is a name written into
        // `capture_source` that disagrees with the name drawn.
        fun rowFor(pkg: String, usePackLabel: Boolean) = SourceRow(
            pkg = pkg,
            label = label(apps, pkg, known[pkg]?.label, usePackLabel),
            seenCount = counts[pkg] ?: 0,
            enabled = known[pkg]?.enabled == true,
            authoritative = known[pkg]?.isAuthoritative == true,
            textStored = known[pkg]?.lastNotificationAt != null,
        )

        val suggested = upper
            // The pack's own label is the better fallback than the raw
            // identifier here: it is the name the rules were written for.
            .map { rowFor(it, usePackLabel = true) }
            .sortedWith(compareByDescending<SourceRow> { it.enabled }.thenBy { it.label })

        val alreadyShown = suggested.mapTo(mutableSetOf()) { it.pkg }
        val seenNotCaptured = counts.keys
            .asSequence()
            .filter { it !in alreadyShown }
            // "NOT CAPTURED" is a claim, not a layout hint. Anything enabled is already
            // in `upper` and excluded by `alreadyShown`; this says so out loud, because
            // the day that stops being true is the day this section starts lying.
            .filter { known[it]?.enabled != true }
            // No pack label here: a package the pack has never heard of has
            // none, and on API 30+ resolveLabel is expected to fail for
            // anything outside <queries>, so the raw identifier is what shows.
            // Enough to enable it; not pretty (spec 9.6).
            .map { rowFor(it, usePackLabel = false) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()

        return SourcesState(
            suggested = suggested,
            seenNotCaptured = seenNotCaptured,
            loaded = true,
        )
    }

    /**
     * What to call a package, stated once.
     *
     * The installed application's own label first, then whatever was recorded
     * in `capture_source`, then -- for a package the pack knows -- the name the
     * rules were written for, and the raw identifier last. `setEnabled` writes
     * this same answer, so the stored label cannot drift from the drawn one.
     */
    private fun label(
        apps: InstalledApps,
        pkg: String,
        known: String?,
        usePackLabel: Boolean,
    ): String = apps.label(pkg)
        ?: known
        ?: (if (usePackLabel) packLabels[pkg] else null)
        ?: pkg

    /**
     * The pack's labels as a map, built once rather than scanned per row.
     * `Graph.parsePack()` is memoized, so the initialiser is a volatile read
     * plus one pass over a pack of tens.
     */
    private val packLabels: Map<String, String> by lazy {
        Graph.parsePack().packages.associate { it.pkg to it.label }
    }

}

/**
 * `PackageManager` asked once per package, for a single read.
 *
 * Without the memo, `isInstalled` and a label lookup each call
 * `getApplicationInfo`: two binder round trips per pack package, and for a
 * discovered package one that *throws* -- outside `<queries>` that is the
 * expected answer, so each fills in a stack trace. Sixty discovered packages
 * is over a hundred round trips per read, on every resume.
 *
 * A class rather than a closure over a local map: this is handed to [label],
 * and a lambda would keep the whole enclosing read alive with it.
 */
private class InstalledApps(private val pm: PackageManager) {
    private val info = HashMap<String, ApplicationInfo?>()

    /**
     * Memoized including the misses, which are the expensive ones. `getOrPut`
     * would not do: it recomputes whenever the stored value is null, which
     * here is exactly the case worth remembering.
     */
    private fun infoOf(pkg: String): ApplicationInfo? {
        if (info.containsKey(pkg)) return info[pkg]
        return runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull().also { info[pkg] = it }
    }

    /**
     * Visible, not installed. From API 30 `PackageManager` answers only about
     * packages this app declared an interest in, so a `NameNotFoundException`
     * means "installed, or not, and you may not know" for anything outside
     * `<queries>`. The screen treats that as absent, which is the only honest
     * reading available without the package-visibility permission this app
     * will not ship.
     */
    fun isInstalled(pkg: String): Boolean = infoOf(pkg) != null

    fun label(pkg: String): String? = infoOf(pkg)
        ?.let { pm.getApplicationLabel(it).toString() }
        ?.takeIf { it.isNotBlank() && it != pkg }
}
