package my.pinged.ledger.learned

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
import my.pinged.data.dao.LearnedMerchant

/** What the learned-merchants screen draws. */
data class LearnedState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    val items: List<LearnedMerchant> = emptyList(),
    /** Spec 11.1: the database could not be opened, which is not "nothing taught". */
    val storageUnavailable: Boolean = false,
)

/**
 * The list of learned rules (#50, spec #45 "Learned merchants list"): read,
 * and delete.
 *
 * A delete and the read after it take [turn], so the list never draws a state
 * older than a delete that landed. Delete is `MerchantRuleDao.deleteLearnedOfIdentity`
 * and nothing else: it touches no `txn` row and re-files nothing.
 */
class LearnedViewModel(app: Application) : AndroidViewModel(app) {

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    private val turn = Mutex()
    private val _state = MutableStateFlow(LearnedState())
    val state: StateFlow<LearnedState> = _state.asStateFlow()

    fun refresh(): Job = viewModelScope.launch { turn.withLock { _state.value = read() } }

    fun delete(merchant: LearnedMerchant): Job = viewModelScope.launch {
        turn.withLock {
            // Guarded as every write in this module is: `viewModelScope` has no
            // handler, and an unguarded open in spec 11.1's state kills the
            // process on a tap. A refused delete leaves the rule, and the read
            // after it draws that.
            CaptureStorage.guarded(
                context,
                what = "The learned merchant could not be deleted",
                unavailable = { 0 },
                damageStopsCapture = false,
            ) { Databases.merchantRuleDao(context).deleteLearnedOfIdentity(merchant.identity) }
            _state.value = read()
        }
    }

    private suspend fun read(): LearnedState =
        CaptureStorage.guarded(
            context,
            what = "Learned merchants cannot be read",
            unavailable = { LearnedState(loaded = true, storageUnavailable = true) },
            damageStopsCapture = false,
        ) {
            LearnedState(loaded = true, items = Databases.merchantRuleDao(context).learnedMerchants())
        }
}
