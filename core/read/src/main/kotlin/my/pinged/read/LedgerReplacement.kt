package my.pinged.read

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A ledger replacement (`CONTEXT.md`): a restore or a delete-everything, from
 * the moment the user starts it until it finishes. What [ScreenRead] refuses
 * writes for, and what a screen names while it does ([LedgerAccess.Withholding]).
 */
enum class LedgerReplacement { RESTORE, DELETE }

/**
 * The claim (#376): the ledger replacement running in this process, or null.
 * `Transfers` publishes it as it claims a restore or a delete and clears it as
 * the operation ends, so it is up for the whole of an operation, including the
 * stretch before its gate, where a write would land and then be wiped.
 *
 * Here and not in `Transfers` because `:core:read` cannot see `:feature:ledger`,
 * and the claim is every [ScreenRead]'s: it is the default each one is built with.
 */
object LedgerReplacements {
    private val claim = MutableStateFlow<LedgerReplacement?>(null)

    /** The replacement running, while one is. */
    val current: StateFlow<LedgerReplacement?> = claim.asStateFlow()

    /** `Transfers` only: the replacement it has claimed, or null once that has ended. */
    fun publish(replacement: LedgerReplacement?) {
        claim.value = replacement
    }
}
