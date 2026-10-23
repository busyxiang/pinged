package my.pinged.ledger.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.Seed
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.holdWhile
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.review.sql
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import my.pinged.parse.ExclusionReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's category chips, most used first (#100), against the real
 * encrypted database: the counted rows of the 90 days ending today, then
 * `sort_order`, and never Uncategorized. Read through [ManualEntries], the
 * holder the entry screen's view model owns, so what is asserted is the order
 * the screen is handed.
 */
@RunWith(AndroidJUnit4::class)
class CategoryRankingTest {
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

    @Test fun withNoHistoryTheChipsAreInSortOrderWithoutUncategorized() = runBlocking {
        context.freshLedger()

        assertEquals(
            "A fresh install's chips are not the seed's order less Uncategorized",
            SEED_ORDER,
            ranked(),
        )
    }

    @Test fun aRowEightyNineDaysAgoCountsAndNinetyDoesNot() = runBlocking {
        val dao = context.freshLedger()
        dao.paid("Transport", daysAgo = 89)
        repeat(2) { dao.paid("Groceries", daysAgo = 90) }
        dao.paid("Health", daysAgo = 0)

        assertEquals(
            "Day -89 and today did not count, or day -90 did",
            // One use each, so the tie falls back to sort_order: Transport (2) before Health (7).
            listOf("Transport", "Health") + (SEED_ORDER - "Health" - "Transport"),
            ranked(),
        )
    }

    @Test fun moreUsesRankFirstAndTiesFallBackToSortOrder() = runBlocking {
        val dao = context.freshLedger()
        // Seeded ids follow sort_order; this moves Shopping (id 7) ahead of
        // Transport (id 3), so a tie broken by id would read the other way.
        sql("UPDATE category SET sort_order = -1 WHERE name = 'Shopping'")
        repeat(3) { dao.paid("Entertainment", daysAgo = 3) }
        dao.paid("Transport", daysAgo = 1)
        dao.paid("Shopping", daysAgo = 2)

        assertEquals(
            "Three uses did not lead, or a one-use tie did not fall back to sort_order",
            listOf("Entertainment", "Shopping", "Transport") + (SEED_ORDER - "Entertainment" - "Transport" - "Shopping"),
            ranked(),
        )
    }

    @Test fun uncategorizedNeverAppearsHoweverMuchItIsUsed() = runBlocking {
        val dao = context.freshLedger()
        repeat(5) { dao.paid(Seed.UNCATEGORIZED, daysAgo = 1) }

        assertEquals("Uncategorized was offered as a chip", SEED_ORDER, ranked())
    }

    @Test fun excludedPendingAndRejectedRowsDoNotCount() = runBlocking {
        val dao = context.freshLedger()
        repeat(2) { dao.paid("Health", daysAgo = 1, isExcluded = true, exclusionReason = ExclusionReason.USER) }
        repeat(2) { dao.paid("Education", daysAgo = 1, state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD) }
        repeat(2) { dao.paid("Family", daysAgo = 1, state = TxnState.REJECTED) }

        assertEquals("An uncounted row moved a chip", SEED_ORDER, ranked())
    }

    @Test fun aManualEntryCountsLikeACapture() = runBlocking {
        context.freshLedger()
        val entries = entries()
        // The rank `ManualEntries` starts on arrival reads before this save and
        // publishes on its own thread. Left running, it can land after the
        // `rank()` below and put the seed's order back over the new count: the
        // CI failure was "expected Family but was Food & Drinks". Let it finish.
        withTimeout(TIMEOUT) { entries.categories.first { it.isNotEmpty() } }
        entries.save(ManualDraft(amountSen = 500L, category = CategoryChoice(id("Family"), ChosenBy.TAPPED))).await()

        entries.rank().join()

        assertEquals("A manual entry did not count towards its chip", "Family", entries.categories.value.first().name)
    }

    @Test fun chipsReadDuringARestoreArriveWhenItEnds() = runBlocking {
        context.freshLedger()
        val watch = entries()
        lateinit var during: ManualEntries
        holdWhile(Operation.RESTORE, watch.busy, releases) { requester, beforeStart ->
            Transfers.restoreFrom(context, open = { null }, requester = requester, beforeStart = beforeStart)
        }.use {
            during = entries()
            during.rank().join()
            assertTrue("Chips were read during a restore", during.categories.value.isEmpty())
        }

        assertEquals(
            "The chips did not arrive once the restore ended",
            SEED_ORDER,
            withTimeout(TIMEOUT) { during.categories.first { it.isNotEmpty() } }.map { it.name },
        )
    }

    private fun entries() = ManualEntries(context = { context }, scope = scope, clock = { NOON })

    private suspend fun ranked(): List<String> {
        val entries = entries()
        entries.rank().join()
        return entries.categories.value.map { it.name }
    }

    private fun id(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id

    private fun TxnDao.paid(
        category: String,
        daysAgo: Long,
        state: TxnState = TxnState.COMMITTED,
        pendingReason: PendingReason? = null,
        isExcluded: Boolean = false,
        exclusionReason: ExclusionReason? = null,
    ) {
        val day = LocalDates.calendarDay(TODAY).minusDays(daysAgo)
        insert(
            ledgerTxn(
                amountSen = 1_000L,
                occurredAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                categoryId = id(category),
                state = state,
                pendingReason = pendingReason,
                isExcluded = isExcluded,
                exclusionReason = exclusionReason,
            ),
        )
    }

    private companion object {
        /** The moment the ranking is read: today is 2026-08-15. */
        val NOON: Long = java.time.LocalDate.of(2026, 8, 15).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val TODAY = LocalDates.of(NOON)

        /** The seed's names in `sort_order`, less Uncategorized: the order with no history. */
        val SEED_ORDER = listOf(
            "Food & Drinks", "Groceries", "Transport", "Petrol & tolls", "Bills & utilities",
            "Telco & internet", "Shopping", "Health", "Education", "Family",
            "Religious & zakat", "Government & fees", "Entertainment", "Loans & financing",
            "Insurance & takaful",
        )
    }
}
