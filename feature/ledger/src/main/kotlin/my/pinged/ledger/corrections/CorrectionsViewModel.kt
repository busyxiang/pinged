package my.pinged.ledger.corrections

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
class CorrectionsViewModel internal constructor(app: Application, runner: ReadRunner) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    /**
     * What is awaiting an answer, in one transaction. The swept version comes
     * from the capture store through `runBlocking`, which the body may do
     * because it runs off the main thread and cannot itself suspend.
     * `CorrectionsScreen` reads it on resume and on `Databases.rewrites`
     * through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, Pending> =
        ScreenRead.fixed(viewModelScope, runner, "Corrections cannot be read") { db ->
            val matcher = Graph.ruleMatcher()
            val compared = runBlocking { Corrections.comparedVersion(context) }
            Pending(Corrections.pending(compared, db.rawCaptureDao(), matcher), matcher.packVersion)
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
