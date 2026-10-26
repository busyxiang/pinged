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
import my.pinged.capture.GuardedRunner
import my.pinged.capture.ParseWorker
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.UserTemplateRule
import my.pinged.ledger.LedgerBusy
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferState
import my.pinged.ledger.settings.Transfers
import my.pinged.parse.Direction
import my.pinged.parse.MatchOutcome
import my.pinged.parse.TaughtTemplate
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner

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
    /** A ledger replacement turned the read away (#328): drawn as `REPLACING_YOUR_DATA`, never with [storageUnavailable]. */
    val replacing: Boolean = false,
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
class TeachViewModel internal constructor(
    app: Application,
    private val captureId: Long,
    /** Stage two, asked to run once a save has requeued its captures. A test records it instead. */
    private val runStageTwo: (Context) -> Unit,
    /** How the capture is read and the rule written; a JVM test passes `ScriptedRunner`. */
    private val runner: ReadRunner,
    transfers: StateFlow<TransferState> = Transfers.state,
) : AndroidViewModel(app) {
    constructor(app: Application, captureId: Long, runStageTwo: (Context) -> Unit = ParseWorker::enqueue) :
        this(app, captureId, runStageTwo, GuardedRunner(app))

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    private val gate = LedgerBusy(viewModelScope, transfers)

    /** The ledger replacement running, while one is: the screen disables Save and says so (#331). */
    val busy: StateFlow<Operation?> = gate.busy

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
     * leaves the screen as it was, so the user can try again; so does a
     * ledger replacement ([busy]), during which nothing is asked of the
     * ledger, and the taps are kept for a save once it finishes.
     */
    fun save(now: Long = System.currentTimeMillis()): Job = gate.unlessReplacing {
        viewModelScope.launch { turn.withLock { saveShown(now) } }
    }

    private suspend fun saveShown(now: Long) {
        val shown = _state.value
        val capture = shown.capture ?: return
        val selection = shown.selection?.takeIf { it.step == TeachSelection.Step.READY } ?: return
        val derived = selection.derived() ?: return
        val saved = runner.write("The taught message could not be saved") { db ->
            val reads = TaughtTemplate.matcher(
                listOf(TaughtTemplate.Stored(0, capture.sourcePackage, derived.pattern, shown.direction, 0)),
            )
            val unread = db.userRejectRuleDao().unreadOf(capture.sourcePackage)
                .filter { reads.match(it.sourcePackage, it.title, it.text, it.bigText) is MatchOutcome.Matched }
                .map { it.id }
            db.userTemplateRuleDao().teach(
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
        }
        if (saved !is Outcome.Done) return
        // Unguarded, it would kill the process on a tap; a capture left at
        // NEW is still read on stage two's next run, whatever starts it.
        runCatching { runStageTwo(context) }
        _state.update { it.copy(saved = true) }
    }

    private suspend fun read(): TeachState =
        when (val read = runner.read("The notification to teach cannot be read") { db -> captured(db) }) {
            is Outcome.Done -> TeachState(
                loaded = true,
                capture = read.value,
                selection = read.value?.let { TeachSelection(TaughtTemplate.words(it.title, it.text, it.bigText)) },
            )
            Outcome.Refused -> TeachState(loaded = true, storageUnavailable = true)
            Outcome.Replacing -> TeachState(loaded = true, replacing = true)
        }

    private fun captured(db: PingedDatabase): UnreadCapture? =
        db.userRejectRuleDao().unreadById(captureId)
            ?.let { it.copy(label = db.captureSourceDao().byPackage(it.sourcePackage)?.label) }
}
