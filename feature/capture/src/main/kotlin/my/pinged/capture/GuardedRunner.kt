package my.pinged.capture

import android.content.Context
import my.pinged.data.DatabaseAccess
import my.pinged.data.PingedDatabase
import my.pinged.read.Outcome
import my.pinged.read.ReadRunner

/**
 * `:core:read`'s [ReadRunner] over `DatabaseAccess`'s `read` and `write`, through
 * [CaptureStorage]'s mapping onto the storage flag (#296, decision 8; #302,
 * decision 6): the open, the lease, the hop to `Dispatchers.IO` and the refusal that
 * every screen's read of the ledger goes through, which live beside `guarded` and
 * not in the module that asks for them. A read that succeeds clears the flag.
 *
 * **`damageStopsCapture` is false, always.** A screen's refusal is not a capture
 * lost, and damage is recorded as damage; see `guarded`'s KDoc. It is not a
 * parameter: no screen has a reason to raise the storage flag.
 *
 * **Only `DatabaseUnavailableException` is caught**, as `guarded` catches it.
 * Anything else -- `SQLiteFullException` among it -- escapes to the caller.
 * A delete's or a restore's gate answers [Outcome.Replacing], every other
 * refusal [Outcome.Refused] (#328).
 */
class GuardedRunner(private val context: Context) : ReadRunner {

    /**
     * [DatabaseAccess.read], so every statement of [body] reads one snapshot,
     * through `CaptureStorage`'s mapping. It holds Room's one connection and so
     * a capture waits for it: [body] is a short, bounded read, whose duration
     * its caller measures.
     */
    override suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T> =
        CaptureStorage.guardedRead<Outcome<T>>(context, what, unavailable = { Outcome.Refused }, replacing = { Outcome.Replacing }) { db ->
            Outcome.Done(body(db))
        }

    /**
     * [body] on the instance the attempt opened, under the same lease, in no
     * transaction of its own unless [oneTransaction]. The policy may run it
     * again on a new connection, so it must be safe to run twice.
     */
    override suspend fun <T> write(what: String, oneTransaction: Boolean, body: suspend (PingedDatabase) -> T): Outcome<T> =
        CaptureStorage.guardedWrite<Outcome<T>>(context, what, unavailable = { Outcome.Refused }, replacing = { Outcome.Replacing }, oneTransaction = oneTransaction) { db ->
            Outcome.Done(body(db))
        }
}
