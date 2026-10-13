package my.pinged.ledger.entry

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.data.Databases
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.Transfers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No lease outlasts a gate (`CLAUDE.md`, #108): a Save pressed as a
 * delete-everything closes the gate either finishes before the wipe, which
 * then removes it, or is refused. It is never queued behind the gate to land
 * in the ledger the delete leaves, and it never keeps the delete waiting out
 * `Databases.RESET_LEASE_WAIT_MILLIS` for its lease.
 *
 * The delete is started from inside the save's own lease: [ManualEntries]
 * asks its `context` provider for the ledger first on the caller's thread,
 * then off it under `CaptureStorage.guarded`, so once the chips' read is
 * done, the provider's next call off the main thread is the moment Save has
 * its lease and has not yet written. That is the race the busy check before the guard cannot close.
 */
@RunWith(AndroidJUnit4::class)
class EntryGateRaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun leaveNothingBehind() {
        runBlocking { withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } } }
        Transfers.forgetOutcome()
        scope.cancel()
        context.discardTheDatabase()
    }

    @Test fun aSaveThatMeetsTheRaisedGateIsRefusedAndNotQueued() = runBlocking {
        val race = race(untilGated = true)

        assertNull("A save that met the gate reported a row", race.saved)
        theGateWasNotOutlasted(race)
    }

    /**
     * The delete claimed as Save holds its lease, left to raise its gate when
     * it will: Save gets its write in first or meets the gate, and either way
     * the gate is not outlasted. Repeated, so both ends of the race are met;
     * the counts are logged.
     */
    @Test fun aSaveRacingTheGateFinishesBeforeItOrIsRefused() = runBlocking {
        var written = 0
        var refused = 0
        var longestDelete = 0L
        repeat(ROUNDS) { round ->
            val race = race(untilGated = false, pauseMillis = round % 4L)
            if (race.saved != null) written++ else refused++
            longestDelete = maxOf(longestDelete, race.deleteMillis)
            theGateWasNotOutlasted(race)
        }
        Log.i(TAG, "Save against the delete's gate over $ROUNDS rounds: $written written before it, $refused refused; longest delete ${longestDelete}ms")
        Unit
    }

    private class Race(val saved: Long?, val saveMillis: Long, val deleteMillis: Long)

    /**
     * One Save on a fresh ledger with a delete-everything started from
     * inside its lease; [untilGated] holds the save there until the gate is
     * up, else [pauseMillis] before letting it go on.
     */
    private suspend fun race(untilGated: Boolean, pauseMillis: Long = 0L): Race {
        context.freshLedger()
        val delete = AtomicReference<Deferred<Transfers.Answer>?>(null)
        val deleteStarted = AtomicLong(0L)
        val provider = object : () -> Context {
            @Volatile var armed = false

            override fun invoke(): Context {
                if (armed && Looper.myLooper() != Looper.getMainLooper()) {
                    armed = false
                    deleteStarted.set(SystemClock.elapsedRealtime())
                    delete.set(Transfers.deleteEverything(context, requester = Job()))
                    check(Transfers.state.value.replacingLedger == Operation.DELETE) { "The delete did not claim its turn" }
                    if (untilGated) {
                        val deadline = SystemClock.elapsedRealtime() + TIMEOUT
                        while (!Databases.gated && SystemClock.elapsedRealtime() < deadline) Thread.sleep(1)
                        check(Databases.gated) { "The delete never raised its gate" }
                    } else if (pauseMillis > 0) {
                        Thread.sleep(pauseMillis)
                    }
                }
                return context
            }
        }
        val entries = ManualEntries(context = provider, scope = scope)
        // Armed once the chips' first read is done: it opens the ledger off
        // the main thread too, and Save's lease is the one wanted.
        withTimeout(TIMEOUT) { entries.categories.first { it.isNotEmpty() } }
        provider.armed = true

        val saveStarted = SystemClock.elapsedRealtime()
        val saved = withTimeoutOrNull(SAVE_BUDGET) { entries.save(ManualDraft(amountSen = 1_250L)).await() }
        val saveMillis = SystemClock.elapsedRealtime() - saveStarted
        assertTrue("Save did not answer within ${SAVE_BUDGET}ms: it waited on the gate", saveMillis < SAVE_BUDGET)

        val answer = withTimeout(TIMEOUT) { checkNotNull(delete.get()) { "Save never opened the ledger" }.await() }
        val deleteMillis = SystemClock.elapsedRealtime() - deleteStarted.get()
        assertTrue("The delete did not settle: $answer", answer is Transfers.Answer.Settled)
        withTimeout(TIMEOUT) { Transfers.state.first { it.running == null } }
        Transfers.forgetOutcome()
        return Race(saved, saveMillis, deleteMillis)
    }

    private fun theGateWasNotOutlasted(race: Race) {
        assertTrue(
            "The delete took ${race.deleteMillis}ms: it waited out a lease Save held across its gate",
            race.deleteMillis < Databases.RESET_LEASE_WAIT_MILLIS,
        )
        assertEquals(
            "A save (row ${race.saved}, ${race.saveMillis}ms) landed in the ledger the delete left",
            0,
            Databases.txnDao(context).countAll(),
        )
    }

    private companion object {
        const val TAG = "EntryGateRaceTest"
        const val ROUNDS = 20

        /** A save refused rather than queued returns at once; a queued one waits for the gate. */
        const val SAVE_BUDGET = 5_000L
    }
}
