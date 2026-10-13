package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.holdWhile
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting a manual entry (#106, spec 9.4) against the real encrypted
 * database, through [ManualEntries] as the entry screen's Delete drives it:
 * the row and its money leave every total, a captured row cannot be reached,
 * and nothing is deleted while a restore or delete-everything holds the gate.
 */
@RunWith(AndroidJUnit4::class)
class ManualDeleteTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val releases = mutableListOf<CountDownLatch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        releases.forEach { it.countDown() }
        runBlocking { withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } } }
        Transfers.forgetOutcome()
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun theDeleteRemovesTheEntryAndItsMoneyFromEveryTotal() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()
        val kept = entries.save(ManualDraft(amountSen = 800L, merchant = "99 Speedmart")).await()!!
        val deleted = entries.save(ManualDraft(amountSen = 1_250L, merchant = "99 Speedmart")).await()!!
        // A captured row the same day, so a total that lost everything would show.
        dao.insert(ledgerTxn(amountSen = 3_000L, occurredAt = NOON - HOUR, localDate = DAY))
        assertEquals("precondition: the day's total", 5_050L, dayTotal())
        assertEquals("precondition: the month's total", 5_050L, monthTotal())
        assertEquals("precondition: the merchant's count", 2, speedmartCount())

        assertTrue("The delete reported nothing deleted", entries.delete(target(deleted)).await())

        assertNull("The deleted entry is still in the ledger", dao.byId(deleted))
        assertNotNull("The delete took another entry with it", dao.byId(kept))
        assertEquals("The day's total did not drop by RM12.50", 3_800L, dayTotal())
        assertEquals("The month's total did not drop by RM12.50", 3_800L, monthTotal())
        assertEquals("Top merchants still counts the deleted entry", 1, speedmartCount())
    }

    @Test fun anEntryAlreadyGoneCountsAsDeleted() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()
        val id = entries.save(ManualDraft(amountSen = 1_250L)).await()!!
        val target = target(id)
        // Gone behind the screen's back, as from a second entry screen.
        dao.deleteManualEntry(id, target.createdAt)

        assertTrue(
            "A delete of an entry already gone reported it still there, so the screen stayed open over nothing",
            entries.delete(target).await(),
        )
    }

    @Test fun theDeleteRefusesACapturedRow() = runBlocking {
        val dao = context.freshLedger()
        val captured = dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = NOON - HOUR, localDate = DAY))
        val before = dao.byId(captured)!!

        assertFalse("The delete reported deleting a captured row", entries().delete(EditTarget(captured, before.createdAt)).await())
        assertEquals("The delete changed a captured row", before, dao.byId(captured))
    }

    @Test fun nothingIsDeletedDuringARestore() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()
        val id = entries.save(ManualDraft(amountSen = 1_250L)).await()!!

        holdWhile(Operation.RESTORE, entries.busy, releases) { requester, beforeStart ->
            Transfers.restoreFrom(context, open = { null }, requester = requester, beforeStart = beforeStart)
        }.use {
            assertFalse("A delete during a restore reported a row", withTimeout(BUDGET) { entries.delete(target(id)).await() })
        }

        assertNotNull("A delete during a restore was written", dao.byId(id))
        assertFalse("The refusal was reported as a ledger that will not open", entries.storageUnavailable.value)
    }

    @Test fun nothingIsDeletedDuringADeleteEverything() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()
        val id = entries.save(ManualDraft(amountSen = 1_250L)).await()!!

        // The requester goes away while held, so the delete-everything never starts.
        val requester = Job()
        holdWhile(Operation.DELETE, entries.busy, releases, requester) { r, beforeStart ->
            Transfers.deleteEverything(context, requester = r, beforeStart = beforeStart)
        }.use {
            assertFalse("A delete during delete-everything reported a row", withTimeout(BUDGET) { entries.delete(target(id)).await() })
            requester.cancel()
        }

        assertNotNull("A delete during delete-everything was written", dao.byId(id))
    }

    private fun target(id: Long) = EditTarget(id, Databases.txnDao(context).byId(id)!!.createdAt)

    private fun dayTotal(): Long = Databases.txnDao(context).dayTotals(DAY, DAY).sumOf { it.netSen }

    private fun monthTotal(): Long = MONTH.let { Databases.txnDao(context).monthTotals(it.start, it.endInclusive) }.sumOf { it.netSen }

    private fun speedmartCount(): Int =
        MONTH.let { Databases.txnDao(context).merchantTotals(it.start, it.endInclusive, limit = 10) }
            .single { it.identityKey == "99 SPEEDMART" }.txnCount

    private fun entries() = ManualEntries(context = { context }, scope = scope, clock = { NOON })

    private companion object {
        val NOON: Long = LocalDate.of(2026, 8, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val DAY = LocalDates.of(NOON)
        val MONTH = LocalDates.monthRange(YearMonth.of(2026, 8))
        const val HOUR = 3_600_000L
        const val BUDGET = 5_000L
    }
}
