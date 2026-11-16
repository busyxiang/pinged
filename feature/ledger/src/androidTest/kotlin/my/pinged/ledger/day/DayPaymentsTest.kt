package my.pinged.ledger.day

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.ui.theme.Separator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * The `Day` header's `N PAYMENTS` counts as every total counts (spec 7.4):
 * a payback is in the list on the day it arrived, and in no count, there or
 * on its bill's day. Counted over the day's rows, it was one too many on the
 * day a friend paid back.
 */
@RunWith(AndroidJUnit4::class)
class DayPaymentsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    @Test(timeout = 60_000)
    fun aPaybackIsAPaymentOnNeitherItsOwnDayNorItsBills() = runBlocking<Unit> {
        val dao = context.freshLedger()
        // Both days watched, so the header draws its capture line on each.
        for (day in listOf(BILL_DAY.minusDays(1), BILL_DAY, PAYBACK_DAY)) {
            Databases.captureDayDao(context).recordListenerBound(LocalDates.of(day), true)
        }
        val bill = dao.insert(txn(9_600L, BILL_DAY))
        dao.insert(txn(890L, PAYBACK_DAY))
        val payback = dao.insert(txn(3_200L, PAYBACK_DAY, Direction.REFUND))
        assertEquals(1, dao.setBill(payback, bill, System.currentTimeMillis()))

        assertEquals("The friend's 32.00 is in the list on the 20th and is no payment there", ONE, captureLine(PAYBACK_DAY))
        assertEquals("Nor is it one on its bill's day, where its money counts", ONE, captureLine(BILL_DAY))
    }

    private suspend fun captureLine(date: LocalDate): String? {
        val day = withContext(Dispatchers.Main) { DayViewModel(context.applicationContext as Application, date) { TODAY } }
        withContext(Dispatchers.Main) { day.read.refresh() }.join()
        return day.read.state.value.answer!!.heading.captureLine
    }

    private fun txn(amountSen: Long, day: LocalDate, direction: Direction = Direction.EXPENSE) = ledgerTxn(
        amountSen = amountSen,
        occurredAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    ).copy(direction = direction)

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 30)
        val BILL_DAY: LocalDate = LocalDate.of(2026, 9, 18)
        val PAYBACK_DAY: LocalDate = LocalDate.of(2026, 9, 20)
        const val ONE = "1 PAYMENT" + Separator + WATCHED
    }
}
