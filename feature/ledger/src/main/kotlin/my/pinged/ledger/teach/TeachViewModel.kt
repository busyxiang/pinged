package my.pinged.ledger.teach

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ParseWorker
import my.pinged.data.Databases
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.UserTemplateRule
import my.pinged.parse.Direction
import my.pinged.parse.MatchOutcome
import my.pinged.parse.TaughtTemplate

/** What the Teach screen draws. */
data class TeachState(
    /** False until the first read completes. */
    val loaded: Boolean = false,
    /** The capture being taught, or null once it has left the unread list. */
    val capture: UnreadCapture? = null,
    val selection: TeachSelection? = null,
    val direction: Direction = Direction.EXPENSE,
    /** Saved: the screen leaves. */
    val saved: Boolean = false,
    /** Spec 11.1: the database could not be opened. */
    val storageUnavailable: Boolean = false,
)

/**
 * Spec 5.8's Teach screen for unread capture [captureId]: two taps, a
 * direction, and "Save this rule".
 *
 * Saving writes the `user_template_rule` and hands back to stage two every
 * unread capture of the app the rule reads, the one taught on among them, so
 * each becomes a transaction -- held for review, as its first three are --
 * rather than waiting for a message that may never come again.
 */
class TeachViewModel(
    app: Application,
    private val captureId: Long,
    /** Stage two, asked to run once a save has requeued its captures. A test records it instead. */
    private val runStageTwo: (Context) -> Unit = ParseWorker::enqueue,
) : AndroidViewModel(app) {

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    private val turn = Mutex()
    private val _state = MutableStateFlow(TeachState())
    val state: StateFlow<TeachState> = _state.asStateFlow()

    init {
        viewModelScope.launch { turn.withLock { _state.value = read() } }
    }

    fun tap(index: Int) = _state.update { it.copy(selection = it.selection?.tap(index)) }

    fun noMerchant() = _state.update { it.copy(selection = it.selection?.noMerchant()) }

    fun direction(direction: Direction) = _state.update { it.copy(direction = direction) }

    /**
     * Saves what the screen shows, if both taps are made. A refused write
     * leaves the screen as it was, so the user can try again.
     */
    fun save(now: Long = System.currentTimeMillis()): Job = viewModelScope.launch {
        turn.withLock {
            val shown = _state.value
            val capture = shown.capture ?: return@withLock
            val selection = shown.selection?.takeIf { it.step == TeachSelection.Step.READY } ?: return@withLock
            val derived = selection.derived() ?: return@withLock
            val saved = guarded("The taught message could not be saved", unavailable = { false }) {
                val reads = TaughtTemplate.matcher(
                    listOf(TaughtTemplate.Stored(0, capture.sourcePackage, derived.pattern, shown.direction, 0)),
                )
                val unread = Databases.userRejectRuleDao(context).unreadOf(capture.sourcePackage)
                    .filter { reads.match(it.sourcePackage, it.title, it.text, it.bigText) is MatchOutcome.Matched }
                    .map { it.id }
                Databases.userTemplateRuleDao(context).teach(
                    UserTemplateRule(
                        sourcePackage = capture.sourcePackage,
                        pattern = derived.pattern,
                        direction = shown.direction,
                        hasMerchantGroup = derived.hasMerchant,
                        sampleText = capture.shownText(),
                        createdAt = now,
                    ),
                    // The capture taught on, whatever the matcher said: its own
                    // rule not reading it is a derivation bug, and stage two
                    // returns it to the list if so.
                    unread = (unread + capture.id).distinct(),
                )
                true
            }
            if (!saved) return@withLock
            // Unguarded, it would kill the process on a tap; a capture left at
            // NEW is still read on stage two's next run, whatever starts it.
            runCatching { runStageTwo(context) }
            _state.update { it.copy(saved = true) }
        }
    }

    private suspend fun read(): TeachState =
        guarded("The notification to teach cannot be read", unavailable = {
            TeachState(loaded = true, storageUnavailable = true)
        }) {
            val capture = Databases.userRejectRuleDao(context).unreadById(captureId)
                ?.let { it.copy(label = Databases.captureSourceDao(context).byPackage(it.sourcePackage)?.label) }
            TeachState(
                loaded = true,
                capture = capture,
                selection = capture?.let { TeachSelection(TaughtTemplate.words(it.title, it.text, it.bigText)) },
            )
        }

    /** Guarded as every database call in this module is; see `LearnedViewModel.delete`. */
    private suspend fun <T> guarded(what: String, unavailable: () -> T, block: suspend () -> T): T =
        CaptureStorage.guarded(context, what = what, unavailable = unavailable, damageStopsCapture = false, block = block)
}
