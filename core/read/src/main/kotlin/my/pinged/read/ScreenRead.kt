package my.pinged.read

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.pinged.data.PingedDatabase

/**
 * A screen's one-shot read of the ledger (#296): a question goes in, an answer
 * comes out, and [state] says where that stands.
 *
 * **One turn.** Every read, and every write from [write], runs one at a time
 * in the order it was asked for, so an older answer can never land over a
 * newer one and a write is read back by the read that follows it. Spec 9.6's
 * consent rests on it: two taps on one toggle land in the order made, and a
 * read never publishes a snapshot older than a write that landed. Two holes
 * would each look like a simplification, and `TurnTest` holds both:
 *
 * - **The chain is a signal, and the wait for it is [NonCancellable].** `Job.join()`
 *   is cancellable and a cancelled `Job` completes, so a chain built on the
 *   predecessor's `Job` severs the moment a link still waiting is cancelled, and
 *   everything behind it runs beside the operation that link was waiting for.
 *   Waiting inside [NonCancellable] means cancelling a link cannot skip its wait,
 *   and it still cancels promptly at its body's first suspension point. The signal
 *   completes in a `finally`, so a link that throws or is cancelled releases the
 *   queue, but only after it has itself waited.
 * - **A place is claimed by the call, not by the coroutine it launches.** Claimed
 *   inside the coroutine it is claimed when the dispatcher gets to it, which moves
 *   the race rather than closing it.
 *
 * A read in flight is not cancelled by the next one: cancel-and-restart would
 * save a read per cold launch and cost the order, because a `join()` on a
 * cancelled read returns at once. Two reads queue instead, the later publishing
 * last.
 *
 * **The current question** is [question]. A read of a question that has
 * changed by the time it lands publishes nothing, and one that has not yet
 * started is never run. A screen with no question to ask passes a fixed
 * one, as [fixed] does.
 *
 * **Threads.** [ask], [refresh] and [write] must be called from [scope]'s own
 * thread, which is the main thread for `viewModelScope`: the order of the
 * turn is the order of the calls, and the chain's tail has no lock. Nothing
 * enforces it: a caller on another thread is safe only while it is the only
 * caller. [state] is written there too. The read body runs wherever [runner]
 * runs it.
 *
 * **What is not caught.** Only [my.pinged.data.DatabaseUnavailableException]
 * is, by the production runner as `CaptureStorage.guarded` does, and it
 * surfaces as [ReadState.unavailable]. Any other failure escapes into
 * [scope], for the reason `RowActions`' KDoc gives: `SQLiteFullException` is
 * not a database that cannot be opened.
 *
 * When to read again is not decided here: [refresh] is what a screen calls on
 * resume and on `Databases.rewrites`, and [rememberReadState] is the one
 * composable that does, since a holder that re-reads for itself re-reads at the
 * wrong moments (`LedgerViewModel.rebind`).
 *
 * @param scope where the turn runs; [CoroutineScope.launch]ed bodies end with it.
 * @param runner opens, leases, runs one transaction and guards: see [ReadRunner].
 * @param what the failure's name in the log, for example "The charts cannot be read".
 * @param initial the question asked until [ask] changes it. Nothing is read until
 *   the first [refresh] or [ask].
 * @param read the question's statements, all through the [PingedDatabase] it is handed
 *   and so inside its one transaction.
 */
