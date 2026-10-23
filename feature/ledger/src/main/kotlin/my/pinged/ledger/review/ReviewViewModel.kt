package my.pinged.ledger.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import my.pinged.capture.GuardedRunner
import my.pinged.read.ReadRunner

/**
 * The review inbox screen's holder: owns a [ReviewInbox] on its own scope.
 *
 * Built from nothing but the `Application`, so a holder rebuilt after a
 * process death reads every card afresh. The `Review` key's focus is the
 * screen's, not this holder's.
 */
class ReviewViewModel internal constructor(app: Application, runner: ReadRunner) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GuardedRunner(app))

    val inbox = ReviewInbox(scope = viewModelScope, runner = runner)

    /** [answer] to [card], by the button the screen drew for it. */
    fun answer(card: ReviewCard, answer: ReviewAnswer): Job = when (answer) {
        ReviewAnswer.ACCEPT -> inbox.accept(card)
        ReviewAnswer.NOT_A_PAYMENT -> inbox.reject(card)
        ReviewAnswer.MERGE -> inbox.merge(card)
        ReviewAnswer.KEEP_BOTH -> inbox.keepBoth(card)
        ReviewAnswer.LEAVE_OUT -> inbox.leaveOut(card)
        ReviewAnswer.KEEP_AS_EXPENSE -> inbox.keepAsExpense(card)
    }
}
