package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import my.pinged.data.CaptureRecord
import my.pinged.data.DayStatus
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.corruptTheDatabase
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.holdWhile
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's manual entry against the real encrypted database (#99): what a
 * Save writes, that every total counts it, that it is never evidence of
 * capture, and that nothing is saved while a restore or a delete runs.
 *
 * Driven through [ManualEntries], the holder the entry screen's view model
 * owns, so what is asserted is what a tap on Save does.
 */
@RunWith(AndroidJUnit4::class)
class ManualEntriesTest {
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

    @Test fun saveWritesAManualEntryAndNothingElse() = runBlocking {
        val dao = context.freshLedger()
        val uncategorized = Databases.categoryDao(context).requireUncategorizedId()

        val id = entries(clock = { NOON }).save(ManualDraft(amountSen = 1_250L)).await()

        assertEquals(
            "Save did not write exactly spec 9.4's manual entry",
            Txn(
                id = id!!,
                rawCaptureId = null,
                amountSen = 1_250L,
                currency = "MYR",
                direction = Direction.EXPENSE,
                occurredAt = NOON,
                localDate = DAY,
                merchantRaw = null,
                merchantDisplay = null,
                merchantKey = null,
                categoryId = uncategorized,
                sourcePackage = null,
                sourceLabel = null,
                confidence = Confidence.HIGH,
                state = TxnState.COMMITTED,
                pendingReason = null,
                isExcluded = false,
                exclusionReason = null,
                note = null,
                userEdited = false,
                createdAt = NOON,
                updatedAt = NOON,
            ),
            dao.byId(id),
        )
    }

    @Test fun theMonthAndTheDayCountTheEntry() = runBlocking {
        val dao = context.freshLedger()
        dao.insert(ledgerTxn(amountSen = 5_000L, occurredAt = NOON - HOUR))

        entries(clock = { NOON }).save(ManualDraft(amountSen = 1_250L)).await()

        val month = LocalDates.monthRange(YearMonth.of(2026, 8))
        assertEquals(
            "The month total does not include the RM12.50 entered by hand",
            listOf(6_250L),
            dao.monthTotals(month.start, month.endInclusive).map { it.netSen },
        )
        assertEquals(
            "The day subtotal does not include the RM12.50 entered by hand",
            listOf(6_250L),
            dao.dayTotals(DAY, DAY).map { it.netSen },
        )
    }

    @Test fun aDayWithOnlyAManualEntryIsNotCapturedAndTheHistoryStartStays() = runBlocking {
        context.freshLedger()
        val captureDays = Databases.captureDayDao(context)
        // Capture began the day after; the entry is the only row on its day.
        val firstBound = LocalDates.calendarDay(DAY).plusDays(1)
        captureDays.recordListenerBound(LocalDates.of(firstBound), bound = true)

        entries(clock = { NOON }).save(ManualDraft(amountSen = 1_250L)).await()

        val record = Databases.inOneTransaction(context) { CaptureRecord.read(it, firstBound.plusDays(2), CaptureRecord.EVERY_DAY) }
        assertEquals(
            "A manual entry moved the history start",
            firstBound,
            record.historyStart,
        )
        assertEquals(
            "A day holding only a manual entry, before the history start, is in the record",
            DayStatus.OUTSIDE_RECORD,
            record.day(LocalDates.calendarDay(DAY)),
        )
    }

    @Test fun nothingIsSavedDuringARestoreAndSaveWorksAfterwards() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()

        // Held before it touches anything; a document that will not open then
        // ends it with the ledger as it was.
        holdWhile(Operation.RESTORE, entries.busy, releases) { requester, beforeStart ->
            Transfers.restoreFrom(context, open = { null }, requester = requester, beforeStart = beforeStart)
        }.use {
            val refused = withTimeout(SAVE_BUDGET) { entries.save(ManualDraft(amountSen = 1_250L)).await() }
            assertNull("A save during a restore reported a row", refused)
        }

