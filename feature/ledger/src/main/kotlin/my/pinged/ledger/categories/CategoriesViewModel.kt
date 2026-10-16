package my.pinged.ledger.categories

import android.app.Application
import android.content.Context
import android.util.Log
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
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.CategoryInUseException
import my.pinged.data.dao.CategoryUse

/** What the Categories list draws. */
data class CategoriesState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    val items: List<CategoryUse> = emptyList(),
    /** Spec 11.1: the database could not be opened, which is not "no categories". */
    val storageUnavailable: Boolean = false,
)

/**
 * Spec 4's category editing (#155): the list, add, rename and re-icon, and
 * delete when unused.
 *
 * A write and the read after it take [turn], so the list never draws a state
 * older than a write that landed, as `LearnedViewModel` does.
 *
 * **A refused write is the read after it, not an error.** The sheet says a
 * clash or a use before Save is tapped; the DAO refuses again inside its own
 * transaction, which is only reached when the table moved in between, and
 * then the list redraws from what is there. The refusals caught are the
 * DAO's: [IllegalArgumentException], which `CategoryDao` raises for a taken,
 * blank or over-long name, a protected or missing category and a blank icon,
 * and [CategoryInUseException] for a delete something now uses. The sheet
 * never sends a blank icon, so that one would be a programming error caught
 * as a refusal; it is logged, not hidden.
 */
class CategoriesViewModel(app: Application) : AndroidViewModel(app) {

    /** A getter, for the reason `SourcesViewModel.context` gives. */
    private val context: Context get() = getApplication<Application>()

    private val turn = Mutex()
    private val _state = MutableStateFlow(CategoriesState())
    val state: StateFlow<CategoriesState> = _state.asStateFlow()

    fun refresh(): Job = viewModelScope.launch { turn.withLock { _state.value = read() } }

    fun add(name: String, iconKey: String): Job = write("The category could not be added") { it.add(name, iconKey) }

    fun edit(categoryId: Long, name: String, iconKey: String): Job =
        write("The category could not be changed") { it.edit(categoryId, name, iconKey) }

    fun delete(categoryId: Long): Job = write("The category could not be deleted") { it.deleteIfUnused(categoryId) }

    private fun write(what: String, change: (CategoryDao) -> Unit): Job = viewModelScope.launch {
        turn.withLock {
            // Guarded as every write in this module is: `viewModelScope` has
            // no handler, and an unguarded open in spec 11.1's state kills the
            // process on a tap.
            CaptureStorage.guarded(context, what = what, unavailable = { }, damageStopsCapture = false) {
                try {
                    change(Databases.categoryDao(context))
                } catch (refused: IllegalArgumentException) {
                    Log.i(TAG, "$what: ${refused.message}")
                } catch (refused: CategoryInUseException) {
                    Log.i(TAG, "$what: ${refused.message}")
                }
            }
            _state.value = read()
        }
    }

    private suspend fun read(): CategoriesState =
        CaptureStorage.guarded(
            context,
            what = "Categories cannot be read",
            unavailable = { CategoriesState(loaded = true, storageUnavailable = true) },
            damageStopsCapture = false,
        ) {
            CategoriesState(loaded = true, items = Databases.categoryDao(context).uses())
        }

    private companion object {
        const val TAG = "Categories"
    }
}
