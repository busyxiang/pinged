package my.pinged.read

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
 * surfaces as [LedgerAccess.Unreadable], or as [LedgerAccess.BeingReplaced]
 * when it is a ledger replacement's gate. Any other failure escapes into
 * [scope], for the reason `RowActions`' KDoc gives: `SQLiteFullException` is
 * not a database that cannot be opened.
 *
 * **A ledger replacement's claim** ([replacement], #376) is the other half of
 * a replacement, the one the gate cannot see: from the user's tap to the
 * operation's end, which starts before the gate is raised. While it is up,
 * every [write] and [lookup] is refused before [runner] and its holder is told
 * [Outcome.Replacing]; reads go on, and land as [LedgerAccess.Withholding].
 * Refused here, in the turn, so no holder has to remember to, and asked
 * immediately before the runner, so a claim raised while a write waits its
 * turn still refuses it. A claim raised once a write is running is the gate's.
 *
 * When to read again is not decided here: [refresh] is what a screen calls on
 * resume and on `Databases.rewrites`, and [rememberReadState] is the one
 * composable that does, since a holder that re-reads for itself re-reads at the
 * wrong moments (`LedgerViewModel.rebind`).
 *
 * **A read that must not hold the connection** is [staged] instead of built with
 * the constructor: one transaction is Room's one connection and `Databases`'
 * monitor for as long as its body runs, so a body that waits on another store
 * or does work of its own measures itself against `GuardedRunner`'s bound.
 *
 * @param scope where the turn runs; [CoroutineScope.launch]ed bodies end with it.
 * @param runner opens, leases, runs one transaction and guards: see [ReadRunner].
 * @param what the failure's name in the log, for example "The charts cannot be read".
 * @param initial the question asked until [ask] changes it. Nothing is read until
 *   the first [refresh] or [ask].
 * @param replacement the claim: [LedgerReplacements.current] in production; a test passes its own.
 * @param read the question's statements, all through the [PingedDatabase] it is handed
 *   and so inside its one transaction.
 */