        assertEquals("A save during a restore was written", 0, dao.countAll())
        assertNull("The holder still says a restore is running after it ended", entries.busy.value)
        assertTrue("Save did nothing once the restore had ended", entries.save(ManualDraft(amountSen = 1_250L)).await() != null)
        assertEquals(1, dao.countAll())
    }

    @Test fun nothingIsSavedDuringADeleteAndSaveWorksAfterwards() = runBlocking {
        val dao = context.freshLedger()
        val entries = entries()

        // Held before it touches anything; the requester then goes away, so
        // the delete never starts.
        val requester = Job()
        holdWhile(Operation.DELETE, entries.busy, releases, requester) { r, beforeStart ->
            Transfers.deleteEverything(context, requester = r, beforeStart = beforeStart)
        }.use {
            val refused = withTimeout(SAVE_BUDGET) { entries.save(ManualDraft(amountSen = 1_250L)).await() }
            assertNull("A save during a delete reported a row", refused)
            requester.cancel()
        }

        assertEquals("A save during a delete was written", 0, dao.countAll())
        assertNull("The holder still says a delete is running after it ended", entries.busy.value)
        assertTrue("Save did nothing once the delete had ended", entries.save(ManualDraft(amountSen = 1_250L)).await() != null)
        assertEquals(1, dao.countAll())
    }

    @Test fun aLedgerThatWillNotOpenSavesNothingAndSaysSo() = runBlocking {
        context.corruptTheDatabase()
        val entries = entries()

        val saved = entries.save(ManualDraft(amountSen = 1_250L)).await()

        assertNull("A save into a ledger that will not open reported a row", saved)
        assertTrue("The holder did not say the ledger cannot be read", entries.storageUnavailable.value)
    }

    @Test fun anEmptyAmountIsNeverSaved() = runBlocking {
        val dao = context.freshLedger()

        val saved = entries().save(ManualDraft(amountSen = 0L)).await()

        assertNull("RM0.00 reported a row", saved)
        assertEquals("RM0.00 was written", 0, dao.countAll())
    }

    @Test fun aTappedChipSavesAsAOneOffThatTeachingDoesNotMove() = runBlocking {
        val dao = context.freshLedger()
        val food = categoryId("Food & Drinks")
        val transport = categoryId("Transport")

        val id = entries(clock = { NOON })
            .save(ManualDraft(amountSen = 1_250L, merchant = MERCHANT, category = CategoryChoice(food, ChosenBy.TAPPED))).await()!!
        assertEquals("A tapped chip did not save under its category", food, dao.byId(id)!!.categoryId)
        assertTrue("A tapped chip did not save as a one-off", dao.byId(id)!!.userEdited)

        teachTheMerchant(to = transport)

        assertEquals("Teaching the merchant moved a one-off", food, dao.byId(id)!!.categoryId)
    }

    @Test fun noChipSavesUncategorizedForALaterTeachingToFile() = runBlocking {
        val dao = context.freshLedger()
        val transport = categoryId("Transport")

        val id = entries(clock = { NOON }).save(ManualDraft(amountSen = 1_250L, merchant = MERCHANT)).await()!!
        assertEquals(
            "No chip did not save as Uncategorized",
            Databases.categoryDao(context).requireUncategorizedId(),
            dao.byId(id)!!.categoryId,
        )
        assertTrue("No chip saved as a one-off", !dao.byId(id)!!.userEdited)

        teachTheMerchant(to = transport)

        assertEquals("Teaching the merchant did not file the entry", transport, dao.byId(id)!!.categoryId)
    }

    /**
     * Teaches [MERCHANT], the merchant the entry was typed with, [to] from a
     * captured payment of it, as the chooser's "Always call this" does.
     */
    private fun teachTheMerchant(to: Long) {
        val captured = Databases.txnDao(context).insert(
            ledgerTxn(amountSen = 900L, occurredAt = NOON - HOUR, categoryId = Databases.categoryDao(context).requireUncategorizedId()),
        )
        Databases.merchantIdentityDao(context).teachAndFix(captured, to, NOON)
    }

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id

    private fun entries(clock: () -> Long = System::currentTimeMillis) =
        ManualEntries(context = { context }, scope = scope, clock = clock)

    private companion object {
        /** Noon on a fixed day, so the day and month totals name one period. */
        val NOON: Long = java.time.LocalDate.of(2026, 8, 15).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val DAY = LocalDates.of(NOON)
        const val HOUR = 3_600_000L

        /** A save refused rather than queued returns at once; a queued one waits for the hold. */
        const val SAVE_BUDGET = 5_000L
    }
}
