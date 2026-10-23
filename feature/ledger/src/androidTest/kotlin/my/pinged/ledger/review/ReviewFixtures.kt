package my.pinged.ledger.review

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.refreshed
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.ledgerTxn

private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** A view model over the real database, its holder read once and settled. */
internal suspend fun settledReview(): ReviewViewModel {
    val viewModel = ReviewViewModel(context.applicationContext as Application)
    withTimeout(TIMEOUT) { viewModel.inbox.read.refreshed() }
    return viewModel
}

/** [settledReview]'s holder, for a test that drives the holder alone. */
internal suspend fun inbox(): ReviewInbox = settledReview().inbox

/** A row awaiting review for [reason], as the gate holds one. */
internal fun pending(
    reason: PendingReason,
    at: Long,
    amountSen: Long = 65_000L,
    categoryId: Long = uncategorized(),
): Txn = ledgerTxn(amountSen = amountSen, occurredAt = at, categoryId = categoryId, state = TxnState.PENDING, pendingReason = reason)

/** An entry typed by hand: no capture, no package. */
internal fun manual(amountSen: Long, occurredAt: Long): Txn =
    ledgerTxn(amountSen = amountSen, occurredAt = occurredAt, categoryId = uncategorized(), rawCaptureId = null)
        .copy(sourcePackage = null, sourceLabel = null)

internal fun uncategorized(): Long = Databases.categoryDao(context).requireUncategorizedId()

/** A write behind the holder's back: what another writer, such as a Corrections re-read, does. */
internal fun sql(statement: String) = Databases.shared(context).openHelper.writableDatabase.execSQL(statement)
