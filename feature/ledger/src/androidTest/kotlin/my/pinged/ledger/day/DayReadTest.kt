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
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The `Day` screen's read is one snapshot (#81's `Day` screen): its rows and
 * the header's total are read in one transaction, so a commit landing between
 * them is in both or in neither.
 */
@RunWith(AndroidJUnit4::class)
class DayReadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() = context.discardTheDatabase()

    /**
     * A purchase committed after the rows are read and before the day's total
     * would put a total over the rows that leaves one of them out.
     */
    @Test(timeout = 60_000)
    fun aCommitLandingMidReadIsInTheRowsAndTheTotalOrInNeither() = runBlocking<Unit> {
        val dao = context.freshLedger()
        Databases.captureDayDao(context).recordListenerBound(LocalDates.of(DAY.minusDays(10)), true)
        Databases.captureDayDao(context).recordListenerBound(LocalDates.of(DAY), true)
        dao.insert(txn(4_500L))
        val day = withContext(Dispatchers.Main) { DayViewModel(context.applicationContext as Application, DAY) }
        val writer = AtomicReference<Thread?>(null)
        day.midRead = {
            writer.set(thread { Databases.shared(context).txnDao().insert(txn(1_000L)) }.also { it.join(GRACE_MS) })
        }

        withContext(Dispatchers.Main) { day.refresh(TODAY) }.join()
        writer.get()!!.join()

        val read = day.read.value
        assertNotNull("The day's read published nothing", read)
        val seen = read!!.rows.size to read.heading.total
        assertTrue(
            "The header's total and the rows beneath it are from different moments: $seen",
            seen == (1 to "RM45.00") || seen == (2 to "RM55.00"),
        )
    }

    private fun txn(amountSen: Long) = ledgerTxn(
        amountSen = amountSen,
        occurredAt = DAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 10)
        val DAY: LocalDate = TODAY.minusDays(3)

        /** Far past an unblocked insert, which commits in milliseconds. */
        const val GRACE_MS = 500L
    }
}
