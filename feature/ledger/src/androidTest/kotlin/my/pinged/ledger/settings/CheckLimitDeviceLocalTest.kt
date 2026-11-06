package my.pinged.ledger.settings

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import my.pinged.capture.CheckLimit
import my.pinged.data.Databases
import my.pinged.data.entity.PendingReason
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.review.NOON
import my.pinged.ledger.review.manual
import my.pinged.ledger.review.pending
import my.pinged.ledger.review.uncategorized
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.transfer.exportBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #362: the check limit is this phone's, and a change to it moves no money.
 * Saving one writes no `txn` row either way; a restore keeps the phone's limit,
 * whatever the limit was when the backup was made; delete everything puts it
 * back to RM500.
 */
@RunWith(AndroidJUnit4::class)
class CheckLimitDeviceLocalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application

    @After fun leaveNothingBehind() {
        runBlocking {
            withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } }
            CheckLimit.reset(context)
        }
        Transfers.forgetOutcome()
        context.discardTheDatabase()
    }

    private fun limitNow() = runBlocking { CheckLimit.sen(context) }

    @Test fun savingALimitEitherWayChangesNoRow() = runBlocking {
        val dao = context.freshLedger()
        val ids = listOf(
            dao.insert(ledgerTxn(amountSen = 80_000L, occurredAt = NOON, categoryId = uncategorized())),
            dao.insert(pending(PendingReason.OVER_THRESHOLD, at = NOON + 1, amountSen = 80_000L)),
            dao.insert(pending(PendingReason.RULE_REVIEW, at = NOON + 2, amountSen = 30_000L)),
        )
        val before = ids.map { dao.byId(it)!! }
        val settings = withContext(Dispatchers.Main) { SettingsViewModel(app) }

        settings.setCheckLimit(100_000L).join()
        assertEquals("precondition: the raise was not saved", 100_000L, limitNow())
        assertEquals("Raising the limit wrote a row: state, reason or updated_at moved", before, ids.map { dao.byId(it)!! })

        settings.setCheckLimit(20_000L).join()
        assertEquals("precondition: the lower was not saved", 20_000L, limitNow())
        assertEquals("Lowering the limit wrote a row: state, reason or updated_at moved", before, ids.map { dao.byId(it)!! })
        assertEquals("The screen does not draw the limit it saved", 20_000L, settings.state.value.checkLimitSen)
    }

    /** Manual entries, which need no capture behind them for the import's references to hold. */
    @Test fun aRestoreKeepsThisPhonesLimit() = runBlocking {
        val dao = context.freshLedger()
        val kept = dao.insert(manual(amountSen = 30_000L, occurredAt = NOON))
        CheckLimit.set(context, 200_000L)
        val backup = exportBytes(Databases.shared(context))
        CheckLimit.set(context, 10_000L)
        val notInTheBackup = dao.insert(manual(amountSen = 5_000L, occurredAt = NOON + 1))

        val answer = Transfers.restoreFrom(context, open = { ByteArrayInputStream(backup) }, requester = Job()).await()

        val job = (answer as? Transfers.Answer.Settled)?.outcome?.job
        assertTrue("precondition: the restore did not finish: $answer", job is TransferJob.Restored)
        assertNull("precondition: the restore did not replace the ledger", Databases.txnDao(context).byId(notInTheBackup))
        assertNotNull("precondition: the restore did not bring the backup's row", Databases.txnDao(context).byId(kept))
        assertEquals("The restore changed this phone's check limit", 10_000L, limitNow())
    }

    @Test fun deleteEverythingPutsTheLimitBackToRm500() = runBlocking {
        context.freshLedger()
        CheckLimit.set(context, 500_000L)

        val answer = Transfers.deleteEverything(context, requester = Job()).await()

        assertEquals("precondition: the delete did not finish", TransferJob.Deleted, (answer as? Transfers.Answer.Settled)?.outcome?.job)
        assertEquals("Delete everything left the check limit behind", CheckLimit.DEFAULT_SEN, limitNow())
    }
}
