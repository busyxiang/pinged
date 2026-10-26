package my.pinged.ledger.sources

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import my.pinged.capture.GuardedRunner
import my.pinged.capture.Graph
import my.pinged.capture.SourceCounters
import my.pinged.capture.SourceGaps
import my.pinged.data.entity.CaptureSource
import my.pinged.read.ReadRunner
import my.pinged.read.ReadSteps
import my.pinged.read.ReadState
import my.pinged.read.ScreenRead

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
    /**
     * Days since an enabled source last spoke, when that silence is out of
     * character for it (spec 10.3, `SourceGaps.quiet`); null otherwise.
     */
    val quietDays: Int? = null,
    /** What the source sent in the month before it went quiet, beside [quietDays]. */
    val usualMonthly: Int? = null,
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
    /** A ledger replacement turned the read away (#328): drawn as `REPLACING_YOUR_DATA`, never with [storageUnavailable]. */
    val replacing: Boolean = false,
)

/**
 * What the screen draws for [this] read. An unreadable ledger draws no rows,
 * whatever the last read held: `CANNOT READ YOUR DATA` in place of each empty
 * list, which an empty allow-list would contradict (spec 11.1).
 */
fun ReadState<Unit, SourcesState>.toState(): SourcesState {
    if (replacing) return SourcesState(loaded = true, replacing = true)
    if (unavailable) return SourcesState(loaded = true, storageUnavailable = true)
    return answer ?: SourcesState()
}

/**
 * Reads the allow-list, writes the allow-list, and holds nothing else.
 *
 * Both go through `:core:read`'s [ScreenRead] (#296), whose turn is what spec
 * 9.6's consent rests on: two taps on one toggle land in the order made, and a
 * read never publishes a snapshot older than a write that landed. The two holes
 * that guarantee has are recorded on `ScreenRead` and held by its `TurnTest`.
 *
 * An `AndroidViewModel` because `NavDisplay` owns which screens exist -- a
 * holder per Activity field does not scale past one -- and because
 * `viewModelScope` outlives a configuration change, which an Activity's
 * `lifecycleScope` cannot. The context it reads is the `Application`, so
 * surviving a configuration change leaks nothing.
 */
class SourcesViewModel internal constructor(app: Application, runner: ReadRunner) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    /**
     * A getter, not a field: `private val context: Context = app` is what lint's
     * `StaticFieldLeak` fails the build on, and it cannot see that this one is
     * the `Application`. [AndroidViewModel] holds the same reference anyway, so
     * reading it back costs nothing and stores nothing.
     */
    private val context: Context get() = getApplication<Application>()

    /**
     * Everything the screen draws, in no transaction. `SourcesScreen` reads it
     * on each resume and on `Databases.rewrites` through `rememberReadState`: a
     * package that spoke while the screen was away must appear, and a restore
     * must not leave the replaced ledger's sources switched on. Calls are made
     * on `viewModelScope`'s thread, the main thread, as every caller in the app
     * does.
     *
     * The failure that is not this screen's to survive is the database that
     * cannot be opened (spec 11.1): the runner turns it into
     * `ReadState.unavailable`, where unguarded it reached the thread's uncaught
     * handler and killed the process on every launch. The banner does not wait
     * on this read: `AppStatus` runs its own probe on resume, because this
     * screen may not be composed at all.
     */
    val read: ScreenRead<Unit, SourcesState> =
        ScreenRead.fixedStaged(viewModelScope, runner, "The allow-list cannot be read") { readAllowList() }

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
     * It is [read]'s `write`, so it is guarded and leased as every other access
     * on this screen is. On a connection another read has poisoned, the first
     * statement answers code 26; unguarded that reached `viewModelScope` with no
     * handler -- the app killed by a tap -- and a toggle OFF that died there left
     * the source enabled and its text still being stored. The guard runs the three
     * again on a new connection, which is safe because each is: an insert that
     * ignores an existing row, and two updates to a value. A write it refuses
     * leaves the row as it was, and the read after it draws that.
     *
     * The returned `Job` completes after that read, so a caller sequencing work
     * after the toggle sees the state it produced.
     */
    fun setEnabled(pkg: String, on: Boolean): Job =
        read.write("The allow-list cannot be changed") { db ->
            val dao = db.captureSourceDao()
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

    /**
     * One read of the allow-list, as [read]'s staged body: the database in one
     * leased step and the counts, which live in `DataStore`, after it, with
     * nothing of the ledger held.
     *
     * **No transaction, because `SourceGaps.refresh` is in it.** It writes
     * `expected_monthly_count` and scans `raw_capture` once per enabled source
     * still speaking (spec 10.3), and a transaction would hold Room's one
     * connection and `Databases`' monitor against every capture, stage two and
     * `retire` for the whole of it. Measured on emulator-5554 with 10 enabled
     * sources all speaking, in one transaction: 200ms at 20,000 captures
     * (`ReadHoldTest`) and 582ms at 50,000 (medians of 7). As statements the
     * connection is held one scan at a time, but the lease still spans all of
     * them, as it always has: a gate waits for that, up to 5s. The `DataStore`
     * calls are outside the lease as well: a file write behind the connection
     * would queue every capture on a disk.
     */
    private suspend fun ReadSteps.readAllowList(): SourcesState {
        val now = System.currentTimeMillis()
        val known: Map<String, CaptureSource> = statements { db ->
            // Here as well as in the daily check, which an OEM may never let run:
            // this screen is where the user can see which source has gone quiet.
            SourceGaps.refresh(db.captureSourceDao(), db.rawCaptureDao(), now)
            db.captureSourceDao().all().associateBy { it.pkg }
        }
        // The count means the same thing on both sides of the allow-list gate:
        // notifications this listener has been handed from that package. Counting
        // only the discarded side draws `0 SEEN` beside a bank that is visibly
        // capturing, which reads as broken rather than as restraint.
        //
        // Spec 9.6's 30-day drop first. A `capture_source` row is what "touched in
        // the picker" means: `setEnabled` writes one on the first toggle, and nothing
        // else writes one for a package the user never enabled. Pack packages are
        // kept too: Suggested draws them whatever the counter says, so dropping one
        // would only reset its count.
        SourceCounters.dropExpired(
            context,
            now = System.currentTimeMillis(),
            keep = known.keys + packLabels.keys,
        )
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
        ).let { row ->
            val quiet = known[pkg]?.let { SourceGaps.quiet(it, now) } ?: return@let row
            row.copy(quietDays = quiet.days, usualMonthly = quiet.expected)
        }

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
