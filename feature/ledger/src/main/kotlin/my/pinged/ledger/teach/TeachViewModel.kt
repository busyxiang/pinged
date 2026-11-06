package my.pinged.ledger.teach

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.pinged.capture.GuardedRunner
import my.pinged.capture.ParseWorker
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.UserTemplateRule
import my.pinged.ledger.refused
import my.pinged.parse.Direction
import my.pinged.parse.MatchOutcome
import my.pinged.parse.TaughtTemplate
import my.pinged.read.LedgerAccess
import my.pinged.read.LedgerReplacement
import my.pinged.read.LedgerReplacements
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner
import my.pinged.read.ScreenRead

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
    /** What the screen may do with the ledger: draw the capture, withhold Save, or say why it cannot (#376). */
    val ledger: LedgerAccess = LedgerAccess.Readable,
)

/**
 * Spec 5.8's Teach screen for unread capture [captureId]: two taps, a
 * direction, and "Save this rule".
 *
 * Saving writes the `user_template_rule` and hands back to stage two every
 * unread capture of the app the rule reads, the one taught on among them, so
 * each becomes a transaction -- held for review, as its first three are --
 * rather than waiting for a message that may never come again.
 *
 * The capture is `:core:read`'s [ScreenRead] (#296) and the save its `write`
 * (#376), so the claim of a ledger replacement refuses the save before the
 * runner, and the taps are kept for a save once it finishes.
 */
class TeachViewModel internal constructor(
    app: Application,
    private val captureId: Long,
    /** Stage two, asked to run once a save has requeued its captures. A test records it instead. */
    private val runStageTwo: (Context) -> Unit,
    /** How the capture is read and the rule written; a JVM test passes `ScriptedRunner`. */
    runner: ReadRunner,
    replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
) : AndroidViewModel(app) {
    constructor(app: Application, captureId: Long, runStageTwo: (Context) -> Unit = ParseWorker::enqueue) :
        this(app, captureId, runStageTwo, GuardedRunner(app))

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    /** The capture taught on, with its source's label; null once it has left the unread list. */
    internal val read: ScreenRead<Unit, UnreadCapture?> =
        ScreenRead.fixed(viewModelScope, runner, "The notification to teach cannot be read", replacement) { db -> captured(db) }

    /** The taps, and the capture they were made on: a capture read again as another is tapped afresh. */
    private var taps: Pair<UnreadCapture, TeachSelection>? = null
    private var direction = Direction.EXPENSE

    /**
     * Set while a save is asked for and after it lands: the state shown is
     * held as it was, so the save's read-back, which finds the capture gone
     * from the unread list, is not drawn as gone before the screen leaves.
     */
    private var held = false

    private val _state = MutableStateFlow(TeachState())
    val state: StateFlow<TeachState> = _state.asStateFlow()

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { read.state.collect { publish() } }
        read.refresh()
    }

    fun tap(index: Int) = select { it.tap(index) }

    fun noMerchant() = select { it.noMerchant() }

    fun direction(direction: Direction) {
        this.direction = direction
        publish()
    }

    /**
     * Saves what the screen shows, if both taps are made. A refused write
     * leaves the screen as it was, so the user can try again; so does a
     * ledger replacement, which [read] refuses, and the taps are kept for a
     * save once it finishes.
     */
    fun save(now: Long = System.currentTimeMillis()): Job = viewModelScope.launch { saveShown(now) }

    private fun select(change: (TeachSelection) -> TeachSelection) {
        val shown = _state.value
        val capture = shown.capture ?: return
        val selection = shown.selection ?: return
        taps = capture to change(selection)
        publish()
    }

    /** [state] from [read]'s last answer and the taps; nothing while [held]. */
    private fun publish() {
        if (held) return
        val read = read.state.value
        val ledger = read.ledger
        _state.value = when {
            ledger.refused -> TeachState(loaded = true, direction = direction, ledger = ledger)
            read.answered == null -> TeachState(direction = direction, ledger = ledger)
            else -> {
                val capture = read.answer
                val selection = capture?.let { c ->
                    taps?.takeIf { it.first == c }?.second ?: TeachSelection(TaughtTemplate.words(c.title, c.text, c.bigText))
                }
                TeachState(loaded = true, capture = capture, selection = selection, direction = direction, ledger = ledger)
            }
        }
    }

    private suspend fun saveShown(now: Long) {
        val shown = _state.value
        val capture = shown.capture ?: return
        val selection = shown.selection?.takeIf { it.step == TeachSelection.Step.READY } ?: return
        val derived = selection.derived() ?: return
        held = true
        val saved = read.write("The taught message could not be saved") { db ->
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
        }.await()
        if (saved !is Outcome.Done) {
            held = false
            publish()
            return
        }
        // Unguarded, it would kill the process on a tap; a capture left at
        // NEW is still read on stage two's next run, whatever starts it.
        runCatching { runStageTwo(context) }
        _state.update { it.copy(saved = true) }
    }

    private fun captured(db: PingedDatabase): UnreadCapture? =
        db.userRejectRuleDao().unreadById(captureId)
            ?.let { it.copy(label = db.captureSourceDao().byPackage(it.sourcePackage)?.label) }
}
