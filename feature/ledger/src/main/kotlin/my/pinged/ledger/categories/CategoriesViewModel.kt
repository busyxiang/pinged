package my.pinged.ledger.categories

import android.app.Application
import android.util.Log
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
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.CategoryInUseException
import my.pinged.data.dao.CategoryUse
import my.pinged.read.ReadRunner
import my.pinged.read.ReadState
import my.pinged.read.ScreenRead

/** What the Categories list draws. */
data class CategoriesState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    val items: List<CategoryUse> = emptyList(),
    /** Spec 11.1: the database could not be opened, which is not "no categories". */
    val storageUnavailable: Boolean = false,
    /** A ledger replacement turned the read away (#328): drawn as `REPLACING_YOUR_DATA`, never with [storageUnavailable]. */
    val replacing: Boolean = false,
)

/**
 * What the screen draws for [this] read: an unreadable ledger draws no
 * categories, whatever the last read held, so a sheet open over one closes.
 */
internal fun ReadState<Unit, List<CategoryUse>>.toState(): CategoriesState {
    if (replacing) return CategoriesState(loaded = true, replacing = true)
    if (unavailable) return CategoriesState(loaded = true, storageUnavailable = true)
    val items = answer ?: return CategoriesState()
    return CategoriesState(loaded = true, items = items)
}

/**
 * Spec 4's category editing (#155): the list, add, rename and re-icon, and
 * delete when unused.
 *
 * The list is `:core:read`'s [ScreenRead] (#296) and each edit is its `write`,
 * so the list never draws a state older than a write that landed.
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
class CategoriesViewModel internal constructor(
    app: Application,
    runner: ReadRunner,
    transfers: StateFlow<TransferState> = Transfers.state,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    private val gate = LedgerBusy(viewModelScope, transfers)

    /** The ledger replacement running, while one is: the screen disables its writes and says so (#331). */
    val busy: StateFlow<Operation?> = gate.busy

    /**
     * Every category with what is filed there. `CategoriesScreen` reads it on
     * resume and on `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, List<CategoryUse>> =
        ScreenRead.fixed(viewModelScope, runner, "Categories cannot be read") { db -> db.categoryDao().uses() }

    fun add(name: String, iconKey: String): Job = write("The category could not be added") { it.add(name, iconKey) }

    fun edit(categoryId: Long, name: String, iconKey: String): Job =
        write("The category could not be changed") { it.edit(categoryId, name, iconKey) }

    fun delete(categoryId: Long): Job = write("The category could not be deleted") { it.deleteIfUnused(categoryId) }

    /** Refused outright while the ledger is being replaced, as [busy] says (#331). */
    private fun write(what: String, change: (CategoryDao) -> Unit): Job = gate.unlessReplacing {
        viewModelScope.launch {
            read.write(what) { db ->
                try {
                    change(db.categoryDao())
                } catch (refused: IllegalArgumentException) {
                    Log.i(TAG, "$what: ${refused.message}")
                } catch (refused: CategoryInUseException) {
                    Log.i(TAG, "$what: ${refused.message}")
                }
            }.await()
        }
    }

    private companion object {
        const val TAG = "Categories"
    }
}
