package my.pinged.ledger.entry

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.pinged.data.CaptureEvidence
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.TxnDao
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The date chip's day, as Save writes it (#103, spec 9.4): a backdated entry
 * is that day at the time of day it was entered, filed under that day's
 * `local_date` by the usual function, counted in that day and month, and
 * still never evidence of capture -- even dated before the history start.
 */
@RunWith(AndroidJUnit4::class)
class BackdatedEntryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun anEntryBackdatedThreeDaysIsThatDayAtTodaysTimeAndCountsThere() = runBlocking {
        val dao = context.freshLedger()
        // Three days before 2 September is 30 August: a different month, so
        // the month total that counts it is August's and not the month of Save.
        val threeDaysAgo = LocalDate.of(2026, 8, 30)

        val id = entries(clock = { NOW }).save(ManualDraft(amountSen = 1_250L, day = threeDaysAgo)).await()

        val row = dao.byId(id!!)!!
        assertEquals(
            "The entry is not 30 August at the time of day it was entered",
            millis(LocalDateTime.of(2026, 8, 30, 14, 37, 12, 345_000_000)),
            row.occurredAt,
        )
        assertEquals("The entry is not filed under 30 August", LocalDates.of(threeDaysAgo), row.localDate)
        assertEquals("30 August's subtotal does not count it", 1_250L, dayTotal(dao, threeDaysAgo))
        assertEquals("August's total does not count it", 1_250L, monthTotal(dao, YearMonth.of(2026, 8)))
        assertEquals("The day of Save counted it too", 0L, dayTotal(dao, TODAY))
        assertEquals("The month of Save counted it too", 0L, monthTotal(dao, YearMonth.of(2026, 9)))
    }

    @Test fun anEntryBeforeTheHistoryStartSavesCountsAndMovesNothing() = runBlocking {
        val dao = context.freshLedger()
        val captureDays = Databases.captureDayDao(context)
        val historyStart = LocalDate.of(2026, 8, 10)
        captureDays.recordListenerBound(LocalDates.of(historyStart), bound = true)
        val before = LocalDate.of(2026, 7, 1)

        val id = entries(clock = { NOW }).save(ManualDraft(amountSen = 800L, day = before)).await()

        assertEquals("The entry is not filed under 1 July", LocalDates.of(before), dao.byId(id!!)!!.localDate)
        assertEquals("1 July's subtotal does not count it", 800L, dayTotal(dao, before))
        assertEquals("July's total does not count it", 800L, monthTotal(dao, YearMonth.of(2026, 7)))
        assertEquals(
            "An entry before the history start moved it",
            historyStart,
            CaptureEvidence.historyStart(captureDays, Databases.rawCaptureDao(context)),
        )
        assertEquals(
            "An entry before the history start made its day captured",
            setOf(historyStart),
            CaptureEvidence.capturedDates(captureDays, before, historyStart),
        )
    }

    @Test fun aFutureDayIsNeverSaved() = runBlocking {
        val dao = context.freshLedger()

        val saved = entries(clock = { NOW }).save(ManualDraft(amountSen = 1_250L, day = TODAY.plusDays(1))).await()

        assertNull("An entry dated tomorrow reported a row", saved)
        assertEquals("An entry dated tomorrow was written", 0, dao.countAll())
    }

    @Test fun theViewModelDatesTheDraftOnAPastDayAndOnTodayAndNeverTheFuture() = runBlocking {
        val vm = ManualEntryViewModel(context.applicationContext as Application, SavedStateHandle())
        val today = LocalDate.now()

        vm.day(today.plusDays(1))
        // Draft updates apply in order, so once the amount shows, so has tomorrow.
        vm.amount(500L)
        assertNull(
            "Picking tomorrow dated the draft",
            withTimeout(TIMEOUT) { vm.state.first { it.draft.amountSen == 500L } }.draft.day,
        )

        vm.day(today.minusDays(3))
        assertEquals(
            "Picking three days ago did not backdate the draft",
            today.minusDays(3),
            withTimeout(TIMEOUT) { vm.state.first { it.draft.day != null } }.draft.day,
        )

        vm.day(today)
        assertNull(
            "Picking today did not put the draft back to Today",
            withTimeout(TIMEOUT) { vm.state.first { it.draft.day == null } }.draft.day,
        )
    }

    private fun entries(clock: () -> Long) = ManualEntries(context = { context }, scope = scope, clock = clock)

    private fun dayTotal(dao: TxnDao, day: LocalDate): Long =
        LocalDates.of(day).let { d -> dao.dayTotals(d, d).sumOf { it.netSen } }

    private fun monthTotal(dao: TxnDao, month: YearMonth): Long =
        LocalDates.monthRange(month).let { r -> dao.monthTotals(r.start, r.endInclusive).sumOf { it.netSen } }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 2)

        /** Save's moment: an afternoon with seconds and millis, so a day's start or noon cannot pass for it. */
        val NOW: Long = millis(TODAY.atTime(14, 37, 12, 345_000_000))

        fun millis(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
