package my.pinged.ledger.corrections

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.pinged.capture.CaptureStorage
import my.pinged.capture.Corrections
import my.pinged.capture.Graph
import my.pinged.data.Databases

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

/**
 * Spec 5.5's reviewable list: reads [Corrections.pending], and writes the
 * user's answer to one item.
 *
 * Every read and write takes [turn], so an answer and the read after it run
 * in the order they were asked for: two taps on one card answer it once, the
 * second finding it already answered (`Corrections.accept` returns false),
 * and no read publishes a list older than an answer that landed.
 */
class CorrectionsViewModel(app: Application) : AndroidViewModel(app) {

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    private val turn = Mutex()
    private val _state = MutableStateFlow(CorrectionsState())
    val state: StateFlow<CorrectionsState> = _state.asStateFlow()

    fun refresh(): Job = viewModelScope.launch { turn.withLock { _state.value = read() } }

    fun accept(correction: Corrections.Correction): Job =
        answer { Corrections.accept(Databases.rawCaptureDao(context), correction) }

    fun decline(correction: Corrections.Correction): Job =
        answer { Corrections.decline(Databases.rawCaptureDao(context), correction) }

    /**
     * Guarded as every write in this module is, since `viewModelScope` has no
     * handler and an unguarded open in spec 11.1's state kills the process on
     * a tap. A write the guard refuses leaves the row as it was, and the read
     * after it draws that.
     */
    private fun answer(write: () -> Boolean): Job = viewModelScope.launch {
        turn.withLock {
            CaptureStorage.guarded(
                context,
                what = "The correction could not be saved",
                unavailable = { false },
                damageStopsCapture = false,
            ) { write() }
            _state.value = read()
        }
    }

    private suspend fun read(): CorrectionsState =
        CaptureStorage.guarded(
            context,
            what = "Corrections cannot be read",
            unavailable = { CorrectionsState(loaded = true, storageUnavailable = true) },
            damageStopsCapture = false,
        ) {
            val matcher = Graph.ruleMatcher()
            val found = Corrections.pending(context, Databases.rawCaptureDao(context), matcher)
            CorrectionsState(
                loaded = true,
                checking = found == null,
                items = found.orEmpty(),
                packVersion = matcher.packVersion,
            )
        }
}
