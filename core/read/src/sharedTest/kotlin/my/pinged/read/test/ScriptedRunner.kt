package my.pinged.read.test

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import my.pinged.data.PingedDatabase
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner

/**
 * A [ReadRunner] a test drives: each call answers at once, is refused, is
 * refused by a ledger replacement, or is held until the test says (#296,
 * decision 8).
 *
 * **The database it hands a body is in memory**, on Robolectric's SQLite and not
 * SQLCipher, and Room builds it lazily: a body that touches no DAO costs
 * nothing. One that does may run on the test's own thread
 * (`allowMainThreadQueries`), and finds the schema and none of the seed:
 * insert what the body reads. Use a device test for anything that needs the
 * real database: snapshot consistency, the lease, SQLCipher.
 *
 * Single-threaded: a test calls it from the thread its `ScreenRead` runs on.
 * Compiled into the JVM tests of `:core:read` and of every module that joins
 * it, as a source directory.
 */
internal class ScriptedRunner(context: Context) : ReadRunner {
    private val database: PingedDatabase =
        Room.inMemoryDatabaseBuilder(context, PingedDatabase::class.java).allowMainThreadQueries().build()

    /** Every call so far, in the order it began. */
    val calls = ArrayList<Call>()

    /** New calls wait for [Call.release] or [Call.refuse] instead of answering. */
    var holding = false

    /** New calls that are not held are refused. */
    var refusing = false

    /** New calls that are not held are refused as a ledger replacement's gate refuses them. Wins over [refusing]. */
    var replacing = false

    /** One read or write [ScriptedRunner] has been asked for. */
    inner class Call(val what: String, val write: Boolean) {
        private val verdict = CompletableDeferred<Outcome<Unit>>()

        /** The body ran, and the caller was handed its answer. */
        var ran = false
            internal set

        /** The caller has its outcome: ran, or was refused. */
        var finished = false
            internal set

        /** Let a held call run its body and answer. */
        fun release() {
            verdict.complete(Outcome.Done(Unit))
        }

        /** Refuse a held call as an unopenable database would. */
        fun refuse() {
            verdict.complete(Outcome.Refused)
        }

        /** Refuse a held call as a ledger replacement's gate would. */
        fun replace() {
            verdict.complete(Outcome.Replacing)
        }

        internal suspend fun waited(): Outcome<Unit> = verdict.await()

        internal fun decided(answer: Outcome<Unit>) {
            verdict.complete(answer)
        }
    }

    override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> =
        enter(what, write = false) { body(database) }

    override suspend fun <T> write(what: String, body: suspend (PingedDatabase) -> T): Outcome<T> =
        enter(what, write = true) { body(database) }

    private suspend fun <T> enter(what: String, write: Boolean, run: suspend () -> T): Outcome<T> {
        val call = Call(what, write)
        calls += call
        if (!holding) {
            call.decided(
                when {
                    replacing -> Outcome.Replacing
                    refusing -> Outcome.Refused
                    else -> Outcome.Done(Unit)
                },
            )
        }
        val outcome = when (val answer = call.waited()) {
            is Outcome.Done -> Outcome.Done(run().also { call.ran = true })
            Outcome.Refused -> Outcome.Refused
            Outcome.Replacing -> Outcome.Replacing
        }
        call.finished = true
        return outcome
    }
}
