package my.pinged.ledger.entry

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * What Save does when Pinged already has the payment (#104, spec 9.4), through
 * the entry screen's own view model against the real database: it writes
 * nothing and asks; Cancel keeps the draft unsaved; Add anyway writes it.
 */
@RunWith(AndroidJUnit4::class)
class NearbyWarningViewModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test fun saveAsksFirstCancelKeepsTheDraftAndAddAnywaySaves() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = System.currentTimeMillis() - 60_000L))
        val vm = ManualEntryViewModel(context.applicationContext as Application, SavedStateHandle())
        vm.amount(1_250L)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 1_250L } }

        vm.save().join()
        val asked = withTimeout(TIMEOUT) { vm.state.first { it.nearby != null || it.saved } }
        assertEquals("Save did not name the capture it may repeat", SOURCE_LABEL, asked.nearby?.source)
        assertEquals("Save wrote the entry before the user answered", 1, dao.countAll())

        vm.keepEditing()
        val kept = withTimeout(TIMEOUT) { vm.state.first { it.nearby == null } }
        assertEquals("Cancel did not keep the amount", 1_250L, kept.draft.amountSen)
        assertEquals("Cancel saved the entry", false, kept.saved)
        assertEquals("Cancel wrote the entry", 1, dao.countAll())

        // What Cancel kept is what the next Save asks about.
        vm.save().join()
        assertEquals(
            "Cancel did not keep the draft: Save again did not ask about RM12.50",
            1_250L,
            withTimeoutOrNull(ANSWER) { vm.state.first { it.nearby != null } }?.draft?.amountSen,
        )
        vm.addAnyway().join()
        assertEquals(
            "Add anyway did not save",
            true,
            withTimeoutOrNull(ANSWER) { vm.state.first { it.saved } }?.saved,
        )
        assertEquals("Add anyway did not write the entry beside the capture", 2, dao.countAll())
    }

    @Test fun withNothingNearSaveSavesAtOnce() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 1_251L, occurredAt = System.currentTimeMillis() - 60_000L))
        val vm = ManualEntryViewModel(context.applicationContext as Application, SavedStateHandle())
        vm.amount(1_250L)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 1_250L } }

        vm.save().join()

        val done = withTimeout(TIMEOUT) { vm.state.first { it.nearby != null || it.saved } }
        assertNull("Save warned with nothing near", done.nearby)
        assertEquals("Save did not write the entry", 2, dao.countAll())
    }

    @Test fun changingTheDraftWithdrawsTheWarning() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = System.currentTimeMillis() - 60_000L))
        val vm = ManualEntryViewModel(context.applicationContext as Application, SavedStateHandle())
        vm.amount(1_250L)
        withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 1_250L } }
        vm.save().join()
        withTimeout(TIMEOUT) { vm.state.first { it.nearby != null } }

        vm.amount(125L)

        assertNull(
            "A warning about RM12.50 still stood over RM1.25",
            withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 125L } }.nearby,
        )
    }

    private companion object {
        /** A state already decided, a dispatch behind the holder; a miss here is a wrong answer, not a slow one. */
        const val ANSWER = 5_000L
    }
}
