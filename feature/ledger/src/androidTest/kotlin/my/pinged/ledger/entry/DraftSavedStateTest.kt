package my.pinged.ledger.entry

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.holdWhile
import my.pinged.ledger.review.NOON
import my.pinged.ledger.review.manual
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole draft survives a process death (#108): what the entry screen's
 * view model wrote to its `SavedStateHandle`, carried through a parcelled
 * `Bundle` as the system carries it, is the draft a new view model starts
 * from -- the amount, the day, the chip and how it was chosen, the merchant,
 * the note and the entry being edited.
 *
 * The restored holder's pre-selection never answers, so what is asserted is
 * what was restored, not what a fresh lookup would choose.
 */
@RunWith(AndroidJUnit4::class)
class DraftSavedStateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application
    private val releases = mutableListOf<CountDownLatch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewModels = mutableListOf<ManualEntryViewModel>()

    @Before fun openTheLedger() {
        context.freshLedger()
    }

    @After fun leaveNothingBehind() {
        // So no view model of this test reads on into the next one's.
        viewModels.forEach { it.viewModelScope.cancel() }
        releases.forEach { it.countDown() }
        runBlocking { withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } } }
        Transfers.forgetOutcome()
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun aPreselectedDraftComesBackWhole() = runBlocking {
        val handle = SavedStateHandle()
        val day = LocalDate.now().minusDays(3)
        val vm = ManualEntryViewModel(app, handle, target = null) { CompletableDeferred(FOOD) }.also(viewModels::add)
        vm.amount(1_250L)
        vm.day(day)
        vm.merchant("Kopitiam")
        vm.note("teh tarik")
        val typed = withTimeout(TIMEOUT) { vm.state.first { it.draft.category != null } }.draft
        assertEquals(
            "precondition: the draft is not the one typed",
            ManualDraft(1_250L, day, "Kopitiam", "teh tarik", CategoryChoice(FOOD, ChosenBy.PRESELECTED)),
            typed,
        )

        val restored = ManualEntryViewModel(app, afterProcessDeath(handle), target = null, preselection = never).also(viewModels::add)

        assertEquals("The draft did not survive the process death", typed, restoredDraft(restored))
    }

    @Test fun aTappedChipComesBackTapped() = runBlocking {
        val handle = SavedStateHandle()
        val vm = ManualEntryViewModel(app, handle, target = null, preselection = never).also(viewModels::add)
        vm.amount(500L)
        vm.tapCategory(FOOD)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.category != null } }

        val restored = ManualEntryViewModel(app, afterProcessDeath(handle), target = null, preselection = never).also(viewModels::add)

        assertEquals(
            "A tapped chip did not come back as tapped, so it would save as rule-filed",
            ManualDraft(amountSen = 500L, category = CategoryChoice(FOOD, ChosenBy.TAPPED)),
            restoredDraft(restored),
        )
        assertEquals("A new entry came back as an edit", null, restored.editing)
    }

    @Test fun aRestoredCorrectionIsNotReadOverByTheStoredRow() = runBlocking {
        val id = Databases.txnDao(context).insert(manual(amountSen = 500L, occurredAt = NOON))
        val handle = SavedStateHandle()
        val vm = ManualEntryViewModel(app, handle, target = target(id), preselection = never).also(viewModels::add)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 500L } }
        vm.amount(900L)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 900L } }

        // No id from the key: the one corrected is what the saved state kept.
        val restored = ManualEntryViewModel(app, afterProcessDeath(handle), target = null, preselection = never).also(viewModels::add)

        assertEquals("The corrected amount did not survive the process death", 900L, restoredDraft(restored)?.amountSen)
        assertNull(
            "The stored row was read over the restored correction",
            withTimeoutOrNull(LOAD_BUDGET) { restored.state.first { it.draft.amountSen == 500L } },
        )
        assertEquals("The entry being corrected did not survive the process death", id, restored.state.value.editing)
    }

    @Test fun aCorrectionKilledBeforeItsRowWasReadOpensWithTheRow() = runBlocking {
        val id = Databases.txnDao(context).insert(manual(amountSen = 500L, occurredAt = NOON))
        val handle = SavedStateHandle()
        val busy = Transfers.state.map { it.replacingLedger }.stateIn(scope, SharingStarted.Eagerly, null)

        // A restore holds the row's read off; the process dies meanwhile.
        val killed = holdWhile(Operation.RESTORE, busy, releases) { requester, beforeStart ->
            Transfers.restoreFrom(context, open = { null }, requester = requester, beforeStart = beforeStart)
        }.use {
            val vm = ManualEntryViewModel(app, handle, target = target(id), preselection = never).also(viewModels::add)
            withTimeout(TIMEOUT) { vm.state.first { it.busy != null } }
            afterProcessDeath(handle)
        }

        val restored = ManualEntryViewModel(app, killed, target = null, preselection = never).also(viewModels::add)

        assertEquals(
            "A correction killed before its row was read reopened blank instead of with the row",
            500L,
            withTimeoutOrNull(LOAD_BUDGET) { restored.state.first { it.draft.amountSen == 500L } }?.draft?.amountSen,
        )
    }

    /** The draft [vm] starts from, once its state has one; null when it never does. */
    private fun target(id: Long) = EditTarget(id, Databases.txnDao(context).byId(id)!!.createdAt)

    private suspend fun restoredDraft(vm: ManualEntryViewModel): ManualDraft? =
        withTimeoutOrNull(RESTORE_BUDGET) { vm.state.first { it.draft.amountSen != 0L } }?.draft

    /**
     * [handle]'s values as a `Bundle` carries them across a process death:
     * parcelled and read back. After the main thread is idle, where the view
     * model writes the draft, as an Activity saves its state there.
     */
    private fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return parcelled(handle)
    }

    /** [handle]'s values as a `Bundle` carries them: parcelled and read back. */
    private fun parcelled(handle: SavedStateHandle): SavedStateHandle {
        val saved = bundleOf(*handle.keys().map { it to handle.get<Any?>(it) }.toTypedArray())
        val parcel = Parcel.obtain()
        val bytes = try {
            saved.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
        val back = Parcel.obtain()
        try {
            back.unmarshall(bytes, 0, bytes.size)
            back.setDataPosition(0)
            val bundle: Bundle = back.readBundle(javaClass.classLoader)!!
            @Suppress("DEPRECATION")
            return SavedStateHandle(bundle.keySet().associateWith { bundle.get(it) })
        } finally {
            back.recycle()
        }
    }

    private companion object {
        const val FOOD = 1L

        /** A draft restored is in the first state; nothing is read for it. */
        const val RESTORE_BUDGET = 5_000L

        /** Ample for edit mode's one read of the row on a fresh ledger. */
        const val LOAD_BUDGET = 3_000L

        /** A pre-selection that never answers. */
        val never: (String) -> Deferred<Long?> = { CompletableDeferred() }
    }
}
