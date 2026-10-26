package my.pinged.capture

import android.content.Context
import my.pinged.data.Access
import my.pinged.data.DatabaseAccess
import my.pinged.data.PingedDatabase

/**
 * What capture makes of a database that cannot be opened, or whose connection
 * has met damage: the adapter from [DatabaseAccess]'s refusal onto
 * [CaptureHealth]'s storage flag (#302, decision 5). The policy itself --
 * the lease, the retries, retiring a poisoned instance, recording and
 * announcing damage -- lives in `:core:data`, so that a caller outside capture
 * gets it without this module's flag.
 *
 * Spec 11.1's device-transfer state -- an intact database whose
 * Keystore-wrapped key is gone -- reaches every caller of `Databases`, because
 * `DatabaseFactory.build` is eager and throws from the first access. That is a
 * [my.pinged.data.DatabaseUnavailableException], the whole family: a corrupt
 * file, a full disk and an IO error all mean there is nowhere to put a
 * capture. The policy is stated once here -- record it, and hand the caller
 * something it can render.
 */
object CaptureStorage {
    /**
     * Run [block], or [unavailable] if the database cannot be opened or its
     * connection has met damage. A success clears the flag, so a restored key
     * makes the banner go away.
     *
     * **The open happens in [DatabaseAccess], not in [block]**, so clearing the
     * flag is honest even for the blocks that skip the database; and its
     * `Dispatchers.IO` hop covers [block] whole. `GuardedDispatchTest` pins
     * the hop. What [DatabaseAccess] does about damage, and why, is its KDoc's.
     * Every [block] here is safe to run again, since it may be.
     *
     * **[block] receives the instance the attempt opened, and reaches its DAOs
     * through it**: a retry runs on a new instance, and a DAO resolved through
     * `Databases` instead is answered by whichever instance is served when it
     * is asked, which is the same one only by luck.
     *
     * **[damageStopsCapture]** says whether a refusal raises the storage
     * flag. It does for the callers that store -- a capture damage refused is
     * a capture lost, and dropped silently it is this object's original bug
     * again -- and not for the screens' reads or stage two, whose damage is
     * recorded as damage: a damaged index under the backup nudge's count
     * would otherwise put "nothing new is being recorded" up on every
     * foreground while capture works. The same holds for a delete's or a
     * restore's gate ([Access.Why.Gate]): it raises the flag for a capture,
     * which it drops, and not for the rest, which lose nothing by it. Every
     * other refusal raises it for every caller, since none of them clears on
     * its own. `GateRefusalTest`.
     *
     * **[replacing]** is what a gate's refusal ([Access.Why.Gate]) answers, in
     * place of [unavailable]: a screen says a ledger replacement is running
     * rather than that the ledger cannot be read (#328). It defaults to
     * [unavailable], which capture and `LedgerWrites` keep.
     */
    suspend fun <T> guarded(
        context: Context,
        what: String,
        unavailable: () -> T,
        damageStopsCapture: Boolean = true,
        replacing: () -> T = unavailable,
        block: suspend (PingedDatabase) -> T,
    ): T = mapped(context, unavailable, replacing, damageStopsCapture, DatabaseAccess.production(context).write(what, block = block))

    /**
     * [guarded] without its hop to `Dispatchers.IO`, for stage one's insert
     * alone. `PingedNotificationListener` runs capture on one thread so that
     * captures are written in the order they arrived, and the insert runs on
     * it; on the IO pool two could land out of order.
     */
    internal suspend fun <T> guardedInOrder(
        context: Context,
        what: String,
        unavailable: () -> T,
        block: suspend (PingedDatabase) -> T,
    ): T = mapped(
        context, unavailable, replacing = unavailable, damageStopsCapture = true,
        DatabaseAccess.production(context).writeOnThisThread(what, block),
    )

    /**
     * [DatabaseAccess.read] under the same mapping, for a screen's read: no
     * flag is raised by a refusal, and a success clears it, as [guarded] does.
     * [block] reads in one transaction.
     */
    internal suspend fun <T> guardedRead(
        context: Context,
        what: String,
        unavailable: () -> T,
        replacing: () -> T = unavailable,
        block: (PingedDatabase) -> T,
    ): T = mapped(
        context, unavailable, replacing, damageStopsCapture = false,
        DatabaseAccess.production(context).read(what, block),
    )

    /**
     * [guarded] for a screen's write, which wants the instance the attempt
     * opened: no flag is raised by a refusal. [oneTransaction] is
     * [DatabaseAccess.write]'s.
     */
    internal suspend fun <T> guardedWrite(
        context: Context,
        what: String,
        unavailable: () -> T,
        replacing: () -> T = unavailable,
        oneTransaction: Boolean = false,
        block: suspend (PingedDatabase) -> T,
    ): T = mapped(
        context, unavailable, replacing, damageStopsCapture = false,
        DatabaseAccess.production(context).write(what, oneTransaction, block),
    )

    /**
     * The storage flag after [result], then the caller's own answer. Cleared on
     * a success, which is the one place that clears it; raised on a refusal
     * as [guarded]'s KDoc says.
     */
    private suspend fun <T> mapped(
        context: Context,
        unavailable: () -> T,
        replacing: () -> T,
        damageStopsCapture: Boolean,
        result: Access<T>,
    ): T = when (result) {
        is Access.Done -> {
            CaptureHealth.clearStorageUnavailable(context)
            result.value
        }
        is Access.Refused -> {
            // A gate is a delete or a restore, not damage: a screen refused by
            // one has lost nothing, and the banner would stay up until the
            // next guarded call succeeds. A capture refused by one is lost.
            // Only the rest of the unavailable family is raised for everyone.
            val raise = when (result.why) {
                Access.Why.Damage, Access.Why.Gate -> damageStopsCapture
                Access.Why.Unavailable -> true
            }
            if (raise) CaptureHealth.recordStorageUnavailable(context)
            if (result.why == Access.Why.Gate) replacing() else unavailable()
        }
    }

    /**
     * [DatabaseAccess.ATTEMPTS], under the name this module's tests read it by
     * (`DamagedConnectionTest`).
     */
    internal const val ATTEMPTS = DatabaseAccess.ATTEMPTS
}
