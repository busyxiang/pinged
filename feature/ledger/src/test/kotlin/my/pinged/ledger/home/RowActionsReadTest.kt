package my.pinged.ledger.home

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import my.pinged.data.LocalDates
import my.pinged.data.entity.Category
import my.pinged.ledger.ledgerTxn
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
 * What `RowActions` asks of `:core:read` (#296, #298): a write is read back in
 * its own turn, and the merchant sheet is a read whose question is its
 * opening. Run through `ScriptedRunner`, which holds each call until the test
 * lets it go, over an in-memory ledger it has seeded.
 *
 * The scope is `Dispatchers.Unconfined` on the test's own thread, as
 * `ScreenReadTest`'s is, so each assertion is made where the test stopped the
 * runner.
 */
@RunWith(RobolectricTestRunner::class)
class RowActionsReadTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val runner = ScriptedRunner(ApplicationProvider.getApplicationContext())

    /** Whatever the screen reads: the category the seeded row carries. */
    private val screen: ScreenRead<Unit, Long?> =
        ScreenRead.fixed(scope, runner, "The test cannot be read") { db -> db.txnDao().byId(ROW)?.categoryId }

    private val rows = RowActions({ ApplicationProvider.getApplicationContext() }, scope, runner, screen.rowScreen())

    /** Seeds the ledger through the runner, which answers at once while it is not holding. */
    private fun seed(): Int {
        runBlocking {
            runner.read("The test cannot be seeded") { db ->
                db.categoryDao().insert(Category(UNCATEGORIZED, "Uncategorized", "other", 99, isProtected = true))
                db.categoryDao().insert(Category(GROCERIES, "Groceries", "shopping-basket", 1))
                db.txnDao().insert(ledgerTxn(1_840L, id = ROW, categoryId = UNCATEGORIZED).copy(merchantKey = ALI, merchantRaw = ALI, merchantDisplay = "Ali"))
                db.txnDao().insert(ledgerTxn(900L, id = OTHER_ROW, categoryId = UNCATEGORIZED).copy(merchantKey = BAKRI, merchantRaw = BAKRI, merchantDisplay = "Bakri"))
            }
        }
        return runner.calls.size
    }

    @After fun end() {
        scope.cancel()
    }

    @Test fun aCategoryAssignedIsReadBackInItsOwnTurnSoAResumeReadQueuedBehindItNeverSeesTheOldOne() {
        seed()
        screen.refresh()
        assertEquals("The screen's own read did not see the row before the write", UNCATEGORIZED, screen.state.value.answer)
        runner.holding = true
        val base = runner.calls.size

        val assigned = rows.assignCategory(ROW, GROCERIES, teach = false)
        // The resume that arrives while the write is held.
        screen.refresh()
        assertEquals("A read began while the write held the turn", base + 1, runner.calls.size)
        assertTrue("The write was not first", runner.calls[base].write)

        runner.calls[base].release()
        assertTrue("The write did not run before its read-back", runner.calls[base].ran)
        assertEquals("The call after the write was not one more", base + 2, runner.calls.size)
        assertFalse("The call after the write was not the write's read-back", runner.calls[base + 1].write)
        assertFalse("The write was complete before its read-back landed", assigned.isCompleted)

        runner.calls[base + 1].release()
        assertEquals("The read-back did not publish the write's category", GROCERIES, screen.state.value.answer)
        assertTrue(assigned.isCompleted)

        runner.calls[base + 2].release()
        assertEquals("The resume read, queued behind the write, published something older", GROCERIES, screen.state.value.answer)
    }

    @Test fun aSheetClosedWhileItsReadRunsStaysClosed() {
        val seeded = seed()
        runner.holding = true

        rows.openMerchant(opening(ALI))
        assertEquals("The sheet did not open on the tap", OpenMerchantSheet(opening(ALI)), rows.merchantSheet.value)
        rows.closeMerchant()
        runner.calls[seeded].release()

        assertTrue("The read did not run, so this proves nothing", runner.calls[seeded].ran)
        assertNull("The read opened a sheet the user had closed", rows.merchantSheet.value)
    }

    @Test fun aSheetOpenedFromAnotherRowWhileItsReadRunsKeepsTheOtherRowsOpening() {
        val seeded = seed()
        runner.holding = true

        rows.openMerchant(opening(ALI))
        rows.openMerchant(opening(BAKRI))
        runner.calls[seeded].release()

        assertTrue("The first read did not run, so this proves nothing", runner.calls[seeded].ran)
        assertEquals(
            "The first row's read landed on the sheet opened for the second",
            OpenMerchantSheet(opening(BAKRI)),
            rows.merchantSheet.value,
        )

        runner.calls[seeded + 1].release()
        val sheet = rows.merchantSheet.value
        assertEquals(BAKRI, sheet?.opening?.ownKey)
        assertEquals("The second row's own read was not drawn", BAKRI, sheet?.read?.ownKey)
    }

    /** The module keeps the last answer while another question is read; the sheet must not draw it as this row's. */
    @Test fun aSheetOpenedFromAnotherRowDoesNotDrawTheLastRowsReadWhileItsOwnIsRead() {
        val seeded = seed()
        runner.holding = true
        rows.openMerchant(opening(ALI))
        runner.calls[seeded].release()
        assertEquals("The first row's own read was not drawn", ALI, rows.merchantSheet.value?.read?.ownKey)

        rows.openMerchant(opening(BAKRI))

        assertEquals(
            "The last row's read was drawn on the sheet opened for the next",
            OpenMerchantSheet(opening(BAKRI), read = null),
            rows.merchantSheet.value,
        )
    }

    @Test fun aMerchantNoLongerInTheLedgerClosesTheSheetOnceTheReadSaysSo() {
        seed()

        rows.openMerchant(opening("TNG*GONE"))

        assertNull("A sheet with nothing to read stayed open", rows.merchantSheet.value)
    }

    @Test fun aRefusedReadClosesTheSheetAndLetsTheScreenFindOut() {
        val seeded = seed()
        runner.refusing = true

        rows.openMerchant(opening(ALI))

        assertNull("A sheet whose read was refused stayed open", rows.merchantSheet.value)
        assertTrue("The screen's own read was not asked to look again", runner.calls.drop(seeded).any { it.what == "The test cannot be read" })
        assertTrue("The screen did not learn the ledger cannot be read", screen.state.value.unavailable)
    }

    @Test fun aReadSheetIsDrawnWithItsRead() {
        seed()

        rows.openMerchant(opening(ALI))

        val sheet = rows.merchantSheet.value
        assertNotNull(sheet)
        assertEquals(ALI, sheet?.read?.ownKey)
        assertEquals(listOf(BAKRI), sheet?.read?.others?.map { it.identityKey })
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
