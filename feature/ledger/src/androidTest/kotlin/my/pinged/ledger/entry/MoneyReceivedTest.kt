package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.ManualEdit
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Money received by hand (#355, spec 9.4): a manual entry saved as `REFUND`,
 * and a saved one changed between the two directions, against the real
 * encrypted database through [ManualEntries].
 */
@RunWith(AndroidJUnit4::class)
class MoneyReceivedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun receivedSavesAsARefundAndTheDefaultAsAnExpense() = runBlocking {
        val dao = context.freshLedger()
        val paid = entries().save(ManualDraft(amountSen = 1_000L)).await()!!
        val received = entries().save(ManualDraft(amountSen = 1_000L, direction = Direction.REFUND)).await()!!

        assertEquals("The default draft did not save as an expense", Direction.EXPENSE, dao.byId(paid)!!.direction)
        val stored = dao.byId(received)!!
        assertEquals("Received did not save as a REFUND", Direction.REFUND, stored.direction)
        assertNull(stored.rawCaptureId)
        assertNull(stored.sourcePackage)
        assertNull(stored.sourceLabel)
    }

    @Test fun aReceivedEntryLowersTheMonthTheCategoryAndTheMerchantMonth() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        entries().save(ManualDraft(amountSen = 5_000L, merchant = "Kopitam").tapped(food)).await()
        entries().save(ManualDraft(amountSen = 1_200L, merchant = "Kopitam", direction = Direction.REFUND).tapped(food)).await()

        val from = LocalDates.of(DAY.withDayOfMonth(1))
        val to = LocalDates.of(DAY.withDayOfMonth(DAY.lengthOfMonth()))
        val key = dao.merchantTotals(from, to, 10).single().identityKey
        assertEquals("The month total did not fall by the amount received", 3_800L, dao.monthTotals(from, to).single().netSen)
        assertEquals("The category total did not fall by the amount received", 3_800L, dao.monthByCategory(from, to, 10).single { it.categoryId == food }.netSen)
        assertEquals("The merchant month did not fall by the amount received", 3_800L, dao.merchantMonth(key, from, to).single().netSen)
    }

    @Test fun flippingTheDirectionWritesOnlyTheDirectionAndUpdatedAt() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        val id = entries().save(ManualDraft(amountSen = 800L, merchant = "Kopitam", note = "n").tapped(food)).await()!!
        val stored = dao.byId(id)!!
        val from = LocalDates.of(DAY.withDayOfMonth(1))
        val to = LocalDates.of(DAY.withDayOfMonth(DAY.lengthOfMonth()))

        val toReceived = entries(LATER).read(id).draft.copy(direction = Direction.REFUND)
        assertEquals(true, entries(LATER).update(target(id), toReceived).await())
        assertEquals(
            "Flipping to Received wrote more than direction and updated_at",
            stored.copy(direction = Direction.REFUND, updatedAt = LATER),
            dao.byId(id),
        )
        assertEquals("The total did not follow the flip", -800L, dao.monthTotals(from, to).single().netSen)
        assertEquals(Direction.REFUND, (entries(LATER).read(id)).draft.direction)

        val back = entries(LATER + 1).read(id).draft.copy(direction = Direction.EXPENSE)
        assertEquals(true, entries(LATER + 1).update(target(id), back).await())
        assertEquals(
            "Flipping back wrote more than direction and updated_at",
            stored.copy(updatedAt = LATER + 1),
            dao.byId(id),
        )
        assertEquals(800L, dao.monthTotals(from, to).single().netSen)
    }

    @Test fun aCapturedRowsDirectionNeverChanges() = runBlocking {
        val dao = context.freshLedger()
        val captured = dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = NOON - HOUR))
        val before = dao.byId(captured)!!

        val written = dao.editManualEntry(captured, before.createdAt, ManualEdit(direction = Direction.REFUND), LATER)

        assertFalse("The edit reported writing a captured row", written)
        assertEquals("A captured row's direction was changed", before, dao.byId(captured))
    }

    @Test fun receivedIsNotFlaggedAgainstACapturedExpense() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = NOON - MINUTE))
        val received = ManualDraft(amountSen = 1_250L, direction = Direction.REFUND)

        assertNull("Received was flagged against a captured expense", entries().nearbyCapture(received, editing = null).await())
        assertNull(
            "Received on a backdated day was flagged against a captured expense",
            entries().nearbyCapture(received.copy(day = DAY), editing = null).await(),
        )
    }

    @Test fun receivedIsFlaggedAgainstACapturedRefundAndPaidIsNot() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 1_250L, occurredAt = NOON - MINUTE).copy(direction = Direction.REFUND))
        val received = ManualDraft(amountSen = 1_250L, direction = Direction.REFUND)

        assertNotNull("Received was not flagged against a captured refund", entries().nearbyCapture(received, editing = null).await())
        assertNotNull(
            "Received on a backdated day was not flagged against a captured refund",
            entries().nearbyCapture(received.copy(day = DAY), editing = null).await(),
        )
        assertNull(
            "Paid was flagged against a captured refund",
            entries().nearbyCapture(received.copy(direction = Direction.EXPENSE), editing = null).await(),
        )
    }

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id

    private suspend fun ManualEntries.read(id: Long): Loaded.Entry = load(id).await() as Loaded.Entry

    private fun target(id: Long) = EditTarget(id, Databases.txnDao(context).byId(id)!!.createdAt)

    private fun entries(now: Long = NOON) = ManualEntries(context = { context }, scope = scope, clock = { now })

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 8, 15)
        val NOON: Long = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        val LATER: Long = NOON + HOUR
    }
}
