package my.pinged.ledger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import my.pinged.ledger.settings.TransferState
import my.pinged.read.LedgerReplacement

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
    val busy: StateFlow<LedgerReplacement?> = transfers
        .map { it.replacement }
        .stateIn(scope, SharingStarted.Eagerly, transfers.value.replacement)

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

