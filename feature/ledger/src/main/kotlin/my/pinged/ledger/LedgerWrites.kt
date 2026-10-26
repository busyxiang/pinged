package my.pinged.ledger

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Whether a ledger replacement -- a restore or a delete-everything -- is
 * running, which every holder that writes on a tap refuses to write through,
 * from the claim (#331), and which its screen draws as [busyLine].
 *
 * In `my.pinged.ledger` rather than beside either holder, for the placement
 * rule `AppViewModelFactory` gives.
 */
internal class LedgerBusy(scope: CoroutineScope, private val transfers: StateFlow<TransferState>) {
    /** The restore or delete-everything running, while one is: what a screen draws. */
    val busy: StateFlow<Operation?> = transfers
        .map { it.replacingLedger }
        .stateIn(scope, SharingStarted.Eagerly, transfers.value.replacingLedger)

    /**
     * Whether a restore or a delete-everything is running now: what a write
     * asks before its guard. Read off [transfers], not [busy], which is a
     * collector's copy and can lag the operation's claim by a dispatch; and
     * asked before the guard, not left to the gate, because a gated write
     * through `CaptureStorage.guarded` is refused, which draws the screen's
     * own notice over an operation the user started. Not the capture banner:
     * a screen's call passes `damageStopsCapture = false` (`GateRefusalTest`).
     */
    val replacing: Boolean get() = transfers.value.replacingLedger != null

    /**
     * [write], unless the ledger is [replacing]: then nothing is asked of the
     * ledger, so the screen is not raised `unavailable` either, and the `Job`
     * returned is already finished.
     */
    fun unlessReplacing(write: () -> Job): Job = if (replacing) Job().apply { complete() } else write()
}

/**
 * What the manual entry screen's holder shares with [LedgerBusy]: whether the
 * ledger opened on the last guarded touch. The review inbox reads through
 * `:core:read`, which keeps that flag itself (#296), and uses [LedgerBusy] alone.
 */
internal class LedgerWrites(
    private val context: () -> Context,
    scope: CoroutineScope,
    transfers: StateFlow<TransferState>,
) {
    private val gate = LedgerBusy(scope, transfers)

    /** The database would not open on the last guarded touch. */
    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    /** See [LedgerBusy.busy]. */
    val busy: StateFlow<Operation?> get() = gate.busy

    /** See [LedgerBusy.replacing]. */
    val replacing: Boolean get() = gate.replacing

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

    /**
     * Every other write on a tap (#331): the category chooser's Save, the
     * merchant sheet's, Detail's and the Settings sub-screens'.
     */
    CHANGED("CHANGED"),
}

/**
 * The line a [busy] operation draws over a screen that refuses writes while
 * it runs: `RESTORING A BACKUP · NOTHING CAN BE ANSWERED UNTIL IT FINISHES`
 * over the review cards, `... SAVED ...` over the entry screen, and
 * `... CHANGED ...` everywhere else ([ChangedLine]).
 */
internal fun busyLine(busy: Operation, withheld: Withheld): String {
    val doing = when (busy) {
        Operation.RESTORE -> "RESTORING A BACKUP"
        Operation.DELETE -> "DELETING EVERYTHING"
        else -> busy.doing.uppercase(Locale.ROOT)
    }
    return "$doing · NOTHING CAN BE ${withheld.word} UNTIL IT FINISHES"
}
