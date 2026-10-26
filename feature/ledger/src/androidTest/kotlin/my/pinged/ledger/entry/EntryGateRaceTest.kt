package my.pinged.ledger.entry

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
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
 * **A save that has its lease when the gate rises is handed its instance and
 * finishes**, in the milliseconds its two statements take: the delete's reset
 * waits for that lease and then closes the instance and removes the file,
 * the row with it. Nothing in the block asks the gate again: its DAOs come
 * from the instance it was handed. One that asks after the gate is up is
 * refused at the open.
 *
 * The delete is started from inside the save's lease through
 * [ManualEntries.midSave], where the save holds its lease and has not yet
 * written, or as the save enters its guard, through the `context` provider
 * [ManualEntries] asks for the ledger there. That is the race the busy check
 * before the guard cannot close.
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

    @Test fun aSaveThatHasItsLeaseWhenTheGateRisesFinishesAndTheDeleteLeavesNoRow() = runBlocking {
        val race = race(Start.IN_THE_LEASE, untilGated = true)

        val saved = checkNotNull(race.saved) { "A save that held its lease when the gate rose was refused" }
        assertNull(
            "The row the save wrote ($saved) survived the delete's wipe",
            Databases.txnDao(context).byId(saved),
        )
        theGateWasNotOutlasted(race)
    }

    @Test fun aSaveAskedForAfterTheGateIsUpIsRefusedAndNotQueued() = runBlocking {
        val race = race(Start.AT_THE_GUARD, untilGated = true)

        assertNull("A save that met the gate reported a row", race.saved)
        theGateWasNotOutlasted(race)
    }

    /**
     * The delete claimed as Save holds its lease, left to raise its gate when
     * it will: Save's write is in before the wipe, and the gate is not
     * outlasted. Repeated, so the delete meets Save at several points of its
     * statements; the counts are logged.
     */
    @Test fun aSaveRacingTheGateFinishesBeforeItAndIsWipedWithTheRest() = runBlocking {
        var written = 0
        var refused = 0
        var longestDelete = 0L
        repeat(ROUNDS) { round ->
            val race = race(Start.IN_THE_LEASE, untilGated = false, pauseMillis = round % 4L)
            if (race.saved != null) written++ else refused++
            longestDelete = maxOf(longestDelete, race.deleteMillis)
            theGateWasNotOutlasted(race)
        }
        Log.i(TAG, "Save against the delete's gate over $ROUNDS rounds: $written written before it, $refused refused; longest delete ${longestDelete}ms")
        Unit
    }

    /** Where the delete is started: with the save's lease held, or as it enters its guard, before the lease. */
    private enum class Start { IN_THE_LEASE, AT_THE_GUARD }

    private class Race(val saved: Long?, val saveMillis: Long, val deleteMillis: Long)

    /**
     * One Save on a fresh ledger with a delete-everything started at [start];
     * [untilGated] holds the save there until the gate is up, else
     * [pauseMillis] before letting it go on.
     */
    private suspend fun race(start: Start, untilGated: Boolean, pauseMillis: Long = 0L): Race {
        context.freshLedger()
        val delete = AtomicReference<Deferred<Transfers.Answer>?>(null)
        val deleteStarted = AtomicLong(0L)
        val armed = AtomicBoolean(false)
        val startTheDelete = {
            if (armed.compareAndSet(true, false)) {
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
        }
        val provider = object : () -> Context {
            override fun invoke(): Context {
                if (start == Start.AT_THE_GUARD) startTheDelete()
                return context
            }
        }
        val entries = ManualEntries(context = provider, scope = scope)
        if (start == Start.IN_THE_LEASE) entries.midSave = startTheDelete
        // Armed once the chips' first read is done: it asks for the ledger
        // through the same provider, and Save's guard is the call wanted.
        withTimeout(TIMEOUT) { entries.categories.first { it.isNotEmpty() } }
        armed.set(true)

        val saveStarted = SystemClock.elapsedRealtime()
        val saved = withTimeoutOrNull(SAVE_BUDGET) { entries.save(ManualDraft(amountSen = 1_250L)).await() }
        val saveMillis = SystemClock.elapsedRealtime() - saveStarted
        assertTrue("Save did not answer within ${SAVE_BUDGET}ms: it waited on the gate", saveMillis < SAVE_BUDGET)

        val answer = withTimeout(TIMEOUT) { checkNotNull(delete.get()) { "Save never reached the delete's start" }.await() }
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