class ScreenRead<Q, A>(
    private val scope: CoroutineScope,
    private val runner: ReadRunner,
    private val what: String,
    initial: Q,
    private val read: (PingedDatabase, Q) -> A,
) {
    private val _question = MutableStateFlow(initial)

    /** The question the next read answers: the last one [ask]ed, or `initial`. */
    val question: StateFlow<Q> = _question.asStateFlow()

    private val _state = MutableStateFlow(ReadState<Q, A>(answered = null, rereading = false, unavailable = false))

    /** Where the read stands. See [ReadState]. */
    val state: StateFlow<ReadState<Q, A>> = _state.asStateFlow()

    /** A read, or a write's read, asked for and not yet landed, refused or dropped. */
    private inner class Pending(val question: Q)

    private val pending = ArrayList<Pending>()

    /**
     * The tail of the turn: completed when the last operation asked for has
     * ended, whichever way. Only written on [scope]'s thread, as the call is.
     */
    private var tail: CompletableDeferred<Unit>? = null

    /**
     * Tests only: a suspension point at the head of each operation's turn, after
     * the one before it has ended and before its own body. The order is invisible
     * when every operation takes the same few milliseconds, so holding the first
     * one open is what makes the second's position observable.
     */
    @VisibleForTesting
    var beforeEachOperation: suspend () -> Unit = {}

    /**
     * Change the question to [question] and read it. The answer held stays
     * until this one lands, and [ReadState.rereading] says so meanwhile. The
     * returned `Job` completes when the read has landed, been refused or been
     * dropped for a newer question.
     */
    fun ask(question: Q): Job {
        _question.value = question
        return readOf(question)
    }

    /**
     * Change the question to [question] **without reading it**, for a screen
     * whose question is "nothing to show", as a merchant sheet that has been
     * closed. A read running for the old question publishes nothing, one still
     * waiting its turn is never run, and nothing is queued. The answer held
     * stays, with the question it answered, so the next [ask] can be told
     * apart from it.
     */
    fun setQuestion(question: Q) {
        _question.value = question
        emit { held -> held }
    }

    /**
     * Read the current question again: a resume, a restore, or whatever else
     * may have changed what the ledger holds. Never sets [ReadState.rereading],
     * whose answer is for the same question.
     */
    fun refresh(): Job = readOf(_question.value)

    /**
     * Run [body] through [runner]'s write, then read the current question
     * again in the same turn, so the read that follows is the one that sees
     * it and no other read of the screen lands between them.
     *
     * The returned `Deferred` completes after that re-read, with what the
     * write did: [Outcome.Done] with [body]'s value, or [Outcome.Refused]
     * when the database could not be opened, which also raises
     * [ReadState.unavailable]. The re-read follows a refusal too: it is what
     * says whether the database is still unreadable. A failure other than
     * that refusal completes it exceptionally and escapes into [scope].
     *
     * [body] stays the caller's own: its statements, its transactions, and
     * whether it is safe to run twice, which the guard may do on a new
     * connection.
     *
     * @param what the write's name in the log, for example "The allow-list cannot be changed".
     */
    fun <R> write(what: String, body: suspend (PingedDatabase) -> R): Deferred<Outcome<R>> {
        val result = CompletableDeferred<Outcome<R>>()
        val asked = _question.value
        val own = open(asked)
        turn {
            try {
                val written = runner.write(what, body)
                if (written is Outcome.Refused) emit { it.copy(unavailable = true) }
                readInTurn(asked, own)
                result.complete(written)
            } catch (thrown: Throwable) {
                // Idempotent: [readInTurn] closed it already on its own paths.
                close(own)
                result.completeExceptionally(thrown)
                throw thrown
            }
        }
        return result
    }

    private fun readOf(asked: Q): Job {
        val own = open(asked)
        return turn { readInTurn(asked, own) }
    }

    /**
     * The read itself, inside the turn: dropped unstarted if the question
     * has moved on since [asked], and its answer dropped if it moves on
     * while it runs. Closes [own] and emits exactly once, on every exit.
     */
    private suspend fun readInTurn(asked: Q, own: Pending) {
        var outcome: Outcome<A>? = null
        try {
            if (_question.value == asked) outcome = runner.read(what) { db -> read(db, asked) }
        } finally {
            pending.remove(own)
            val landed = outcome
            emit { held ->
                when (landed) {
                    is Outcome.Done ->
                        if (_question.value == asked) {
                            held.copy(answered = Answered(asked, landed.value), unavailable = false)
                        } else {
                            held
                        }
                    Outcome.Refused -> held.copy(unavailable = true)
                    null -> held
                }
            }
        }
    }

    private fun open(asked: Q): Pending = Pending(asked).also {
        pending += it
        emit { held -> held }
    }

    private fun close(own: Pending) {
        if (pending.remove(own)) emit { held -> held }
    }

    /** [transform] on the state, with [ReadState.rereading] worked out from what is asked and pending. */
    private fun emit(transform: (ReadState<Q, A>) -> ReadState<Q, A>) {
        val next = transform(_state.value)
        val current = _question.value
        val rereading = next.answered != null &&
            next.answered.question != current &&
            pending.any { it.question == current }
        _state.value = next.copy(rereading = rereading)
    }

    private fun turn(block: suspend () -> Unit): Job {
        val previous = tail
        val mine = CompletableDeferred<Unit>()
        tail = mine
        return scope.launch {
            try {
                withContext(NonCancellable) { previous?.await() }
                beforeEachOperation()
                block()
            } finally {
                mine.complete(Unit)
            }
        }
    }

    companion object {
        /**
         * A read with no question to ask, as Detail's and Learned's: the
         * question is always [Unit], so [ReadState.rereading] is never set and
         * only [ScreenRead.refresh] and [ScreenRead.write] move it.
         */
        fun <A> fixed(
            scope: CoroutineScope,
            runner: ReadRunner,
            what: String,
            read: (PingedDatabase) -> A,
        ): ScreenRead<Unit, A> = ScreenRead(scope, runner, what, Unit) { db, _ -> read(db) }
    }
}
