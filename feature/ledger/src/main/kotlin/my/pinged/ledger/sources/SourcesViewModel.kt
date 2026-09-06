package my.pinged.ledger.sources

import androidx.annotation.VisibleForTesting
import my.pinged.data.Databases
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
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
     * The database could not be opened at all (spec 11.1). The screen renders
     * nothing rather than an empty allow-list, which would read as "no app has
     * ever notified you" -- a confident answer this state cannot support.
     */
    val storageUnavailable: Boolean = false,
)

/**
 * Reads the allow-list, writes the allow-list, and holds nothing else.
 *
 * Not an `androidx.lifecycle.ViewModel`. It owns no state that has to survive
 * a configuration change -- every field is re-read from Room and DataStore in
 * a few milliseconds -- and the lifecycle-viewmodel-compose artifact would be
 * a dependency bought for one class. The caller supplies the scope, which is
 * the activity's, so the reads die with the screen.
 */
class SourcesViewModel(
    private val context: Context,
    private val scope: CoroutineScope,
) {
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
     * Written and read only from the caller's thread -- `onResume`, a
     * `LaunchedEffect` and a Compose click are all the main thread -- so the
     * chaining needs no lock. What it must not do is claim its place inside the
     * new coroutine, which would move the race rather than close it.
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
     * Returns its `Job` so a caller can order work after it: this read is what
     * probes the database and therefore what records or clears the storage flag,
     * and `MainActivity` reads that flag to draw the banner. Launched
     * independently, the flag read wins and the banner describes the previous
     * foreground.
     *
     * **It does not cancel the read in flight.** Cancel-and-restart saved one
     * read per cold launch and cost the ordering entirely, because `Job.join()`
     * on a cancelled job returns immediately -- so the `join()` in `onResume`
     * became a no-op the instant the `LaunchedEffect` cancelled the job it was
     * waiting on. Two refreshes queue instead, the later publishing last.
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
     * [DatabaseKeyUnavailableException] out of the first line. `lifecycleScope`
     * carries no CoroutineExceptionHandler, so that reached the thread's uncaught
     * handler and killed the process on every launch, twice -- which spec 11.1
     * names as the outcome that must not happen.
     *
     * Recording it is also what the banner reads. Otherwise the flag is only set
     * by the listener or the worker, both of which need something to have hit the
     * dead database first.
     */
    private suspend fun readOrReportStorage(): SourcesState =
        CaptureStorage.guarded(
            context,
            what = "The allow-list cannot be read",
            unavailable = { SourcesState(loaded = true, storageUnavailable = true) },
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
     */
    fun setEnabled(pkg: String, on: Boolean): Job =
        enqueue {
            beforeEachOperation()
            withContext(Dispatchers.IO) {
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

    private suspend fun read(): SourcesState = withContext(Dispatchers.IO) {
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
        // pack is enabled from the lower list, and without it stayed there under a
        // heading saying NOT CAPTURED while its text was being written.
        val upper: List<String> =
            (Graph.parsePack().packages.filter { apps.isInstalled(it.pkg) }.map { it.pkg } +
                known.values.filter { it.enabled }.map { it.pkg }).distinct()

        // One row shape, and one answer to "what do we call this package". These were
        // two near-identical constructions and the label fallback had three spellings,
        // so the name written into `capture_source` could disagree with the name
        // drawn in either list.
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

        SourcesState(suggested = suggested, seenNotCaptured = seenNotCaptured, loaded = true)
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
 * `isInstalled` and `resolveLabel` each called `getApplicationInfo`, so every
 * pack package cost two binder round trips and every discovered package one
 * that *throws* -- outside `<queries>` that is the expected answer, so each
 * fills in a stack trace. Sixty discovered packages meant over a hundred round
 * trips per read, on every resume.
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
