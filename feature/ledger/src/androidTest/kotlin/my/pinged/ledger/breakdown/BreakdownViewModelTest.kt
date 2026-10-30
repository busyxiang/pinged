package my.pinged.ledger.breakdown

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Breakdown's holder over a real ledger (#342): what it reads, what it
 * keeps of a line expanded across a process death, and what a write it makes
 * takes off the screen on the re-read that follows.
 */
@RunWith(AndroidJUnit4::class)
class BreakdownViewModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private var food = 0L
    private var groceries = 0L
    private val warung = LineKey.of("WARUNG PAK ALI")

    @Before fun seed() {
        context.freshLedger()
        val categories = Databases.categoryDao(context).all()
        food = categories.single { it.name == "Food & Drinks" }.id
        groceries = categories.single { it.name == "Groceries" }.id
    }

    /** Every day of the month captured but [missed], so the month is trusted unless one is named. */
    private fun capture(missed: Set<Int> = emptySet()) {
        (1..MONTH.lengthOfMonth()).filter { it !in missed }.forEach {
            Databases.captureDayDao(context).recordListenerBound(LocalDates.of(MONTH.atDay(it)), bound = true)
        }
    }

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    private fun pay(sen: Long, day: Int, category: Long = food, currency: String = "MYR"): Long =
        Databases.txnDao(context).insert(
            ledgerTxn(sen, occurredAt = at(day), categoryId = category).copy(currency = currency),
        )

    private fun at(day: Int) = MONTH.atDay(day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun holder(handle: SavedStateHandle = SavedStateHandle(), id: Long = food) =
        withContext(Dispatchers.Main) { BreakdownViewModel(app, id, MONTH, handle) { TODAY } }

    private suspend fun BreakdownViewModel.landed(): BreakdownRead {
        withContext(Dispatchers.Main) { read.refresh() }.join()
        return requireNotNull(read.state.value.answer) { "The read published nothing" }
    }

    @Test(timeout = 60_000)
    fun reFilingAnExpandedRowTakesItsAmountAndCountOffTheScreen() = runBlocking<Unit> {
        capture()
        val moved = pay(3_700L, day = 16)
        pay(3_440L, day = 4)
        val breakdown = holder()
        breakdown.landed()
        withContext(Dispatchers.Main) { breakdown.toggle(warung) }.join()
        assertEquals(2, breakdown.read.state.value.answer!!.rows.getValue(warung).size)

        withContext(Dispatchers.Main) { breakdown.rows.assignCategory(moved, groceries, teach = false) }.join()

        val after = breakdown(breakdown.read.state.value.answer!!)!!
        assertEquals("RM34.40", after.figures.figure)
        assertEquals("1 TRANSACTION", after.figures.count)
        assertEquals(listOf("34.40" to "1×"), after.lines.map { it.amount to it.count })
        assertEquals(listOf(3_440L), breakdown.read.state.value.answer!!.rows.getValue(warung).map { it.txn.amountSen })
    }

    @Test(timeout = 60_000)
    fun anExpandedLineSurvivesTheHolderAndAGoneOneIsDropped() = runBlocking<Unit> {
        capture()
        val only = pay(3_700L, day = 16)
        val handle = SavedStateHandle()
        val first = holder(handle)
        first.landed()
        withContext(Dispatchers.Main) { first.toggle(warung) }.join()

        // A new holder over the same saved state, as after a process death.
        val restored = holder(handle)
        assertEquals(setOf(warung), restored.read.question.value)
        assertEquals(1, restored.landed().rows.getValue(warung).size)

        // The line goes: its one row re-filed elsewhere. Polled, since the
        // drop follows the read's state on the main thread's next turn.
        withContext(Dispatchers.Main) { restored.rows.assignCategory(only, groceries, teach = false) }.join()
        withTimeoutOrNull(5_000) { while (restored.read.question.value.isNotEmpty()) delay(10) }
        assertTrue("A line gone from the read is still expanded", restored.read.question.value.isEmpty())
        assertEquals(emptyList<String>(), handle.get<ArrayList<String>>(BreakdownViewModel.EXPANDED))
    }

    @Test(timeout = 60_000)
    fun aRowInAnotherCurrencyIsNowhere() = runBlocking<Unit> {
        capture()
        pay(3_700L, day = 16)
        pay(9_900L, day = 17, currency = "SGD")
        val breakdown = holder(SavedStateHandle(mapOf(BreakdownViewModel.EXPANDED to arrayListOf(warung.saved))))

        val read = breakdown.landed()

        val view = breakdown(read)!!
        assertEquals("RM37.00", view.figures.figure)
        assertEquals("1 TRANSACTION", view.figures.count)
        assertEquals(listOf("37.00"), view.lines.map { it.amount })
        assertEquals(listOf(3_700L), read.rows.getValue(warung).map { it.txn.amountSen })
    }

    @Test(timeout = 60_000)
    fun aDeletedCategoryReadsAsGone() = runBlocking<Unit> {
        capture()
        val added = Databases.categoryDao(context).add("Kopi", "coffee")
        val breakdown = holder(id = added)
        assertEquals("Kopi", breakdown.landed().category?.name)

        Databases.categoryDao(context).deleteIfUnused(added)

        assertNull(breakdown.landed().category)
    }

    @Test(timeout = 60_000)
    fun aMonthWithADayNotCapturedIsUntrusted() = runBlocking<Unit> {
        capture(missed = setOf(9))
        pay(3_700L, day = 16)

        assertEquals(1, holder().landed().notCaptured)
        assertTrue(breakdown(holder().landed())!!.figures.greyed)
    }

    private companion object {
        val MONTH: YearMonth = YearMonth.of(2026, 9)
        val TODAY: LocalDate = LocalDate.of(2026, 10, 30)
    }
}
