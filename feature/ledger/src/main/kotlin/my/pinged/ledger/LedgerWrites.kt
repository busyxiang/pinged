package my.pinged.ledger

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import my.pinged.capture.CaptureStorage
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferState

/**
 * What a screen's holder that writes the ledger on a tap shares with the
 * others (`ReviewInbox`, `ManualEntries`): whether a restore or a
 * delete-everything is running, which refuses the write, and whether the
 * ledger opened on the last guarded touch.
 *
 * In `my.pinged.ledger` rather than beside either holder, for the placement
 * rule `AppViewModelFactory` gives.
 */
internal class LedgerWrites(
    private val context: () -> Context,
    scope: CoroutineScope,
    private val transfers: StateFlow<TransferState>,
) {
    /** The database would not open on the last guarded touch. */
    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    /** The restore or delete-everything running, while one is: what a screen draws. */
    val busy: StateFlow<Operation?> = transfers
        .map { it.replacingLedger }
        .stateIn(scope, SharingStarted.Eagerly, transfers.value.replacingLedger)

    /**
     * Whether a restore or a delete-everything is running now: what a write
     * asks before its guard. Read off [transfers], not [busy], which is a
     * collector's copy and can lag the operation's claim by a dispatch; and
     * asked before the guard, not left to the gate, because a gated write
     * through `CaptureStorage.guarded` reads as storage unavailable, which
     * raises the capture-stopped banner over an operation the user started.
     */
    val replacing: Boolean get() = transfers.value.replacingLedger != null

    /**
     * [block] under `CaptureStorage.guarded`, which leases the instance; a
     * database that will not open sets [storageUnavailable] and answers
     * [unavailable]. Damage never stops capture from here: a tap on a screen
     * is not stage one.
     */
    suspend fun <T> guarded(what: String, unavailable: T, block: suspend () -> T): T =
        CaptureStorage.guarded(
            context(),
            what = what,
            unavailable = {
                _storageUnavailable.value = true
                unavailable
            },
            damageStopsCapture = false,
            block = block,
        )

    /** The ledger opened after all: clears [storageUnavailable]. */
    fun opened() {
        _storageUnavailable.value = false
    }
}

/** What a screen cannot do while a restore or delete-everything runs, in [busyLine]. */
internal enum class Withheld(val word: String) {
    /** The review inbox's answers. */
    ANSWERED("ANSWERED"),

    /** The manual entry screen's Save and Delete (#108). */
    SAVED("SAVED"),
}

/**
 * The line a [busy] operation draws over a screen that refuses writes while
 * it runs: `RESTORING A BACKUP · NOTHING CAN BE ANSWERED UNTIL IT FINISHES`
 * over the review cards, `... SAVED ...` over the entry screen.
 */
internal fun busyLine(busy: Operation, withheld: Withheld): String {
    val doing = when (busy) {
        Operation.RESTORE -> "RESTORING A BACKUP"
        Operation.DELETE -> "DELETING EVERYTHING"
        else -> busy.doing.uppercase(Locale.ROOT)
    }
    return "$doing · NOTHING CAN BE ${withheld.word} UNTIL IT FINISHES"
}
