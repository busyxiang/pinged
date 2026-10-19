package my.pinged.ledger.entry

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.ledger.SOURCE_LABEL
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen's edit mode (#105) through its own view model against the
 * real database: it opens filled in, a correction asks about a nearby capture
 * again only when the amount or date changed, and the stored category is not
 * replaced by a lookup of the merchant it was filed by.
 */
@RunWith(AndroidJUnit4::class)
class ManualEditViewModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val viewModels = mutableListOf<ManualEntryViewModel>()

    @After fun leaveNothingBehind() {
        // So no view model of this test reads on into the next one's.
        viewModels.forEach { it.viewModelScope.cancel() }
        context.discardTheDatabase()
    }

    @Test fun aNoteAloneSavesWithoutAskingAgain() = runBlocking {
        val dao = context.freshLedger()
        val now = System.currentTimeMillis()
        dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = now - MINUTE))
        val id = dao.insert(manual(amountSen = 1_250L, occurredAt = now - 2 * MINUTE))
        val vm = editing(id)

        vm.note("parking")
        withTimeout(TIMEOUT) { vm.state.first { it.draft.note == "parking" } }
        vm.save().join()

        val done = withTimeout(TIMEOUT) { vm.state.first { it.nearby != null || it.saved } }
        assertNull("A note corrected alone asked about the capture beside the entry", done.nearby)
        assertEquals("parking", dao.byId(id)!!.note)
    }

    @Test fun aNewAmountAsksAboutTheCaptureItNowRepeats() = runBlocking {
        val dao = context.freshLedger()
        val now = System.currentTimeMillis()
        dao.insert(ledgerTxn(amountSen = 1_300L, occurredAt = now - MINUTE))
        val id = dao.insert(manual(amountSen = 1_250L, occurredAt = now - 2 * MINUTE))
        val vm = editing(id)

        vm.amount(1_300L)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 1_300L } }
        vm.save().join()

        val asked = withTimeout(TIMEOUT) { vm.state.first { it.nearby != null || it.saved } }
        assertEquals("A corrected amount was not checked against the capture it repeats", SOURCE_LABEL, asked.nearby?.source)
        assertEquals("The correction was written before the user answered", 1_250L, dao.byId(id)!!.amountSen)
    }

    @Test fun editModeKeepsTheStoredCategoryOverTheMerchantsFiling() = runBlocking {
        val dao = context.freshLedger()
        val transport = categoryId("Transport")
        // Rule-filed under Transport, though the dictionary files 99 SPEEDMART as Groceries.
        val id = dao.insert(
            manual(amountSen = 1_250L, occurredAt = System.currentTimeMillis() - MINUTE, categoryId = transport)
                .copy(merchantRaw = "99 Speedmart", merchantDisplay = "99 Speedmart", merchantKey = "99 SPEEDMART"),
        )
        val vm = editing(id)
        assertEquals(CategoryChoice(transport, ChosenBy.PRESELECTED), vm.state.value.draft.category)

        // An absence, so a wait: a lookup of the merchant answers within
        // milliseconds on the emulator, and this is far longer.
        assertNull(
            "Opening edit mode replaced the stored category with the merchant's filing",
            withTimeoutOrNull(ANSWER) { vm.state.first { it.draft.category?.categoryId != transport } },
        )
    }

    /**
     * #237: until the row is read the draft is the empty default, and the
     * state says so, so the screen does not draw it as the payment.
     */
    @Test fun aCorrectionIsReadingUntilItsRowIsRead() = runBlocking {
        val dao = context.freshLedger()
        val id = dao.insert(manual(amountSen = 1_250L, occurredAt = System.currentTimeMillis() - MINUTE))
        val vm = ManualEntryViewModel(context.applicationContext as Application, SavedStateHandle(), EditTarget(id, dao.byId(id)!!.createdAt))
            .also(viewModels::add)

        assertEquals("A correction not read yet was not marked as reading", true, vm.state.value.reading)
        val read = withTimeout(TIMEOUT) { vm.state.first { it.loaded != null } }
        assertEquals("A correction read was still marked as reading", false, read.reading)
    }

    @Test fun deletingAnEntryAlreadyGoneClosesTheScreen() = runBlocking {
        val dao = context.freshLedger()
        val id = dao.insert(manual(amountSen = 1_250L, occurredAt = System.currentTimeMillis() - MINUTE))
        val vm = editing(id)
        dao.deleteManualEntry(id, dao.byId(id)!!.createdAt)

        vm.delete().join()

        assertEquals(
            "Delete of an entry already gone left the screen open with no word",
            true,
            withTimeoutOrNull(TIMEOUT) { vm.state.first { it.deleted } }?.deleted,
        )
    }

    /** The view model in edit mode on [id], once its draft has loaded. */
    private suspend fun editing(id: Long): ManualEntryViewModel {
        val vm = ManualEntryViewModel(context.applicationContext as Application, SavedStateHandle(), EditTarget(id, Databases.txnDao(context).byId(id)!!.createdAt))
            .also(viewModels::add)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen > 0L } }
        assertEquals(id, vm.state.value.editing)
        return vm
    }

    /** An entry typed by hand, as `ReviewFixtures.manual` builds one. */
    private fun manual(amountSen: Long, occurredAt: Long, categoryId: Long = uncategorized()) =
        ledgerTxn(amountSen = amountSen, occurredAt = occurredAt, categoryId = categoryId, rawCaptureId = null)
            .copy(sourcePackage = null, sourceLabel = null)

    private fun uncategorized(): Long = Databases.categoryDao(context).requireUncategorizedId()

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id

    private companion object {
        const val MINUTE = 60_000L

        /** A state already decided, a dispatch behind the holder. */
        const val ANSWER = 2_000L
    }
}
