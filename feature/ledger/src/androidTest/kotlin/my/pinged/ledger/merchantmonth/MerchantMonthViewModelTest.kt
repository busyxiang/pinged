package my.pinged.ledger.merchantmonth

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.home.MerchantOpening
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Merchant month's holder over a real ledger (#344): its figures are the
 * merchant's `WHO GOT IT` row across categories, and a merge, a separation, a
 * rename and an exclusion each re-read it in place, as the merchant the key
 * is part of now, without the screen ever having nothing to draw.
 */
@RunWith(AndroidJUnit4::class)
class MerchantMonthViewModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private var food = 0L
    private var groceries = 0L

    @Before fun seed() {
        context.freshLedger()
        val categories = Databases.categoryDao(context).all()
        food = categories.single { it.name == "Food & Drinks" }.id
        groceries = categories.single { it.name == "Groceries" }.id
        (1..MONTH.lengthOfMonth()).forEach {
            Databases.captureDayDao(context).recordListenerBound(LocalDates.of(MONTH.atDay(it)), bound = true)
        }
    }

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    private fun pay(key: String, sen: Long, day: Int, category: Long = food, direction: Direction = Direction.EXPENSE): Long =
        Databases.txnDao(context).insert(
            ledgerTxn(sen, occurredAt = at(day), categoryId = category)
                .copy(merchantRaw = key, merchantDisplay = key.lowercase(), merchantKey = key, direction = direction),
        )

    private fun at(day: Int) = MONTH.atDay(day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun holder(key: String) =
        withContext(Dispatchers.Main) { MerchantMonthViewModel(app, key, MONTH) { TODAY } }

    private suspend fun MerchantMonthViewModel.landed(): MerchantMonthRead {
        withContext(Dispatchers.Main) { read.refresh() }.join()
        return answer()
    }

    private fun MerchantMonthViewModel.answer(): MerchantMonthRead =
        requireNotNull(read.state.value.answer) { "The read published nothing" }

    /** The merchant sheet on [key]'s row, read, as a long press on it opens it. */
    private suspend fun MerchantMonthViewModel.openSheet(key: String) {
        withContext(Dispatchers.Main) { rows.openMerchant(MerchantOpening(key, key, LocalDates.of(at(1)))) }.join()
        withTimeout(10_000) { rows.merchantSheet.first { it?.read != null } }
    }

    @Test(timeout = 60_000)
    fun theMonthIsTheMerchantsRowAcrossCategoriesARefundIncluded() = runBlocking<Unit> {
        pay("FOODPANDA", 4_000L, day = 3)
        pay("FOODPANDA", 2_200L, day = 9, category = groceries)
        pay("FOODPANDA", 1_500L, day = 12, direction = Direction.REFUND)
        pay("ZUS", 900L, day = 5)

        val read = holder("FOODPANDA").landed()

        val row = Databases.txnDao(context).merchantTotals(LocalDates.of(MONTH.atDay(1)), LocalDates.of(MONTH.atEndOfMonth()), Int.MAX_VALUE)
            .single { it.identityKey == "FOODPANDA" }
        val view = merchantMonth(read)
        assertEquals("RM47.00", view.figures.figure)
        assertEquals("3 TRANSACTIONS", view.figures.count)
        assertEquals(listOf(row), read.totals)
        assertEquals(listOf(1_500L, 2_200L, 4_000L), read.rows.map { it.txn.amountSen })
    }

    @Test(timeout = 60_000)
    fun mergingTheMerchantFromItsOwnRowReReadsAsTheOneItJoined() = runBlocking<Unit> {
        pay("YUENKEEHOMETOWNCAFE", 1_250L, day = 3)
        pay("RESTORAN YUEN KEE", 1_500L, day = 4, category = groceries)
        val month = holder("YUENKEEHOMETOWNCAFE")
        assertEquals(listOf(1_250L), month.landed().rows.map { it.txn.amountSen })

        month.openSheet("YUENKEEHOMETOWNCAFE")
        withContext(Dispatchers.Main) { month.rows.mergeMerchant("RESTORAN YUEN KEE") }.join()

        val merged = month.answer()
        assertEquals("RESTORAN YUEN KEE", merged.identity)
        assertEquals(listOf(1_500L, 1_250L), merged.rows.map { it.txn.amountSen })
        assertEquals("RM27.50", merchantMonth(merged).figures.figure)
        assertEquals("2 TRANSACTIONS", merchantMonth(merged).figures.count)
    }

    @Test(timeout = 60_000)
    fun separatingAKeyOffTakesItsRowsAndARenameReReadsTheName() = runBlocking<Unit> {
        pay("YUENKEEHOMETOWNCAFE", 1_250L, day = 3)
        pay("RESTORAN YUEN KEE", 1_500L, day = 4)
        Databases.merchantIdentityDao(context).merge(source = "YUENKEEHOMETOWNCAFE", target = "RESTORAN YUEN KEE", targetName = "Yuen Kee")
        val month = holder("RESTORAN YUEN KEE")
        assertEquals(2, month.landed().rows.size)

        month.openSheet("YUENKEEHOMETOWNCAFE")
        withContext(Dispatchers.Main) { month.rows.separateMerchant("YUENKEEHOMETOWNCAFE") }.join()

        assertEquals("The separated key's row is still here", listOf(1_500L), month.answer().rows.map { it.txn.amountSen })
        assertEquals("RM15.00", merchantMonth(month.answer()).figures.figure)

        month.openSheet("RESTORAN YUEN KEE")
        withContext(Dispatchers.Main) { month.rows.renameMerchant("Restoran Yuen Kee Ampang") }.join()

        assertEquals("Restoran Yuen Kee Ampang", merchantMonth(month.answer()).name)
    }

    @Test(timeout = 60_000)
    fun excludingTheOnlyCountedRowLeavesAZeroMonthUnderItsName() = runBlocking<Unit> {
        val only = pay("FOODPANDA", 4_000L, day = 3)
        val month = holder("FOODPANDA")
        assertEquals("1 TRANSACTION", merchantMonth(month.landed()).figures.count)

        Databases.txnDao(context).excludeByUser(only, updatedAt = 2_000L)

        val view = merchantMonth(month.landed())
        assertEquals("foodpanda", view.name)
        assertEquals("RM0.00", view.figures.figure)
        assertEquals("0 TRANSACTIONS", view.figures.count)
        assertEquals("RM40.00 kept out: 1 excluded, 0 awaiting review", view.figures.keptOut)
        assertTrue(month.answer().rows.isEmpty())
    }

    private companion object {
        val MONTH: YearMonth = YearMonth.of(2026, 9)
        val TODAY: LocalDate = LocalDate.of(2026, 10, 30)
    }
}
