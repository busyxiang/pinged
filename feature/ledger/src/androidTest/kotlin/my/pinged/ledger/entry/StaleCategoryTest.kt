package my.pinged.ledger.entry

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.data.entity.Category
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.review.NOON
import my.pinged.ledger.review.manual
import my.pinged.ledger.settings.Transfers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A draft's category that the ledger no longer has: a custom chip selected
 * before a delete-everything, which restarts the `category` table with the
 * seed alone. Its id must never reach an insert or an update: `txn` has a
 * foreign key on it, and the violation escaped the view model's scope as a
 * process crash on Save.
 */
@RunWith(AndroidJUnit4::class)
class StaleCategoryTest {
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

    @Test fun aChipADeleteEverythingRemovedSavesNothingAndDoesNotThrow() = runBlocking {
        context.freshLedger()
        val kopi = customCategory()
        Transfers.deleteEverything(context, requester = Job()).await()
        assertNull("precondition: the delete kept the custom category", Databases.categoryDao(context).isProtected(kopi))

        val saved = entries().save(ManualDraft(amountSen = 1_250L, category = CategoryChoice(kopi, ChosenBy.TAPPED))).await()

        assertNull("A save under a category that is gone reported a row", saved)
        assertEquals("A save under a category that is gone was written", 0, Databases.txnDao(context).countAll())
    }

    @Test fun aCorrectionToACategoryThatIsGoneWritesNothingAndDoesNotThrow() = runBlocking {
        val dao = context.freshLedger()
        val id = dao.insert(manual(amountSen = 500L, occurredAt = NOON))
        val kopi = customCategory()
        Databases.categoryDao(context).deleteRow(kopi)

        val written = entries().update(EditTarget(id, dao.byId(id)!!.createdAt), ManualDraft(amountSen = 500L, day = DAY_OF_NOON, category = CategoryChoice(kopi, ChosenBy.TAPPED))).await()

        assertFalse("A correction to a category that is gone reported a write", written)
        assertEquals("A correction to a category that is gone was written", uncategorized(), dao.byId(id)!!.categoryId)
    }

    @Test fun theScreenDropsAChipTheLedgerNoLongerHas() = runBlocking {
        context.freshLedger()
        val kopi = customCategory()
        val vm = ManualEntryViewModel(app, SavedStateHandle(), target = null, preselection = never).also(viewModels::add)
        withTimeout(TIMEOUT) { vm.state.first { state -> state.categories.any { it.id == kopi } } }
        vm.amount(1_250L)
        vm.tapCategory(kopi)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.category?.categoryId == kopi } }

        Transfers.deleteEverything(context, requester = Job()).await()

        withTimeout(TIMEOUT) { vm.state.first { state -> state.busy == null && state.categories.isNotEmpty() && state.categories.none { it.id == kopi } } }
        val dropped = withTimeoutOrNull(TIMEOUT) { vm.state.first { it.draft.category == null } }
        assertNotNull("The draft kept a chip the ledger no longer has once the chips were read again", dropped)
        assertEquals("The rest of the draft was not kept", 1_250L, dropped!!.draft.amountSen)
    }

    /** A category of the user's own, which a delete-everything does not seed back. */
    private fun customCategory(): Long {
        Databases.categoryDao(context).insertAll(listOf(Category(name = "Kopi", iconKey = "coffee", sortOrder = 99)))
        return Databases.categoryDao(context).all().single { it.name == "Kopi" }.id
    }

    private fun uncategorized(): Long = Databases.categoryDao(context).requireUncategorizedId()

    private fun entries() = ManualEntries(context = { context }, scope = scope)

    private companion object {
        val DAY_OF_NOON = java.time.Instant.ofEpochMilli(NOON).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        /** A pre-selection that never answers, so the chip is the one tapped. */
        val never: (String) -> Deferred<Long?> = { CompletableDeferred() }
    }
}
