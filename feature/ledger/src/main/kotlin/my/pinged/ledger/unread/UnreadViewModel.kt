package my.pinged.ledger.unread

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import my.pinged.ledger.LedgerBusy
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferState
import my.pinged.ledger.settings.Transfers
import my.pinged.capture.GuardedRunner
import my.pinged.data.dao.UnreadCapture
import my.pinged.read.ReadRunner
import my.pinged.read.ReadState
import my.pinged.read.ScreenRead

/** What the unread list draws. */
data class UnreadState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    val items: List<UnreadCapture> = emptyList(),
    /** Whether a page past [items] may hold more; false once one came back short. */
    val more: Boolean = false,
    /** Spec 11.1: the database could not be opened, which is not "nothing unread". */
    val storageUnavailable: Boolean = false,
    /** A ledger replacement turned the read away (#328): drawn as `REPLACING_YOUR_DATA`, never with [storageUnavailable]. */
    val replacing: Boolean = false,
)

/** What one read of [UnreadViewModel.read] found: the cards, and whether a page past them may hold more. */
internal data class UnreadPage(val items: List<UnreadCapture>, val more: Boolean)

/**
 * What the screen draws for [this] read: an unreadable ledger draws no cards,
 * whatever the last read held, as `LearnedViewModel`'s does.
 */
internal fun ReadState<Int, UnreadPage>.toState(): UnreadState {
    if (replacing) return UnreadState(loaded = true, replacing = true)
    if (unavailable) return UnreadState(loaded = true, storageUnavailable = true)
    val page = answer ?: return UnreadState()
    return UnreadState(loaded = true, items = page.items, more = page.more)
}

/**
 * Spec 5.6's unread list: read a page at a time, newest first, and spec 5.7's
 * "never a transaction".
 *
 * **The question is how many cards to show**, `:core:read`'s [ScreenRead]
 * (#296), and [loadMore] asks for one page more. So a re-read on resume, on
 * `Databases.rewrites` or after a dismissal is at least as long as what is
 * showing and never shortens the list, and a page more is read in the same
 * turn as a write, from the top, in one snapshot: a card dismissed meanwhile
 * can neither repeat nor be skipped at the seam between two pages. The cost is
 * that a page more reads every card already shown again, as a resume does.
 */
class UnreadViewModel internal constructor(
    app: Application,
    runner: ReadRunner,
    transfers: StateFlow<TransferState> = Transfers.state,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    private val gate = LedgerBusy(viewModelScope, transfers)

    /** The ledger replacement running, while one is: the screen disables its writes and says so (#331). */
    val busy: StateFlow<Operation?> = gate.busy

    /**
     * The list from its top, as long as the question says, newest first.
     * `UnreadScreen` reads it on resume and on `Databases.rewrites` through
     * `rememberReadState`.
     */
    internal val read: ScreenRead<Int, UnreadPage> =
        ScreenRead(viewModelScope, runner, "The notifications Pinged could not read cannot be read", initial = PAGE) { db, limit ->
            val items = db.userRejectRuleDao().unreadPage(Long.MAX_VALUE, Long.MAX_VALUE, limit)
            UnreadPage(items, more = items.size == limit)
        }

    /**
     * One page more than the question holds. Nothing while the last read came
     * back short, none has landed, or the question has already grown and its
     * read is still in flight: the card that asked for a page is the last of
     * a list that is about to be longer.
     */
    fun loadMore(): Job = viewModelScope.launch {
        val held = read.state.value
        val asked = read.question.value
        if (held.answered?.question != asked || held.answer?.more != true) return@launch
        read.ask(asked + PAGE).join()
    }

    /**
     * Spec 5.7: hides [capture] and every unread capture of its shape from its
     * app. A refused write leaves the list as it was, and the read after it
     * draws that. Nothing is written while the ledger is being replaced ([busy]).
     */
    fun neverATransaction(capture: UnreadCapture): Job = gate.unlessReplacing {
        viewModelScope.launch {
            read.write("The notification could not be marked as never a transaction") { db ->
                db.userRejectRuleDao().neverATransaction(capture.id, now = System.currentTimeMillis())
            }.await()
        }
    }

    private companion object {
        /** Cards per read. A page is a few screens of cards, each a whole notification. */
        const val PAGE = 50
    }
}
