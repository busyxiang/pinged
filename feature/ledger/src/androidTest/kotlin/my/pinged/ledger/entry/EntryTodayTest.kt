package my.pinged.ledger.entry

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen's today (#103) is the holder's clock, the one a save is
 * dated by, and it moves at midnight with the screen open: the picker's
 * ceiling and the `Today` chip follow it.
 */
@RunWith(AndroidJUnit4::class)
class EntryTodayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as Application
    private val viewModels = mutableListOf<ManualEntryViewModel>()

    @Volatile private var now = BEFORE_MIDNIGHT

    @Before fun openTheLedger() {
        context.freshLedger()
    }

    @After fun leaveNothingBehind() {
        // So no view model of this test reads on into the next one's.
        viewModels.forEach { it.viewModelScope.cancel() }
        context.discardTheDatabase()
    }

    @Test fun todayIsTheClocksAndMovesAtMidnight() = runBlocking {
        val vm = viewModel()
        assertEquals("Today is not the holder's clock's", DAY, vm.state.value.today)

        now = AFTER_MIDNIGHT

        assertEquals(
            "Today stayed on the day before after midnight passed with the screen open",
            DAY.plusDays(1),
            withTimeoutOrNull(MIDNIGHT_BUDGET) { vm.state.first { it.today == DAY.plusDays(1) } }?.today,
        )
    }

    /**
     * Midnight passing between two of the holder's own reads of the clock: a
     * today worked out from one read and its next midnight from another
     * waited for the midnight after, and `Today` stayed on yesterday until the
     * recheck a minute later. The test above failed this way in 2 runs of 10,
     * its clock moved from the test thread while the holder read it on Main.
     */
    @Test fun midnightPassingWhileTheHolderReadsTheClockIsNotSkipped() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            // Held, so the holder's first read after this test arms the clock is its own.
            val held = CountDownLatch(1)
            executor.execute { held.await() }
            val passOnRead = AtomicBoolean(false)
            val entries = ManualEntries(context = { context }, scope = scope, clock = {
                now.also { if (passOnRead.getAndSet(false)) now = AFTER_MIDNIGHT }
            })
            passOnRead.set(true)
            held.countDown()

            assertEquals(
                "Today skipped the midnight that passed while the holder read the clock",
                DAY.plusDays(1),
                withTimeoutOrNull(MIDNIGHT_BUDGET) { entries.today.first { it == DAY.plusDays(1) } },
            )
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test fun pickingTheClocksTodayIsToday() = runBlocking {
        val vm = viewModel()
        vm.amount(500L)
        vm.day(DAY.minusDays(1))
        withTimeout(TIMEOUT) { vm.state.first { it.draft.day == DAY.minusDays(1) } }

        vm.day(DAY)

        assertNotNull(
            "Picking the holder's today backdated the entry",
            withTimeoutOrNull(MIDNIGHT_BUDGET) { vm.state.first { it.draft.day == null } },
        )
    }

    private fun viewModel() = ManualEntryViewModel(app, SavedStateHandle(), target = null, preselection = { CompletableDeferred() }, clock = { now })
        .also(viewModels::add)

    private companion object {
        /** A day that is not the device's, so the device's clock cannot pass for the holder's. */
        val DAY: LocalDate = LocalDate.of(2026, 8, 15)
        val BEFORE_MIDNIGHT: Long = DAY.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val AFTER_MIDNIGHT: Long = DAY.plusDays(1).atTime(0, 0, 1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        /** The holder looks again at the midnight its clock named, a second after [BEFORE_MIDNIGHT]. */
        const val MIDNIGHT_BUDGET = 5_000L
    }
}
