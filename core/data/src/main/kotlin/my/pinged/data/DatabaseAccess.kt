package my.pinged.data

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * What [DatabaseAccess] did with a block: ran it, or refused it and says why.
 * Its own type, because `:core:read` depends on this module and has an
 * `Outcome` of its own (#302, decision 5).
 */
sealed interface Access<out T> {
    /** The block ran, and this is what it returned. */
    data class Done<out T>(val value: T) : Access<T>

    /** The block's work was not done, for [why]; [cause] is what said so. */
    data class Refused(val why: Why, val cause: Throwable) : Access<Nothing>

    /** The three reasons the policy refuses, which a caller maps onto its own. */
    enum class Why {
        /**
         * A delete or a restore holds [Databases.whileDeleting]
         * ([DatabaseBeingDeletedException]). Not damage: a caller that is not
         * storing anything has lost nothing by it.
         */
        Gate,

        /**
         * [DatabaseAccess.ATTEMPTS] instances in a row, or the first one when
         * the block's own statement met the damage, could not be used: the
         * connection was poisoned or closed under the block.
         */
        Damage,

        /** Any other [DatabaseUnavailableException]: the key is gone, or the file will not open. */
        Unavailable,
    }
}

/**
 * The one place that says what happens when a statement meets a connection
 * that has met damage (#302, decisions 1-4, 7). It owns the hop to
 * [Dispatchers.IO], the lease, the retries, retirement of a poisoned instance,
 * and recording and announcing the damage; a caller says what to do with its
 * refusal. `CaptureStorage.guarded` is the adapter that maps one onto the
 * storage flag, and `GuardedRunner` is the adapter over that.
 *
 * Spec 11.1's device-transfer state -- an intact database whose Keystore-wrapped
 * key is gone -- reaches every caller, because `DatabaseFactory.build` is eager
 * and throws from the first access. That and the rest of the
 * [DatabaseUnavailableException] family come back as [Access.Refused], never
 * as a throw.
 *
 * **Every block receives the instance it was run on**, and reaches its DAOs
 * through it, so a retry on a new instance cannot be answered by the old one.
 * Every block here is safe to run again: capture's writes are an insert that
 * did not commit and idempotent updates, the allow-list toggle an insert-if-new
 * and two targeted updates, and the rest are reads or stage two, whose work is
 * the table.
 *
 * **The order on damage is retire, then any retry, then record, then
 * announce** (decision 3). Announcing after the retry has run on the new
 * instance is the one order with a reason behind it: the ledger feed rebinds
 * on the announcement, and a feed whose next page is the damaged one would
 * otherwise poison the new instance first. Recording is best effort and its
 * place is free. That order is a [DamageScope]'s exit: [read] and [write] open
 * one, and one opened inside another joins it (decision 4).
 *
 * ## A connection that has met damage
 *
 * Measured on emulator-5554, SQLCipher 4.18.0: a read that meets a damaged page
 * throws code 11, inside a transaction or out, and its connection answers every
 * statement after with code 26 until the instance is closed -- a second
 * instance opened beside it reads normally. Any read on the shared instance can
 * do that, the ledger feed scrolling into an old month among them. Let through,
 * code 26 was measured to cost every notification after it -- one log line each
 * in the listener's handler, nothing stored, the storage flag clear -- to keep
 * stage two answering `retry()` for the life of the process, and to kill the
 * app from the ledger's refresh.
 *
 * So [Databases.poisons] from a block -- after the open, which is what
 * separates it from code 26 at open, the unreadable state -- retires the
 * instance ([Databases.retire]) and runs the block again on a new one.
 *
 * **Which code decides whether to try again.** Code 11 is the block's own
 * statement reading the damaged page: on its own path, which a new connection
 * meets every time, so it is refused at once. Code 26 is a connection some
 * *other* read poisoned -- a screen reading a damaged month -- and says nothing
 * about the block's path, so it is tried again, up to [ATTEMPTS] in all. One
 * retry is not enough: a screen reading beside the capture can poison the
 * instance the retry just opened before the retry's first statement runs,
 * refusing a capture nothing of whose reads is damaged. So a capture is refused
 * for someone else's damage only if [ATTEMPTS] instances in a row were each
 * poisoned first, by as many damaged reads.
 *
 * **An instance closed under a block** answers code 21 or
 * `IllegalStateException`, and is tried again the same way, once the instance
 * the attempt opened is seen to be closed; the gate then refuses it, as if the
 * block had arrived a moment later. Nothing closes one under the whole
 * attempt's [Databases.leasing] lease but a [Databases.reset] that has waited
 * out its bound for it: a replacement for damage never closes one, and a reset
 * for a delete or a restore waits for the lease.
 *
 * **What this costs beside a damaged month on screen.** The ledger feed rebinds
 * onto each new instance and reads its damaged page there, poisoning it again,
 * so every write after that meets code 26 once, replaces the instance once, and
 * costs the feed one reload. Measured on emulator-5554 beside the ledger open
 * on a damaged month: 121 of 121 captures took one retry, and
 * [Databases.generation] moved 121 times. Accepted: one open, 4ms (`OpenTest`),
 * and one page a write, bounded by the writes, while the damage is on record
 * for the settings screen, which is the way out.
 *
 * Memoized by [Databases], so after the first call opening is a volatile read.
 */
class DatabaseAccess internal constructor(private val source: Source) {

    /**
     * The instances and the lease behind a [DatabaseAccess]: [Databases]' state
     * in production, and in `DatabaseAccessTest` an in-memory database it
     * scripts. Internal, so nothing outside this module reaches an instance,
     * a retirement or an announcement except through the policy.
     */
    internal interface Source {
        /** The current instance, built on first use; throws a [DatabaseUnavailableException]. */
        fun instance(): PingedDatabase

        /** The current instance if one has been built, and never builds one: for a check made on the caller's thread. */
        fun current(): PingedDatabase?

        /** [block] in one transaction on the current instance, as [Databases.inOneTransaction] runs it. */
        fun <T> inOneTransaction(block: (PingedDatabase) -> T): T

        /** Stops serving [poisoned] if it is still current, without closing it; whether it did. */
        fun retire(poisoned: PingedDatabase): Boolean

        /** Tells every holder bound to an instance that one has been replaced. */
        fun announceReplacement()

        /** One more block is using instances; none a [retire] sets aside is closed until it [release]s. */
        fun lease()

        /** [lease]'s end. */
        fun release()

        /** Puts the damage on record for the settings screen; best effort, never throws for storage. */
        suspend fun recordDamage()
    }

    /**
     * [block] in **one transaction**, so every statement reads one snapshot
     * ([Databases.inOneTransaction]). It holds Room's one connection and so a
     * capture waits for it: [block] is a short, bounded read, whose duration
     * its caller measures. Runs on [Dispatchers.IO], which covers [block]
     * whole: Room's `assertNotMainThread` throws a plain `IllegalStateException`
     * from a query on the main thread, outside [DatabaseUnavailableException]
     * and so past every catch here, and on a bare `SupervisorJob` it kills the
     * process. `GuardedDispatchTest` pins the hop; `MainThreadRefreshTest` and
     * `LaunchTest` pin the callers.
     *
     * **Never from inside a transaction on the current instance**: this takes
     * [Databases]' monitor and then waits for the connection, and the other
     * order deadlocks. **It throws [IllegalStateException] there instead**, on
     * the calling thread and before the hop, because the hop is what would
     * make the deadlock: the caller's transaction holds Room's one connection
     * and the IO thread that takes the monitor waits for it.
     *
     * Measured on emulator-5554 (API 37), Room 2.8.4 over SQLCipher 4.18.0,
     * `InTransactionTest`: `PingedDatabase.inTransaction()` reports the
     * *calling thread's* transaction and nothing else's. False outside any
     * transaction and after one ends, true inside `runInTransaction` on the
     * same thread, false inside a [write] block (which opens none) and true
     * inside a [read] block. Asked from a second thread while the first held
     * a transaction it answered false at once, where it was bounded at 3s, so
     * it does not wait for the connection. On a closed instance it answers
     * false and leaves it closed, where `runInTransaction` reopens one
     * (`Databases.whileLive`). So the check cannot refuse a caller that is
     * not in a transaction, and does not hang one that is. What it cannot see
     * is a transaction Room runs from a coroutine (`withTransaction`), which
     * nothing here uses.
     *
     * **Only this entry and [write] with `oneTransaction` assert the rule.**
     * `Databases.whileLive`, [DamageScope.replace]'s `retire` and `reset` take
     * the same monitor and deadlock the same way from inside a transaction,
     * and they do not throw: the measurement above was made of these two
     * entries and of nothing else (decision 10), so the rule for the rest
     * stays in their KDocs.
     *
     * @param what the refusal's name in the log.
     * @throws IllegalStateException from inside a transaction on the current instance.
     */
    suspend fun <T> read(what: String, block: (PingedDatabase) -> T): Access<T> {
        refuseInsideATransaction("read")
        return withContext(Dispatchers.IO) { attempt(what) { source.inOneTransaction(block) } }
    }

    /**
     * [block] on the instance the attempt opened. By default in no transaction
     * of its own: a write whose statements are separate, and whose atomicity
     * is its own business. Runs on [Dispatchers.IO] as [read] does.
     *
     * **[oneTransaction] is for a write that checks something and then writes
     * on what it found** (#302, decision 17): `RowActions.assignCategory`
     * asks whether the category still exists and files the row under it, and a
     * delete from Settings must not land between the two. [block] then runs as
     * [read]'s does -- [Databases]' monitor, then one transaction, which Room
     * rolls back whole if [block] throws -- and [read]'s rule with it: **never
     * from inside a transaction on the current instance**, which throws
     * [IllegalStateException] on the calling thread for [read]'s reason.
     *
     * **A [block] that is in a transaction runs on this thread to its end**,
     * through `runBlocking`: the transaction belongs to the thread that began
     * it, so a statement from another thread would wait for the connection
     * this one holds. It issues blocking statements and does not switch
     * dispatchers. The monitor is held for its duration as [read]'s is, so it
     * is a short, bounded write.
     *
     * @param what the refusal's name in the log.
     * @param oneTransaction whether [block]'s statements are one transaction.
     * @throws IllegalStateException from inside a transaction on the current instance, when [oneTransaction].
     */
    suspend fun <T> write(
        what: String,
        oneTransaction: Boolean = false,
        block: suspend (PingedDatabase) -> T,
    ): Access<T> {
        if (!oneTransaction) return withContext(Dispatchers.IO) { attempt(what, block) }
        refuseInsideATransaction("write(oneTransaction = true)")
        return withContext(Dispatchers.IO) {
            attempt(what) { source.inOneTransaction { db -> runBlocking { block(db) } } }
        }
    }

    /**
     * [block] on the served instance, under a lease that outlasts it, with no
     * retry and no refusal: what [block] throws is thrown, a
     * [DatabaseUnavailableException] from the open included, and nothing is
     * replaced, recorded or announced by it. For a holder that is bound to an
     * instance across calls and answers for its own failures -- the ledger
     * feed's pages and its day subtotals, settings' counts -- where [read] and
     * [write]'s retry onto another instance would hand the holder an answer
     * from one it is not bound to (decision 8).
     *
     * **The lease is taken before the instance is asked for**, so none this
     * call is handed can be closed under [block]: the last lease out closes
     * what [Databases.retire] set aside, and one retired between the open and
     * a later lease would be. A holder that replaces an instance for damage
     * does it inside a [damageScope] around this call, and the scope announces
     * once the lease has ended.
     *
     * Does not hop to [Dispatchers.IO]: its callers are already on it, for the
     * statements and for the release, which is where a retired instance is
     * closed.
     */
    suspend fun <T> leased(block: suspend (PingedDatabase) -> T): T {
        source.lease()
        try {
            return block(source.instance())
        } finally {
            source.release()
        }
    }

    /** [read]'s rule, for [entry] and for [write] when it opens a transaction. */
    private fun refuseInsideATransaction(entry: String) {
        check(source.current()?.inTransaction() != true) {
            "DatabaseAccess.$entry was called from inside a transaction on the current instance, " +
                "which deadlocks: its IO thread waits for the connection this one holds"
        }
    }

    /**
     * [write] without its hop to [Dispatchers.IO], for stage one's insert
     * alone. `PingedNotificationListener` runs capture on one thread so that
     * captures are written in the order they arrived, and the insert runs on
     * it; on the IO pool two could land out of order (decision 7).
     *
     * @param what the refusal's name in the log.
     */
    suspend fun <T> writeOnThisThread(what: String, block: suspend (PingedDatabase) -> T): Access<T> =
        attempt(what, block)

    /**
     * Runs [block] in a scope that records the damage it replaced, once, and
     * then announces it, once, when it exits -- in that order, whether it
     * returns, throws or is cancelled. [read] and [write] open one themselves,
     * and a scope opened inside another joins the outer one: a `ParseWorker`
     * run that replaces on every pass records and announces once, however many
     * passes it made (decision 4).
     *
     * Nothing is recorded or announced for a scope that met no damage, and
     * nothing is announced for one whose [DamageScope.replace] found the
     * instance already replaced by someone else.
     */
    suspend fun <T> damageScope(block: suspend DamageScope.() -> T): T {
        coroutineContext[DamageScope]?.let { outer -> return outer.block() }
        val scope = DamageScope(source)
        try {
            return withContext(scope) { scope.block() }
        } finally {
            // Not cancellable: damage on a connection is news whatever became of
            // the caller, and the feed has to hear that the instance moved.
            withContext(NonCancellable) { scope.exit() }
        }
    }

    /**
     * What a [damageScope] knows: whether anything met damage, and whether any
     * instance was replaced for it. A coroutine context element, which is how
     * a scope opened inside another finds it.
     */
    class DamageScope internal constructor(private val source: Source) : AbstractCoroutineContextElement(DamageScope) {
        @Volatile private var damaged = false
        @Volatile private var replaced = false

        /**
         * Says [damagedInstance] met damage and stops serving it at once
         * ([Databases.retire]), without closing it: other threads may be using
         * it, and the last lease out closes it. Returns whether it was still the
         * instance being served; one already replaced by another caller is not
         * retired again, but the damage is on record all the same, since it is
         * still news.
         *
         * **Compare-and-replace.** Retiring whatever is current instead would
         * retire a healthy instance under someone who has just opened it.
         * Announces nothing: the scope does, on exit.
         */
        fun replace(damagedInstance: PingedDatabase): Boolean {
            damaged = true
            val retired = source.retire(damagedInstance)
            if (retired) replaced = true
            return retired
        }

        /**
         * Says damage was met that has no instance of this scope's to retire:
         * a read that threw code 11 on its own path, which a new connection
         * meets again, so the instance it ran on stays served. The scope
         * records on exit and, having replaced nothing, announces nothing.
         */
        fun noteDamage() {
            damaged = true
        }

        internal suspend fun exit() {
            if (damaged) source.recordDamage()
            if (replaced) source.announceReplacement()
        }

        /** The key a nested [damageScope] looks its outer one up by. */
        companion object Key : CoroutineContext.Key<DamageScope>
    }

    private suspend fun <T> attempt(what: String, block: suspend (PingedDatabase) -> T): Access<T> {
        source.lease()
        try {
            return damageScope { tries(what, block) }
        } finally {
            source.release()
        }
    }

    /** Every attempt of [attempt]: the part that runs before the scope's record and announcement. */
    private suspend fun <T> DamageScope.tries(what: String, block: suspend (PingedDatabase) -> T): Access<T> {
        try {
            lateinit var met: RuntimeException
            var attempts = 0
            for (attempt in 1..ATTEMPTS) {
                attempts = attempt
                // The open happens here, not in [block]: blocks that skip the
                // database are normal (`CaptureDays`' two writers both return
                // early once the day is recorded, which is every notification
                // but the first each day), and a caller that clears a flag on
                // success is only honest if the database was actually tried.
                val opened = source.instance()
                met = try {
                    return Access.Done(block(opened))
                } catch (thrown: DatabaseUnavailableException) {
                    throw thrown
                } catch (thrown: CancellationException) {
                    // An `IllegalStateException` too, and never a retry.
                    throw thrown
                } catch (thrown: RuntimeException) {
                    thrown
                }
                val ownPath = when {
                    Databases.poisons(met) -> {
                        replace(opened)
                        met is SQLiteDatabaseCorruptException
                    }
                    // `SQLException` is Room's code 21, the superclass of
                    // `SQLiteException`; the pool's own refusal is an
                    // `IllegalStateException`. Either only once the instance
                    // is seen closed, so a programming error is not retried.
                    (met is SQLException || met is IllegalStateException) && opened.closed -> false
                    else -> throw met
                }
                if (ownPath) break
                if (attempt < ATTEMPTS) {
                    Log.w(TAG, "$what: attempt $attempt met a connection it cannot use; trying a new one", met)
                }
            }
            Log.e(TAG, "$what: refused after $attempts attempts", met)
            return Access.Refused(Access.Why.Damage, met)
        } catch (thrown: DatabaseUnavailableException) {
            Log.e(TAG, "$what: " + thrown.message, thrown)
            // A retry's open can meet a gate, or a file that will not open,
            // after an attempt met damage; that damage is still news, and the
            // scope records it.
            return Access.Refused(
                if (thrown is DatabaseBeingDeletedException) Access.Why.Gate else Access.Why.Unavailable,
                thrown,
            )
        }
    }

    /** [Databases], as a [Source]. */
    private class DatabasesSource(private val context: Context) : Source {
        @OptIn(RawDatabaseAccess::class)
        override fun instance(): PingedDatabase = Databases.shared(context)
        override fun current(): PingedDatabase? = Databases.current()
        override fun <T> inOneTransaction(block: (PingedDatabase) -> T): T = Databases.inOneTransaction(context, block)
        @OptIn(RawDatabaseAccess::class)
        override fun retire(poisoned: PingedDatabase): Boolean = Databases.retire(poisoned)
        override fun announceReplacement() = Databases.announceReplacement()
        override fun lease() = Databases.lease()
        override fun release() = Databases.release()
        override suspend fun recordDamage() = IntegrityStore.recordDamage(context)
    }

    companion object {
        private const val TAG = "DatabaseAccess"

        /** The policy over [Databases]' state, which is what production runs. */
        fun production(context: Context): DatabaseAccess = DatabaseAccess(DatabasesSource(context.applicationContext))

        /**
         * How many instances an attempt tries before it refuses: the first, and
         * a new one for each that answered code 26 or had been closed.
         *
         * **Each damaged read beside a capture poisons about one new instance**,
         * and in step with it: a reader that meets code 26 retries onto the same
         * fresh instance the capture's retry opens, and its own read poisons it.
         * Measured on emulator-5554, a capture beside damaged readers reading in
         * a transaction, as the ledger feed's first page does, 60 rounds each:
         * at 2 attempts, 40 of 60 captures refused beside one reader; at 4, none
         * beside one and 37 and 48 of 60 beside three and six; at 8, none beside
         * one, three or six, with some beside six needing all eight. A screen
         * resuming reads the month, the feed and the backup nudge's count --
         * three -- so eight holds twice that. Each attempt is one open, 4ms
         * (`OpenTest`).
         */
        const val ATTEMPTS = 8
    }
}
