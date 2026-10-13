package my.pinged.ledger.entry

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.holdWhile
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen's holder across a restore and a delete-everything (#108),
 * against the real database: while one runs the screen says so, Save is off
 * and a save asked for anyway writes nothing, and the draft stays as typed;
 * once it ends, Save is on again and writes that draft, once.
 */
@RunWith(AndroidJUnit4::class)
class EntryGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application
    private val releases = mutableListOf<CountDownLatch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        releases.forEach { it.countDown() }
        runBlocking { withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } } }
        Transfers.forgetOutcome()
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun aRestoreHoldsTheDraftAndSaveWritesItOnceAfterwards() = runBlocking {
        val vm = typedDraft()

        // Held before it touches anything; a document that will not open then
        // ends it with the ledger as it was.
        holdWhile(Operation.RESTORE, busyOf(vm), releases) { requester, beforeStart ->
            Transfers.restoreFrom(context, open = { null }, requester = requester, beforeStart = beforeStart)
        }.use {
            refusedWhileHeld(vm, Operation.RESTORE)
        }

        writtenOnceAfterwards(vm, Operation.RESTORE)
    }

    @Test fun aDeleteHoldsTheDraftAndSaveWritesItOnceAfterwards() = runBlocking {
        val vm = typedDraft()

        // Held before it touches anything; the requester then goes away, so
        // the delete never starts.
        val requester = Job()
        holdWhile(Operation.DELETE, busyOf(vm), releases, requester) { r, beforeStart ->
            Transfers.deleteEverything(context, requester = r, beforeStart = beforeStart)
        }.use {
            refusedWhileHeld(vm, Operation.DELETE)
            requester.cancel()
        }

        writtenOnceAfterwards(vm, Operation.DELETE)
    }

    /** A holder on a fresh ledger with the whole draft typed in. */
    private suspend fun typedDraft(): ManualEntryViewModel {
        context.freshLedger()
        foodId = Databases.categoryDao(context).all().single { it.name == "Food & Drinks" }.id
        val vm = ManualEntryViewModel(app, SavedStateHandle(), target = null) { CompletableDeferred<Long?>(null) }
        vm.amount(1_250L)
        vm.merchant("Kopitiam")
        vm.note("teh tarik")
        vm.tapCategory(food())
        withTimeout(TIMEOUT) { vm.state.first { it.draft == typed() && it.canSave } }
        return vm
    }

    private suspend fun refusedWhileHeld(vm: ManualEntryViewModel, operation: Operation) {
        val held = vm.state.value
        assertEquals("The screen does not say the $operation is running", operation, held.busy)
        assertFalse("Save is on during the $operation", held.canSave)

        // As if the tap got through: the holder refuses on the spot.
        withTimeout(SAVE_BUDGET) { vm.save().join() }

        val after = withTimeout(TIMEOUT) { vm.state.first { !it.saving } }
        assertFalse("A save during the $operation reported itself saved", after.saved)
        assertEquals("The draft was not kept through the $operation", typed(), after.draft)
    }

    private suspend fun writtenOnceAfterwards(vm: ManualEntryViewModel, operation: Operation) {
        val dao = Databases.txnDao(context)
        assertEquals("A save during the $operation was written", 0, dao.countAll())
        assertEquals(
            "Save did not come back on once the $operation ended",
            true,
            withTimeoutOrNull(TIMEOUT) { vm.state.first { it.canSave } }?.canSave,
        )
        assertEquals("The draft changed by the end of the $operation", typed(), vm.state.value.draft)

        vm.save().join()
        vm.save().join()

        assertEquals("Save did not write after the $operation", true, withTimeoutOrNull(TIMEOUT) { vm.state.first { it.saved } }?.saved)
        val rows = dao.pageFrom(0L, 10)
        assertEquals("Not one row after the $operation", 1, rows.size)
        val row = rows.single()
        assertEquals(
            "The row is not the draft typed before the $operation",
            listOf<Any?>(1_250L, "Kopitiam", "teh tarik", food(), true, TxnState.COMMITTED, null),
            listOf(row.amountSen, row.merchantRaw, row.note, row.categoryId, row.userEdited, row.state, row.rawCaptureId),
        )
    }

    private fun typed() = ManualDraft(
        amountSen = 1_250L,
        merchant = "Kopitiam",
        note = "teh tarik",
        category = CategoryChoice(food(), ChosenBy.TAPPED),
    )

    /** Read once, before any hold, so nothing here reads the ledger during one. */
    private var foodId = 0L

    private fun food(): Long = foodId

    private fun busyOf(vm: ManualEntryViewModel) =
        vm.state.map { it.busy }.stateIn(scope, SharingStarted.Eagerly, vm.state.value.busy)

    private companion object {
        /** A save refused rather than queued returns at once; a queued one waits for the hold. */
        const val SAVE_BUDGET = 5_000L
    }
}
