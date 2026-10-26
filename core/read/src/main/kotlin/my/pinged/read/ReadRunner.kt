package my.pinged.read

import my.pinged.data.PingedDatabase

/**
 * How a [ScreenRead] reaches the ledger (#296): open the database, lease it,
 * run the statements, and turn a database that cannot be opened into
 * [Outcome.Refused], or one a ledger replacement holds into
 * [Outcome.Replacing]. A seam, so that the ordering, staleness and refusal of
 * a read are tested once, with a runner that answers, refuses or holds on
 * command (`ScriptedRunner`), and a device only has to prove what needs a
 * real database: snapshot consistency and the lease.
 *
 * The production adapter is `GuardedRunner` in `:feature:capture`, beside
 * `CaptureStorage.guarded`, and takes `damageStopsCapture = false` for every
 * call: a screen's refusal is not a capture lost.
 *
 * An implementation catches [my.pinged.data.DatabaseUnavailableException] and
 * nothing else. [ScreenRead] never calls two of these at once.
 */
interface ReadRunner {
    /**
     * Run [body] on the database in **one transaction**, so every statement reads
     * one snapshot and a commit is wholly in the answer or wholly out of it.
     * [body] is blocking, and runs off the main thread.
     *
     * @param what the refusal's name in the log.
     */
    suspend fun <T> read(what: String, body: (PingedDatabase) -> T): Outcome<T>

    /**
     * Run [body] on the database, guarded and leased as [read] is but in **no
     * transaction of its own**: a write whose statements are separate, and
     * whose atomicity is its own business, as `SourcesViewModel`'s allow-list
     * write is. [body] may run twice, on a new connection, if the first met
     * damage.
     *
     * @param what the refusal's name in the log.
     */
    suspend fun <T> write(what: String, body: suspend (PingedDatabase) -> T): Outcome<T>
}

/**
 * What a [ReadRunner] did with a body: ran it, could not open the database to,
 * or was turned away by a ledger replacement.
 */
sealed interface Outcome<out T> {
    /** The body ran, and this is what it returned. */
    data class Done<out T>(val value: T) : Outcome<T>

    /** The database could not be opened or has met damage; the body's work was not done. */
    data object Refused : Outcome<Nothing>

    /**
     * A restore or a delete-everything holds the gate (`DatabaseBeingDeletedException`),
     * so the body's work was not done (#328). Not [Refused]: nothing is wrong
     * with the ledger, the user is replacing it, and a screen says that instead.
     */
    data object Replacing : Outcome<Nothing>
}