@OptIn(RawRunnerAccess::class)
class ScreenRead<Q, A> private constructor(
    private val scope: CoroutineScope,
    private val runner: ReadRunner,
    initial: Q,
    private val replacement: StateFlow<LedgerReplacement?>,
    private val fetch: Fetch<Q, A>,
) {
    constructor(
        scope: CoroutineScope,
        runner: ReadRunner,
        what: String,
        initial: Q,
        replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
        read: (PingedDatabase, Q) -> A,
    ) : this(scope, runner, initial, replacement, Fetch { question -> runner.read(what) { db -> read(db, question) } })

    /** One question's whole read, as the turn runs it: the answer, or the refusal. */
    private fun interface Fetch<Q, A> {
        suspend fun of(question: Q): Outcome<A>
    }

    private val _question = MutableStateFlow(initial)

    /** The question the next read answers: the last one [ask]ed, or `initial`. */
    val question: StateFlow<Q> = _question.asStateFlow()

    /**
     * The last refusal, [LedgerAccess.Unreadable] or [LedgerAccess.BeingReplaced],
     * until a read lands; null after one does. Not in [ReadState], which
     * publishes it merged with the claim.
     */
    private var refusal: LedgerAccess? = null

    private val _state = MutableStateFlow(ReadState<Q, A>(answered = null, rereading = false, ledger = access()))

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

    init {
        // Undispatched, so a claim raised before the first dispatch is not missed.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { replacement.collect { emit { held -> held } } }
    }

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
     * write did: [Outcome.Done] with [body]'s value, [Outcome.Refused]
     * when the database could not be opened, which also publishes
     * [LedgerAccess.Unreadable], or [Outcome.Replacing] when a ledger
     * replacement's gate turned it away, which publishes
     * [LedgerAccess.BeingReplaced]. The re-read follows a refusal too: it is
     * what says whether the database is still unreadable. A failure other
     * than those two refusals completes it exceptionally and escapes into [scope].
     *
     * **During the claim** the write is refused in its turn, immediately
     * before [runner] would be asked: [body] does not run, nothing is
     * published, nothing is read back, and it completes [Outcome.Replacing]
     * (see the class KDoc).
     *
     * [body] stays the caller's own: its statements, its transactions, and
     * whether it is safe to run twice, which the guard may do on a new
     * connection.
     *
     * @param what the write's name in the log, for example "The allow-list cannot be changed".
     * @param oneTransaction see [ReadRunner.write].
     */
    fun <R> write(what: String, oneTransaction: Boolean = false, body: suspend (PingedDatabase) -> R): Deferred<Outcome<R>> {
        val result = CompletableDeferred<Outcome<R>>()
        val asked = _question.value
        val own = open(asked)
        turn {
            try {
                if (replacement.value != null) {
                    close(own)
                    result.complete(Outcome.Replacing)
                    return@turn
                }
                val written = runner.write(what, oneTransaction, body)
                if (written !is Outcome.Done) emit { it.refusedBy(written) }
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

    /**
     * A one-off read whose answer the caller acts on, in the turn: [body] in
     * one transaction, as a read's is, with its own answer and no re-read of
     * the screen. For what feeds a write -- a count shown before a save, the
     * entry a correction starts from -- so it is refused during the claim
     * exactly as [write] is, completing [Outcome.Replacing] and asking
     * [runner] nothing. A refusal by [runner] is the caller's to act on, and
     * is not published: what was refused is not the screen's answer, and
     * drawing the whole screen as unreadable for a count it could not take
     * would claim more than the refusal shows.
     *
     * @param what the refusal's name in the log.
     */
    fun <R> lookup(what: String, body: (PingedDatabase) -> R): Deferred<Outcome<R>> {
        val result = CompletableDeferred<Outcome<R>>()
        turn {
            try {
                if (replacement.value != null) {
                    result.complete(Outcome.Replacing)
                    return@turn
                }
                result.complete(runner.read(what, body))
            } catch (thrown: Throwable) {
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
            if (_question.value == asked) outcome = fetch.of(asked)
        } finally {
            pending.remove(own)
            val landed = outcome
            emit { held ->
                when (landed) {
                    is Outcome.Done ->
                        if (_question.value == asked) {
                            refusal = null
                            held.copy(answered = Answered(asked, landed.value))
                        } else {
                            held
                        }
                    Outcome.Refused, Outcome.Replacing -> held.refusedBy(landed)
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

    /** The state after a refusal: the last one is the one [refusal] keeps. */
    private fun ReadState<Q, A>.refusedBy(outcome: Outcome<*>): ReadState<Q, A> {
        refusal = if (outcome == Outcome.Replacing) LedgerAccess.BeingReplaced else LedgerAccess.Unreadable
        return this
    }

    /** [LedgerAccess]'s precedence: a refusal, else the claim, else readable. */
    private fun access(): LedgerAccess =
        refusal ?: replacement.value?.let(LedgerAccess::Withholding) ?: LedgerAccess.Readable

    /**
     * [transform] on the state, with [ReadState.rereading] worked out from what
     * is asked and pending, and [ReadState.ledger] from [refusal] and the claim.
     */
    private fun emit(transform: (ReadState<Q, A>) -> ReadState<Q, A>) {
        val next = transform(_state.value)
        val current = _question.value
        val rereading = next.answered != null &&
            next.answered.question != current &&
            pending.any { it.question == current }
        _state.value = next.copy(rereading = rereading, ledger = access())
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
            replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
            read: (PingedDatabase) -> A,
        ): ScreenRead<Unit, A> = ScreenRead(scope, runner, what, Unit, replacement) { db, _ -> read(db) }

        /**
         * A read whose statements are not all one transaction: [read] asks
         * [ReadSteps] for each in turn, and does whatever else it must between
         * them -- another store's read, work on what the last step returned --
         * with nothing of the ledger held. Still one turn, one answer, and a
         * refusal at any step is [LedgerAccess.Unreadable] (or
         * [LedgerAccess.BeingReplaced]) with the rest skipped.
         *
         * For what one transaction cannot hold: a body that waits on another
         * store (`DataStore` has no blocking read that is safe inside a
         * transaction), or works at a length that would hold Room's one
         * connection and the monitor against a capture. Statements that must
         * agree with each other still belong in one [ReadSteps.transaction].
         *
         * [read] runs on [dispatcher], not the scope's thread, so blocking or
         * CPU-bound work in it is safe: `Dispatchers.IO`, which the runner's
         * own steps hop to as well. A JVM test passes the dispatcher it drives.
         */
        fun <Q, A> staged(
            scope: CoroutineScope,
            runner: ReadRunner,
            what: String,
            initial: Q,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
            read: suspend ReadSteps.(Q) -> A,
        ): ScreenRead<Q, A> = ScreenRead(scope, runner, initial, replacement) { question ->
            try {
                Outcome.Done(withContext(dispatcher) { RunnerSteps(runner, what).read(question) })
            } catch (refused: StepRefused) {
                refused.outcome
            }
        }

        /** [staged] for a read with no question, as [fixed] is. */
        fun <A> fixedStaged(
            scope: CoroutineScope,
            runner: ReadRunner,
            what: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            replacement: StateFlow<LedgerReplacement?> = LedgerReplacements.current,
            read: suspend ReadSteps.() -> A,
        ): ScreenRead<Unit, A> = staged(scope, runner, what, Unit, dispatcher, replacement) { read() }
    }
}

/**
 * What a [ScreenRead.staged] read may ask of the ledger, one step at a time.
 * Each is guarded and leased by the runner; a database that cannot be opened
 * ends the read as [LedgerAccess.Unreadable], one a ledger replacement holds as
 * [LedgerAccess.BeingReplaced], and no later step runs.
 */
interface ReadSteps {
    /**
     * [body] in **one transaction**, as a plain [ScreenRead] read's is: its
     * statements read one snapshot, and it holds Room's one connection and
     * `Databases`' monitor until it returns. Short and bounded, and nothing
     * in it suspends or waits on another store. [body] runs off the main thread.
     */
    suspend fun <T> transaction(body: (PingedDatabase) -> T): T

    /**
     * [body] guarded and leased, in **no transaction**: each statement is its
     * own snapshot, and a capture takes the connection between them. For
     * statements that need no agreement with each other, or write. [body] may
     * run twice, on a new connection, if the first met damage, so it must be
     * safe to. [body] runs off the main thread.
     */
    suspend fun <T> statements(body: (PingedDatabase) -> T): T
}

/** [ReadSteps] over a [ReadRunner]: a refusal ends the read by this exception, caught by [ScreenRead.staged]. */
@OptIn(RawRunnerAccess::class)
private class RunnerSteps(private val runner: ReadRunner, private val what: String) : ReadSteps {
    override suspend fun <T> transaction(body: (PingedDatabase) -> T): T = runner.read(what, body).orRefuse()

    override suspend fun <T> statements(body: (PingedDatabase) -> T): T =
        runner.write(what) { db -> body(db) }.orRefuse()

    private fun <T> Outcome<T>.orRefuse(): T = when (this) {
        is Outcome.Done -> value
        Outcome.Refused -> throw StepRefused(Outcome.Refused)
        Outcome.Replacing -> throw StepRefused(Outcome.Replacing)
    }
}

/**
 * A step was refused, with the refusal it met, which the read ends with. Not
 * a failure: it is [ScreenRead.staged]'s way to stop at the first one.
 */
private class StepRefused(val outcome: Outcome<Nothing>) : RuntimeException(null, null, false, false)
