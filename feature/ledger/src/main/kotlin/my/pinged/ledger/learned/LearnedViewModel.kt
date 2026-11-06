package my.pinged.ledger.learned

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import my.pinged.ledger.LedgerBusy
import my.pinged.ledger.settings.TransferState
import my.pinged.ledger.settings.Transfers
import my.pinged.capture.Graph
import my.pinged.capture.GuardedRunner
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.RejectRuleRow
import my.pinged.data.dao.StandingAnswerRow
import my.pinged.data.dao.TaughtRuleRow
import my.pinged.read.LedgerReplacement
import my.pinged.read.LedgerAccess
import my.pinged.read.ReadRunner
import my.pinged.read.ReadState
import my.pinged.parse.ExclusionReason
import my.pinged.read.ScreenRead

/** What the taught-rules screen draws. */
data class LearnedState(
    /** False until the first read completes, so an empty list is not a claim. */
    val loaded: Boolean = false,
    val items: List<LearnedMerchant> = emptyList(),
    /** Spec 5.8's taught messages, newest first. */
    val messages: List<TaughtRuleRow> = emptyList(),
    /** Spec 5.7's "never a transaction" rules, newest first. */
    val ignored: List<RejectRuleRow> = emptyList(),
    /** Spec 7.3's standing answers, newest first. */
    val leftOut: List<LeftOut> = emptyList(),
    /** Spec 11.1: the database could not be opened, which is not "nothing taught". */
    val storageUnavailable: Boolean = false,
    /** A ledger replacement turned the read away (#328): drawn as `REPLACING_YOUR_DATA`, never with [storageUnavailable]. */
    val replacing: Boolean = false,
)

/** What one read of [LearnedViewModel.read] found, in one transaction. */
internal data class Taught(
    val items: List<LearnedMerchant>,
    val messages: List<TaughtRuleRow>,
    val ignored: List<RejectRuleRow>,
    val leftOut: List<LeftOut>,
)

/**
 * One standing answer as LEFT OUT words it: its [row], and the exclusion
 * its rule suggests in the loaded pack, by `Graph.suggestedExclusion` --
 * `TRANSFER` for a rule the pack no longer has, so the answer is still
 * listed and can be deleted.
 */
data class LeftOut(val row: StandingAnswerRow, val exclusion: ExclusionReason)

/**
 * What the screen draws for [this] read: an unreadable ledger draws no rules,
 * whatever the last read held, since a list is a claim the database no longer
 * supports.
 */
internal fun ReadState<Unit, Taught>.toState(): LearnedState {
    if (ledger == LedgerAccess.BeingReplaced) return LearnedState(loaded = true, replacing = true)
    if (ledger == LedgerAccess.Unreadable) return LearnedState(loaded = true, storageUnavailable = true)
    val taught = answer ?: return LearnedState()
    return LearnedState(
        loaded = true,
        items = taught.items,
        messages = taught.messages,
        ignored = taught.ignored,
        leftOut = taught.leftOut,
    )
}

/**
 * The list of learned rules (#50, spec #45 "Learned merchants list"), of
 * spec 5.7's user reject rules and 5.8's taught messages, and of spec 7.3's
 * standing answers: read, and delete.
 *
 * The read is `:core:read`'s [ScreenRead] (#296), and each delete is its
 * `write`, so the list never draws a state older than a delete that landed.
 * Delete is `MerchantIdentityDao.deleteLearnedOfIdentity` and nothing else: it
 * touches no `txn` row and re-files nothing. No delete is made while the
 * ledger is being replaced ([busy], #331).
 */
class LearnedViewModel internal constructor(
    app: Application,
    runner: ReadRunner,
    transfers: StateFlow<TransferState> = Transfers.state,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    private val gate = LedgerBusy(viewModelScope, transfers)

    /** The ledger replacement running, while one is: the screen disables its writes and says so (#331). */
    val busy: StateFlow<LedgerReplacement?> = gate.busy

    /**
     * The four lists, in one transaction. `LearnedScreen` reads it on resume
     * and on `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, Taught> =
        ScreenRead.fixed(viewModelScope, runner, "Learned merchants cannot be read") { db ->
            Taught(
                items = db.merchantIdentityDao().learnedMerchants(),
                messages = db.userTemplateRuleDao().rows(),
                ignored = db.userRejectRuleDao().rules(),
                leftOut = db.standingAnswerDao().rows().map { LeftOut(it, Graph.suggestedExclusion(it.sourcePackage, it.matchedRuleId)) },
            )
        }

    /**
     * A refused delete leaves the rule, and the read after it draws that. A
     * delete is its read's `write`: guarded, `viewModelScope` having no handler.
     */
    fun delete(merchant: LearnedMerchant): Job = gate.unlessReplacing {
        viewModelScope.launch {
            read.write("The learned merchant could not be deleted") { db ->
                db.merchantIdentityDao().deleteLearnedOfIdentity(merchant.identity)
            }.await()
        }
    }

    /**
     * Forget [rule]: `UserRejectRuleDao.delete`, which returns its captures to
     * the unread list (spec 5.7) and touches nothing else.
     */
    fun deleteIgnored(rule: RejectRuleRow): Job = gate.unlessReplacing {
        viewModelScope.launch {
            read.write("The ignored message could not be deleted") { db -> db.userRejectRuleDao().delete(rule.id) }.await()
        }
    }

    /**
     * Forget [rule]: `UserTemplateRuleDao.delete`, which leaves every
     * transaction it wrote as it is (spec 5.8).
     */
    fun deleteMessage(rule: TaughtRuleRow): Job = gate.unlessReplacing {
        viewModelScope.launch {
            read.write("The taught message could not be deleted") { db -> db.userTemplateRuleDao().delete(rule.id) }.await()
        }
    }

    /**
     * Forget [answer]: `StandingAnswerDao.delete`. Later transfers it would
     * have answered go back to review; no transaction changes (spec 7.3).
     */
    fun deleteLeftOut(answer: LeftOut): Job = gate.unlessReplacing {
        viewModelScope.launch {
            read.write("The standing answer could not be deleted") { db -> db.standingAnswerDao().delete(answer.row.id) }.await()
        }
    }
}
