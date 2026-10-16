package my.pinged.ledger.unread

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
import my.pinged.data.Databases
import my.pinged.data.dao.UnreadCapture

/** What the unread list draws. */
data class UnreadState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    val items: List<UnreadCapture> = emptyList(),
    /** Whether a page past [items] may hold more; false once one came back short. */
    val more: Boolean = false,
    /** Spec 11.1: the database could not be opened, which is not "nothing unread". */
    val storageUnavailable: Boolean = false,
)

/**
 * Spec 5.6's unread list: read a page at a time, newest first, and spec 5.7's
 * "never a transaction".
 *
 * Every read and write takes [turn], as `LearnedViewModel`'s do, so the list
 * never draws a state older than a dismissal that landed.
 */
class UnreadViewModel(app: Application) : AndroidViewModel(app) {

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    private val turn = Mutex()
    private val _state = MutableStateFlow(UnreadState())
    val state: StateFlow<UnreadState> = _state.asStateFlow()

    /** The list from its top, at least as long as what is showing, so a refresh does not shorten it. */
    fun refresh(): Job = viewModelScope.launch {
        turn.withLock { _state.value = read(limit = maxOf(PAGE, _state.value.items.size)) }
    }

    /** The next page, after the last card showing. */
    fun loadMore(): Job = viewModelScope.launch {
        turn.withLock {
            val shown = _state.value
            val last = shown.items.lastOrNull()
            if (!shown.more || last == null) return@withLock
            val page = guarded("More notifications cannot be read", unavailable = { null }) {
                Databases.userRejectRuleDao(context).unreadPage(last.postedAt, last.id, PAGE)
            } ?: return@withLock
            _state.value = shown.copy(items = shown.items + page, more = page.size == PAGE)
        }
    }

    /**
     * Spec 5.7: hides [capture] and every unread capture of its shape from its
     * app. A refused write leaves the list as it was, and the read after it
     * draws that.
     */
    fun neverATransaction(capture: UnreadCapture): Job = viewModelScope.launch {
        turn.withLock {
            guarded("The notification could not be marked as never a transaction", unavailable = { 0 }) {
                Databases.userRejectRuleDao(context).neverATransaction(capture.id, now = System.currentTimeMillis())
            }
            _state.value = read(limit = maxOf(PAGE, _state.value.items.size))
        }
    }

    private suspend fun read(limit: Int): UnreadState =
        guarded("The notifications Pinged could not read cannot be read", unavailable = {
            UnreadState(loaded = true, storageUnavailable = true)
        }) {
            val items = Databases.userRejectRuleDao(context).unreadPage(Long.MAX_VALUE, Long.MAX_VALUE, limit)
            UnreadState(loaded = true, items = items, more = items.size == limit)
        }

    /** Guarded as every database call in this module is; see `LearnedViewModel.delete`. */
    private suspend fun <T> guarded(what: String, unavailable: () -> T, block: suspend () -> T): T =
        CaptureStorage.guarded(context, what = what, unavailable = unavailable, damageStopsCapture = false, block = block)

    private companion object {
        /** Cards per read. A page is a few screens of cards, each a whole notification. */
        const val PAGE = 50
    }
}
