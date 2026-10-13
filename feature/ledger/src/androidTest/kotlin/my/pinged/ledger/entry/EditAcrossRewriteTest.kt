package my.pinged.ledger.entry

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.data.dao.ManualEdit
import my.pinged.data.entity.Txn
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Transfers
import my.pinged.ledger.transfer.exportBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An edit screen open across a delete-everything or a restore. Either can
 * give the entry's id to another payment -- a delete restarts ids, a restore
 * brings in another ledger's -- and an id is all the screen opened with, so
 * the entry is named by [EditTarget], its id and `created_at`: Save changes
 * and Delete never touch the payment that took the id, the screen closes
 * when its entry is gone, and stays open when a restore brought the same
 * payment back.
 */
@RunWith(AndroidJUnit4::class)
class EditAcrossRewriteTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewModels = mutableListOf<ManualEntryViewModel>()

    @After fun leaveNothingBehind() {
        // So no view model of this test reads on into the next one's.
        viewModels.forEach { it.viewModelScope.cancel() }
        runBlocking { withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } } }
        Transfers.forgetOutcome()
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun aCorrectionHeldOverADeleteEverythingNeverWritesThePaymentThatTookItsId() = runBlocking {
        val dao = context.freshLedger()
        val entries = ManualEntries(context = { context }, scope = scope)
        val first = dao.insert(manual(amountSen = 1_250L, note = "the entry being corrected"))
        val target = EditTarget(first, dao.byId(first)!!.createdAt)

        Transfers.deleteEverything(context, requester = Job()).await()
        val other = Databases.txnDao(context).insert(manual(amountSen = 800L, note = "typed after the delete", createdAt = target.createdAt + 1))
        assertEquals("precondition: the delete did not give the id to the next entry", first, other)
        val before = Databases.txnDao(context).byId(other)!!

        assertEquals("The entry's id read as the payment that took it", Loaded.Gone, entries.load(target.id, target.createdAt).await())
        assertFalse(
            "Save changes reported writing the payment that took the entry's id",
            entries.update(target, ManualDraft(amountSen = 9_900L, note = "corrected")).await(),
        )
        assertTrue(
            "Delete of an entry the delete-everything took did not count it gone",
            entries.delete(target).await(),
        )
        assertEquals("The correction or its delete changed the payment that took the id", before, Databases.txnDao(context).byId(other))

        // The statements' own guard, without `ManualEntries`' read in front of it.
        assertFalse(
            "TxnDao.editManualEntry wrote a row not created when the entry was",
            Databases.txnDao(context).editManualEntry(target.id, target.createdAt, ManualEdit(note = ManualEdit.Note("corrected")), target.createdAt + 2),
        )
        assertEquals("TxnDao.deleteManualEntry deleted a row not created when the entry was", 0, Databases.txnDao(context).deleteManualEntry(target.id, target.createdAt))
        assertEquals("The statements changed the payment that took the id", before, Databases.txnDao(context).byId(other))
    }

    @Test fun aDeleteEverythingClosesTheOpenCorrection() = runBlocking {
        val dao = context.freshLedger()
        val id = dao.insert(manual(amountSen = 1_250L))
        val vm = editing(SavedStateHandle(), id)
        vm.note("typed before the delete")

        Transfers.deleteEverything(context, requester = Job()).await()

        assertEquals(
            "The edit screen stayed open over an entry delete-everything took",
            true,
            withTimeoutOrNull(TIMEOUT) { vm.state.first { it.gone } }?.gone,
        )
    }

    @Test fun aDraftRestoredAfterTheIdChangedHandsClosesWithoutWriting() = runBlocking {
        val dao = context.freshLedger()
        val first = dao.insert(manual(amountSen = 1_250L))
        val handle = SavedStateHandle()
        editing(handle, first).note("typed before the process died")

        // The process dies; meanwhile a delete-everything runs and the next
        // entry takes the id. In-memory `Databases.rewrites` starts again at
        // zero, so only the saved target can tell the two apart.
        Transfers.deleteEverything(context, requester = Job()).await()
        val other = Databases.txnDao(context).insert(manual(amountSen = 800L, createdAt = System.currentTimeMillis() + 1))
        assertEquals("precondition: the delete did not give the id to the next entry", first, other)
        val before = Databases.txnDao(context).byId(other)!!

        val restored = ManualEntryViewModel(app, handle, target = null, preselection = { NEVER }).also(viewModels::add)
        val closed = withTimeoutOrNull(TIMEOUT) { restored.state.first { it.gone } }
        restored.save().join()

        assertEquals("A draft restored after its entry's id changed hands opened over the other payment", true, closed?.gone)
        assertEquals("A restored draft wrote the payment that took its entry's id", before, Databases.txnDao(context).byId(other))
    }

    @Test fun aRestoreThatBringsTheSamePaymentBackKeepsTheCorrection() = runBlocking {
        val dao = context.freshLedger()
        val id = dao.insert(manual(amountSen = 1_250L))
        val backup = exportBytes(Databases.shared(context))
        val vm = editing(SavedStateHandle(), id)
        vm.note("typed across the restore")
        withTimeout(TIMEOUT) { vm.state.first { it.draft.note == "typed across the restore" } }

        Transfers.restoreFrom(context, open = { ByteArrayInputStream(backup) }, requester = Job()).await()
        // Past the restore's re-read: an answer the restore changed would have landed by now.
        withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } }
        assertEquals(
            "The edit screen closed over the same payment a restore brought back",
            null,
            withTimeoutOrNull(ANSWER) { vm.state.first { it.gone } },
        )
        assertEquals("The restore lost what was typed", "typed across the restore", vm.state.value.draft.note)

        vm.save().join()
        withTimeout(TIMEOUT) { vm.state.first { it.saved } }
        assertEquals("The correction did not reach the payment the restore kept", "typed across the restore", Databases.txnDao(context).byId(id)!!.note)
    }

    /** The view model in edit mode on [id], over [handle], once its draft has loaded. */
    private suspend fun editing(handle: SavedStateHandle, id: Long): ManualEntryViewModel {
        val target = EditTarget(id, Databases.txnDao(context).byId(id)!!.createdAt)
        val vm = ManualEntryViewModel(app, handle, target, preselection = { NEVER }).also(viewModels::add)
        withTimeout(TIMEOUT) { vm.state.first { it.loaded != null } }
        return vm
    }

    /** An entry typed by hand, created at [createdAt]. */
    private fun manual(amountSen: Long, note: String? = null, createdAt: Long = System.currentTimeMillis()): Txn =
        ledgerTxn(amountSen = amountSen, occurredAt = System.currentTimeMillis() - MINUTE, rawCaptureId = null)
            .copy(sourcePackage = null, sourceLabel = null, note = note, categoryId = uncategorized(), createdAt = createdAt, updatedAt = createdAt)

    private fun uncategorized(): Long = Databases.categoryDao(context).requireUncategorizedId()

    private companion object {
        const val MINUTE = 60_000L

        /** A state already decided, a dispatch behind the holder. */
        const val ANSWER = 2_000L

        /** A merchant lookup that never answers: no draft here has a merchant. */
        val NEVER = CompletableDeferred<Long?>()
    }
}
