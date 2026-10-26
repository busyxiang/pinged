package my.pinged.ledger.home

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import my.pinged.data.LocalDates
import my.pinged.data.entity.Category
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.TransferJob
import my.pinged.ledger.settings.TransferState
import my.pinged.read.ScreenRead
import my.pinged.read.test.ScriptedRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * `RowActions` during a ledger replacement (#331): from the claim, before any
 * gate is up, the chooser's and the merchant sheet's writes make no
 * `runner.write`, raise no `unavailable`, and write again once it clears.
 *
 * `ScriptedRunner` has no gate at all, so a write it is asked for lands: these
 * are the window between a restore's claim and its gate, where a category
 * saved would be wiped by the restore after the user saw it saved.
 */
@RunWith(RobolectricTestRunner::class)
class RowActionsReplacingTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())
    private val transfers = MutableStateFlow(TransferState())

    private val screen: ScreenRead<Unit, Long?> =
        ScreenRead.fixed(scope, runner, "The test cannot be read") { db -> db.txnDao().byId(ROW)?.categoryId }

    private val rows = RowActions(scope, runner, screen.rowScreen(), transfers)

    @After fun end() {
        scope.cancel()
    }

    @Test fun aCategoryAssignedDuringARestoreIsNotWrittenAndIsOnceItEnds() {
        seed()
        replacing()

        rows.assignCategory(ROW, GROCERIES, teach = false)

        refusedWithoutWriting()
        assertEquals("A category assigned during a restore was written", UNCATEGORIZED, categoryOfRow())

        cleared()
        rows.assignCategory(ROW, GROCERIES, teach = false)
        assertEquals("A category assigned after the restore ended was not written", GROCERIES, categoryOfRow())
    }

    @Test fun aCategoryAddedDuringADeleteIsNotWrittenAndIsOnceItEnds() {
        seed()
        replacing(Operation.DELETE)

        val added = runBlocking { rows.addCategory("Kucing", "dog") }

        assertNull("A category added during a delete-everything has an id", added)
        refusedWithoutWriting()

        cleared()
        assertNotNull("A category added after the delete ended was not written", runBlocking { rows.addCategory("Kucing", "dog") })
    }

    @Test fun aMerchantRenamedDuringARestoreIsNotWrittenAndTheSheetStaysOpen() {
        seed()
        rows.openMerchant(opening(ALI))
        replacing()

        rows.renameMerchant("Ali's")

        refusedWithoutWriting()
        assertNotNull("The sheet closed on a rename it did not write", rows.merchantSheet.value?.read)

        cleared()
        rows.renameMerchant("Ali's")
        assertTrue("A rename after the restore ended was not written", wrote())
    }

    @Test fun aMerchantMergedDuringARestoreIsNotWritten() {
        seed()
        rows.openMerchant(opening(ALI))
        replacing()

        rows.mergeMerchant(BAKRI)

        refusedWithoutWriting()

        cleared()
        rows.mergeMerchant(BAKRI)
        assertTrue("A merge after the restore ended was not written", wrote())
    }

    @Test fun aMerchantSeparatedDuringARestoreIsNotWritten() {
        seed()
        rows.openMerchant(opening(BAKRI))
        rows.mergeMerchant(ALI)
        rows.openMerchant(opening(BAKRI))
        replacing()
        val before = runner.calls.size

        rows.separateMerchant(BAKRI)

        assertEquals("A separate during a restore asked the runner for anything", before, runner.calls.size)
        assertFalse(screen.state.value.unavailable)

        cleared()
        rows.separateMerchant(BAKRI)
        assertTrue("A separate after the restore ended was not written", runner.calls.drop(before).any { it.write && it.ran })
    }

    private var asked = 0

    private fun replacing(operation: Operation = Operation.RESTORE) {
        transfers.value = TransferState(running = TransferJob.Running(operation, 0))
        asked = runner.calls.size
    }

    private fun cleared() {
        transfers.value = TransferState()
    }

    private fun refusedWithoutWriting() {
        assertEquals(
            "A write during a ledger replacement asked the runner for something",
            emptyList<String>(),
            runner.calls.drop(asked).map { it.what },
        )
        assertFalse("A write refused for a replacement raised unavailable", screen.state.value.unavailable)
    }

    private fun wrote(): Boolean = runner.calls.drop(asked).any { it.write && it.ran }

    private fun categoryOfRow(): Long? = runBlocking {
        (runner.read("The test cannot check") { db -> db.txnDao().byId(ROW)?.categoryId } as my.pinged.read.Outcome.Done).value
    }

    private fun seed() {
        runBlocking {
            runner.read("The test cannot be seeded") { db ->
                db.categoryDao().insert(Category(UNCATEGORIZED, "Uncategorized", "other", 99, isProtected = true))
                db.categoryDao().insert(Category(GROCERIES, "Groceries", "shopping-basket", 1))
                db.txnDao().insert(ledgerTxn(1_840L, id = ROW, categoryId = UNCATEGORIZED).copy(merchantKey = ALI, merchantRaw = ALI, merchantDisplay = "Ali"))
                db.txnDao().insert(ledgerTxn(900L, id = OTHER_ROW, categoryId = UNCATEGORIZED).copy(merchantKey = BAKRI, merchantRaw = BAKRI, merchantDisplay = "Bakri"))
            }
        }
    }

    private fun opening(key: String) = MerchantOpening(key, key, LocalDates.of(LocalDate.of(2026, 9, 14)))

    private companion object {
        const val UNCATEGORIZED = 1L
        const val GROCERIES = 2L
        const val ROW = 10L
        const val OTHER_ROW = 11L
        const val ALI = "TNG*ALI"
        const val BAKRI = "TNG*BAKRI"
    }
}
