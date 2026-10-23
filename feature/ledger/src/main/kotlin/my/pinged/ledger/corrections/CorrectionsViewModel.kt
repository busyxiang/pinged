package my.pinged.ledger.corrections

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import my.pinged.capture.Corrections
import my.pinged.capture.Graph
import my.pinged.capture.GuardedRunner
import my.pinged.data.PingedDatabase
import my.pinged.read.ReadRunner
import my.pinged.read.ReadState
import my.pinged.read.ScreenRead

/** What the corrections screen draws. */
data class CorrectionsState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    /**
     * The current pack's sweep has not reached the end of the history, so
     * there is no list to draw yet; see [Corrections.pending].
     */
    val checking: Boolean = false,
    val items: List<Corrections.Correction> = emptyList(),
    val packVersion: Int = 0,
    /** Spec 11.1: the database could not be opened, which is not "nothing to review". */
    val storageUnavailable: Boolean = false,
)

/** What one read of [CorrectionsViewModel.read] found: [Corrections.pending]'s list, null while [checking]. */
internal data class Pending(val found: List<Corrections.Correction>?, val packVersion: Int)

/**
 * What the screen draws for [this] read: an unreadable ledger draws no list,
 * whatever the last read held, as `LearnedViewModel`'s does.
 */
internal fun ReadState<Unit, Pending>.toState(): CorrectionsState {
    if (unavailable) return CorrectionsState(loaded = true, storageUnavailable = true)
    val pending = answer ?: return CorrectionsState()
    return CorrectionsState(
        loaded = true,
        checking = pending.found == null,
        items = pending.found.orEmpty(),
        packVersion = pending.packVersion,
    )
}

/**
 * Spec 5.5's reviewable list: reads [Corrections.pending], and writes the
 * user's answer to one item.
 *
 * The list is `:core:read`'s [ScreenRead] (#296) and an answer is its `write`,
 * so an answer and the read after it run in the order they were asked for: two
 * taps on one card answer it once, the second finding it already answered
 * (`Corrections.accept` returns false), and no read publishes a list older
 * than an answer that landed.
 */
class CorrectionsViewModel internal constructor(
    app: Application,
    runner: ReadRunner,
    /** Where the read's own work runs; a JVM test passes the one it drives. */
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    /**
     * What is awaiting an answer. The swept version is read from the capture
     * store first, and the list a page at a time: each page of captures in a
     * transaction of its own, and the re-reading of them outside it. That is
     * [ScreenRead.fixedStaged], because `Corrections.pending` matches every
     * capture still at an older pack version, and one transaction over the list
     * holds Room's one connection and `Databases`' monitor for all of it.
     * Measured on emulator-5554 over 1,000 awaiting captures (`ReadHoldTest`):
     * a page's transaction held them 6ms at the median and 21ms at the longest
     * of 77, against 200ms median and 254ms longest of 7 for one transaction
     * over the whole list, and the 66ms a lease may leave up. A capture can land
     * between pages, so the list is not one snapshot: `Corrections.accept`
     * refuses a capture that moved since it was read.
     * `CorrectionsScreen` reads it on resume and on `Databases.rewrites`
     * through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, Pending> =
        ScreenRead.fixedStaged(viewModelScope, runner, "Corrections cannot be read", dispatcher) {
            val matcher = Graph.ruleMatcher()
            // The open, asked for before the sweep has reached the end as well,
            // when no page is read: a ledger that cannot be opened is
            // `unavailable` (spec 11.1), not "still checking".
            transaction { }
            val compared = Corrections.comparedVersion(context)
            val found = Corrections.pending(compared, matcher) { afterId ->
                transaction { db -> Corrections.stalePage(db.rawCaptureDao(), matcher.packVersion, afterId) }
            }
            Pending(found, matcher.packVersion)
        }

    fun accept(correction: Corrections.Correction): Job =
        answer("The correction could not be saved") { Corrections.accept(it.rawCaptureDao(), correction) }

    fun decline(correction: Corrections.Correction): Job =
        answer("The correction could not be saved") { Corrections.decline(it.rawCaptureDao(), correction) }

    /** A write the guard refuses leaves the row as it was, and the read after it draws that. */
    private fun answer(what: String, write: (PingedDatabase) -> Boolean): Job = viewModelScope.launch {
        read.write(what) { db -> write(db) }.await()
    }
}
