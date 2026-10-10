package my.pinged.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.pinged.data.dao.CaptureDayDao
import my.pinged.data.dao.MerchantIdentityDao
import my.pinged.data.dao.MerchantRuleDao
import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * The process's one [PingedDatabase], and the DAOs reached through it.
 *
 * Here rather than in `:feature:capture`, where it used to live as half of that
 * module's `Graph`: nothing about memoizing a database handle is specific to
 * notification capture, and while it lived there `:feature:ledger` took a
 * compile dependency on the capture feature in order to reach the database.
 *
 * Manual, because a DI framework here would be scaffolding with one consumer --
 * the system constructs the listener, so it has to reach for its dependencies.
 */
object Databases {
    @Volatile private var db: PingedDatabase? = null

    /**
     * How many [whileDeleting] gates are up; [shared] refuses everyone while
     * it is above zero.
     *
     * **A count, not a flag, because gates nest.** A restore holds one across
     * its wipe and its import, and the wipe raises its own inside it; the
     * inner one lowering must leave the outer one up, or the import runs with
     * the listener, the worker and the resume probe free to open the file it
     * is writing. The other way out -- a wipe that skips gating when it finds
     * a gate already up -- fails the moment two gates are independent rather
     * than nested. `Transfers` rules that out for the settings screen's
     * operations; nothing here does.
     *
     * Written only under this object's monitor, which is what [shared]'s
     * second check relies on. `@Volatile` for its first, unlocked one: a
     * reader arriving one instruction early gets a database about to be
     * closed, which is what one arriving late gets too.
     */
    @Volatile private var gates = 0

    private val _generation = MutableStateFlow(0L)

    /**
     * How many times the database [shared] serves has been replaced,
     * announced once the replacement is in place -- by a delete or a restore,
     * which close it, and by [replacePoisoned] and [retire], which do not.
     *
     * **For any holder that keeps something bound to one instance** -- a DAO,
     * a `PagingSource`, a `Flow` from Room, or an answer read through one --
     * longer than a single call. [reset] closes the instance underneath it,
     * once no [leasing] lease is up, and spec 11.3's delete and 11.2's
     * restore both do that in production. A
     * closed instance never reopens and never hears about the one that
     * replaced it: measured on emulator-5554, a DAO of a closed instance
     * throws "Error code: 21, message: connection is closed" as
     * `android.database.SQLException` -- the superclass of `SQLiteException`,
     * so a `catch (SQLiteException)` does not see it -- its
     * `PagingSource` answers `LoadResult.Error`, and nothing Room invalidates
     * on the new instance reaches either. A holder that sees this change drops
     * what it kept and asks [shared] again.
     *
     * **Announced once the last gate lifts, not when [reset] closes the
     * handle inside one.** A holder that reopened on the close would meet
     * [DatabaseBeingDeletedException] and report an unreadable ledger over a
     * delete about to succeed. A restore resets three times -- its own gate,
     * the wipe's inside it, and its import's -- and announces once, after the
     * import, whether or not the import finished: the handle every holder is
     * bound to is closed either way.
     *
     * A value, not an event stream: a holder compares what it bound against
     * it, which is what makes an open that straddled a reset detectable.
     *
     * **Not for re-reading.** A screen that reads afresh on a change wants
     * [rewrites]: a connection replaced for damage holds the same rows, and a
     * read keyed on this that meets the damage again replaces it again.
     */
    val generation: StateFlow<Long> = _generation.asStateFlow()

    private val _rewrites = MutableStateFlow(0L)

    /**
     * How many times a delete or a restore has replaced what the database
     * holds -- [generation] less the replacements made for damage.
     *
     * **For a screen that re-reads when the ledger changes under it**, not
     * for a holder bound to an instance. A connection replaced because it met
     * damage holds the same rows, so nothing needs reading again; and a
     * re-read that meets the same damage replaces the connection again, which
     * on [generation] would re-read again, for as long as the page is
     * damaged. `MainActivity`'s banner probe and the ledger and allow-list
     * screens re-read on this.
     */
    val rewrites: StateFlow<Long> = _rewrites.asStateFlow()

    /**
     * The database, built on first use and kept until [reset] replaces it.
     *
     * [DatabaseFactory.build] is eager, so this throws
     * [DatabaseKeyUnavailableException] from the first call in spec 11.1's
     * device-transfer state. `CaptureStorage.guarded` in `:feature:capture` is
     * the one place that says what to do about that.
     */
    fun shared(context: Context): PingedDatabase {
        refuseWhileGated()
        return db ?: synchronized(this) {
            // Again, under the lock. A caller that passed the check above and
            // then waited here while [whileDeleting] raised its gate and
            // closed the handle would otherwise build a fresh instance on the
            // file about to be deleted -- one that survives the delete, and
            // that every holder then rebinds onto. `DeleteGateTest` holds the
            // monitor to make that interleaving happen.
            refuseWhileGated()
            db ?: DatabaseFactory.build(context.applicationContext).also { db = it }
        }
    }

    private fun refuseWhileGated() {
        if (gated) {
            throw DatabaseBeingDeletedException(
                "Pinged is deleting or replacing its database, so there is nowhere " +
                    "to put this right now. Capture resumes once that finishes.",
            )
        }
    }

    fun rawCaptureDao(context: Context): RawCaptureDao = shared(context).rawCaptureDao()
    fun txnDao(context: Context): TxnDao = shared(context).txnDao()
    fun categoryDao(context: Context): CategoryDao = shared(context).categoryDao()
    fun captureSourceDao(context: Context): CaptureSourceDao = shared(context).captureSourceDao()
    fun captureDayDao(context: Context): CaptureDayDao = shared(context).captureDayDao()
    fun merchantIdentityDao(context: Context): MerchantIdentityDao = shared(context).merchantIdentityDao()
    fun merchantRuleDao(context: Context): MerchantRuleDao = shared(context).merchantRuleDao()

    /**
     * Closes the database and forces the next call to rebuild, then announces
     * it through [generation] unless a gate is holding the announcement back.
     *
     * The close is the point. A handle dropped without closing leaves
     * SQLCipher's pool open on a file the caller is usually about to delete --
     * and the callers do exactly that. `FreshInstallTest` documents the
     * consequence: the next statement writes to a deleted inode and the
     * failure surfaces in some other test class entirely.
     *
     * Production, not only tests: [whileDeleting] calls this to close the
     * file before its deletion, and `Restore` calls it after its import so
     * the next open reads the file that import wrote.
     *
     * **What it guarantees.** The instance stops being served at once, as
     * [retire] would stop it, and [whileLive] and [requireLive] refuse it.
     * It is closed once no [leasing] lease is up, or once
     * [RESET_LEASE_WAIT_MILLIS] has passed, whichever is first; and this
     * returns only after that close, with every instance [retire] set aside
     * closed too. Closing under a lease's transaction leaves its write lock
     * taken for the life of the process (see [leasing]) -- measured on
     * emulator-5554 with a gate raised over a leased `insertAll` and no
     * unlink after it: code 21 in the insert, code 5 from the retry 2.5s
     * later, and every open of `pinged.db` after that refused.
     * `SharedInstanceUnderUseTest` pins the wait with a reset that unlinks
     * nothing.
     *
     * **Why it closes at all before returning**, rather than leaving the
     * close to the last lease as [retire] does: both callers unlink or
     * replace the file next, and an instance closing after that checkpoints
     * and removes the `-wal` by name -- the replacement's, by then.
     *
     * **Why five seconds is enough.** Under a gate [shared] refuses every
     * new caller, so a lease still up is one that began before the gate and
     * ends on its own, or one refused by the gate at once: a capture's
     * statements, a screen's read, or stage two, which stops at a gate
     * before its next capture (`ParsePass.run`). Measured on emulator-5554:
     * a capture's guarded insert held its lease 3.9ms at the longest of 200,
     * and stage two's longest single capture took 66ms over 6,000 parsed;
     * a delete raised 100 captures into a 2,000-capture run waited 1-2ms, in
     * each of eight. A whole 2,000-capture run holds its lease 6.6s, 11.3s
     * and 16.5s over ledgers of 2,000, 4,000 and 6,000 captures, past the
     * bound -- which is why the run stops at the gate. Settings' counts, the
     * one longer read, take `Transfers`' turn, as a delete does, so never
     * overlap one.
     *
     * **What would break it.** A lease that outlives a gate by the bound --
     * a long read added under a lease without stopping at [gated] -- is
     * closed under, and gets code 21 on its next statement, which
     * `CaptureStorage.guarded` answers by trying again and meeting the gate.
     * If that lease was mid-transaction the lock is left taken: harmless for
     * the two production callers, which unlink the file next so the lock is
     * on a dead inode, and `pinged.db` unwritable until the process dies for
     * a reset that does not. A reset from inside a lease waits for itself
     * and costs the whole bound. Waiting without a bound would turn either
     * into a hang.
     *
     * Waits holding neither this object's monitor nor [leaseLock], which the
     * condition gives up, so a lease holder that meets damage can [retire]
     * and one in [whileLive] can finish. The monitor is taken only to stop
     * serving the instance, which also waits out any [whileLive] or [aside]
     * block.
     */
    fun reset() {
        synchronized(this) {
            db?.let { current ->
                leaseLock.withLock {
                    current.retire()
                    retired += current
                }
            }
            db = null
            // A new file, or a closed one: [roomWorkFailed]'s backoff is
            // about the instance it replaced.
            lastRoomReplacementAt = 0L
            roomReplacementsInARow = 0
        }
        leaseLock.withLock {
            val deadline = SystemClock.elapsedRealtime() + RESET_LEASE_WAIT_MILLIS
            while (leases > 0) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    Log.w(TAG, "Closing the database under $leases leases after ${RESET_LEASE_WAIT_MILLIS}ms")
                    break
                }
                leasesDown.await(left, TimeUnit.MILLISECONDS)
            }
            closeRetiredLocked()
        }
        announce()
    }

    /** Whether a [whileDeleting] gate is up; `ParsePass` stops on it. */
    val gated: Boolean get() = gates > 0

    /**
     * Close the database, hold every other caller of [shared] off, and run
     * [block]; then, once no gate is left up, announce the replacement
     * through [generation].
     *
     * **Only [shared] is refused.** [block] opens what it needs through
     * [DatabaseFactory] directly -- `Restore`'s import does -- so a gate never
     * locks its own holder out of the work it is gating.
     *
     * `inline` so that [block] may suspend: `Restore` holds the gate across a
     * wipe and an import that do. Not a lock across those suspensions, only a
     * count, so which thread lowers it does not matter.
     *
     * Lowered in a `finally`: a gate left up by a throwing block is capture
     * dead for the life of the process, under a banner blaming a transfer that
     * never happened.
     */
    inline fun <T> whileDeleting(block: () -> T): T {
        raiseGate()
        try {
            reset()
            return block()
        } finally {
            lowerGate()
        }
    }

    /** [whileDeleting]'s, and public only because an `inline` body calls it. */
    @PublishedApi internal fun raiseGate() {
        synchronized(this) { gates++ }
    }

    /** [whileDeleting]'s: announces if this was the last gate up. */
    @PublishedApi internal fun lowerGate() {
        val lifted = synchronized(this) { --gates == 0 }
        if (lifted) announceRewrite()
    }

    /**
     * Runs [block] against [database] with no [reset] able to close it
     * underneath, and refuses a [database] already closed -- which every
     * instance [reset] has replaced is. Ruling R42.
     *
     * **For the paths that reach the file around Room's connection
     * manager.** A DAO on a closed instance throws "connection is closed";
     * `openHelper` and `runInTransaction` instead reopen the helper, outside
     * this object and outside its gate. Measured on emulator-5554:
     * `Integrity.check` answered `Ok` through a closed instance and left
     * `pinged.db` recreated where a wipe had just removed it.
     *
     * Under this object's monitor, so the close [reset] makes cannot land
     * between the check and the file access. **Nothing may call this, [reset]
     * or [whileDeleting] from inside a transaction on the current instance**:
     * this holds the monitor and then waits for the connection, so the other
     * order deadlocks. A transaction body reaching [shared] is safe -- while
     * an instance is current [shared] takes no lock.
     *
     * [reset] and [whileDeleting] wait for [block]: the 1,243ms an export of
     * 50,000 captures takes (`Transfers.export`), whose `ExportJson.write` is
     * the longest block here. The integrity check waits them out the same way
     * from [aside], which holds the same monitor. Neither waiter is ever the
     * main thread.
     *
     * **Nor can [retire]**, which takes the same monitor, so [database] is
     * neither set aside nor closed by a lease's release while [block] reads
     * through it; a caller replacing an instance for damage waits for
     * [block] as [reset] does.
     *
     * @throws DatabaseReplacedException without touching the file.
     */
    fun <T> whileLive(database: PingedDatabase, block: () -> T): T = synchronized(this) {
        requireLive(database)
        block()
    }

    /**
     * Runs [block] in one transaction on the current instance, so its
     * statements read one snapshot: a screen's read of several aggregates
     * that must agree (#81's Charts read, `Months`, the `Day` screen). Each
     * statement outside a transaction is its own snapshot, and stage two
     * commits between them.
     *
     * [whileLive] and `runInTransaction`, `Transfers.readable`'s pattern, with
     * the instance asked for inside the monitor: no [retire] or [reset] can
     * run there, so the instance is the current one and live, and nothing
     * is refused that a retry would get through.
     *
     * **It serialises every other caller** for its duration -- Room 2.8 hands
     * SQLCipher one connection (`ExportJson.writeDocument`) -- and holds this
     * object's monitor, so a capture, stage two and a replacement for damage
     * wait for [block]. Only short, bounded reads belong here; the caller
     * records its own measured duration. The same deadlock rule as
     * [whileLive]'s applies: never call this from inside a transaction.
     */
    fun <T> inOneTransaction(context: Context, block: (PingedDatabase) -> T): T = synchronized(this) {
        val database = shared(context)
        database.runInTransaction(Callable { block(database) })
    }

    /**
     * [whileLive]'s refusal alone, holding nothing. Enough for an instance
     * [shared] never served -- `Restore`'s own rebuild and its probe -- which
     * nothing but its caller can close or retire, so the answer cannot go
     * stale before the caller uses it.
     */
    fun requireLive(database: PingedDatabase) {
        if (database.closed || database.retired) {
            throw DatabaseReplacedException(
                "This handle on Pinged's database has been closed or set aside, and the " +
                    "database may have been replaced since. Nothing was read through it.",
            )
        }
    }

    /** [generation]'s writer outside a gate, silent while one is up. */
    private fun announce() {
        if (gates == 0) announceRewrite()
    }

    private fun announceRewrite() {
        _generation.update { it + 1 }
        _rewrites.update { it + 1 }
    }

    /**
     * Runs [block] on an instance of its own, opened for it and closed after,
     * never the one [shared] serves. For reads that walk the whole file.
     *
     * **Why.** Measured on emulator-5554, SQLCipher 4.18.0: a read that meets
     * a damaged page outside a transaction throws code 11, and its connection
     * then answers every later statement with code 26 -- capture's insert
     * included -- until the instance is closed. `PRAGMA integrity_check`
     * walks every page, so on the shared instance the weekly check turned
     * finding damage into losing every notification after it. On this one it
     * poisons nothing anyone else holds.
     *
     * **Never creates a key or a file.** The instance is keyed by
     * [DatabaseKey.existingRawKeyPassphrase], which throws rather than mints,
     * and a missing `pinged.db` is refused before Room is asked, so this can
     * neither race [shared]'s first open into a second key (the interleaving
     * `596e195` found losing a ledger) nor recreate a file a delete removed.
     *
     * **Under this object's monitor**, as [whileLive] is: no gate can rise and
     * no [reset] can close and unlink the file while [block] reads it, and
     * the instance's close, which checkpoints and removes the `-wal` by name
     * when it is the last connection, cannot land on a file a delete has
     * replaced. [shared] takes no lock while an instance is current, so
     * capture carries on beside it: measured on emulator-5554 at 50,000
     * captures, an insert on the shared instance made while the check ran
     * took 1.1-1.3ms, against 1.9-3.0ms with nothing running, and the check
     * 206-222ms. Only a first open of [shared] waits for [block]. The lock
     * order is [whileLive]'s, and the same rule holds: nothing may call this
     * from inside a transaction on the current instance.
     *
     * @throws DatabaseBeingDeletedException while a gate is up.
     * @throws DatabaseReplacedException if there is no file to open.
     * @throws DatabaseKeyUnavailableException if the key file is gone.
     * @throws DatabaseUnreadableException if the file will not open through
     *   it -- code 26 on a fresh connection, a damaged first page or a wrong
     *   key, which is not poison.
     */
    fun <T> aside(context: Context, block: (PingedDatabase) -> T): T = synchronized(this) {
        val own = openAsideLocked(context)
        try {
            block(own)
        } finally {
            runCatching { own.close() }
        }
    }

    /**
     * [aside]'s instance without its block, for a reader that has to open
     * more than one: salvage discards its connection after every damaged
     * read (`RowReader`) and opens the next through this. The same
     * refusals, under this object's monitor for the open alone.
     *
     * **The caller keeps every gate down for as long as it holds the
     * instance**, which [aside] does by holding the monitor and this cannot:
     * a gate raised over it would close and unlink the file under a
     * connection that then checkpoints into whatever replaced it. Salvage
     * holds `Transfers`' turn, and the only two callers of [whileDeleting]
     * -- restore and delete -- take that turn too. Close it with
     * [closeAside].
     *
     * **[key] is read once by the caller and handed to every open**, for a
     * reader that reopens thousands of times: unwrapping it costs a Keystore
     * load and an AES-GCM decrypt, measured at 0.60 ms an unwrap on
     * emulator-5554, which a damaged interior page at 50,000 captures paid
     * 7,410 times. sqlcipher-android 4.18.0 does not clear a passphrase it is
     * given -- read off its bytecode, `SQLiteOpenHelper` keeps the array as
     * `mPassword` to open each pooled connection, and nothing in the library
     * but `SQLiteProgram`'s bind arguments calls `Arrays.fill` -- so the one
     * array keys every instance, and the caller may wipe it only once the
     * last instance it keyed is closed. Read it with
     * [DatabaseKey.existingRawKeyPassphrase], which never mints.
     *
     * @throws DatabaseBeingDeletedException while a gate is up, and the rest
     *   of [aside]'s refusals.
     */
    fun openAside(context: Context, key: ByteArray): PingedDatabase =
        synchronized(this) { openAsideLocked(context, key) }

    /** Closes an [openAside] instance, under the monitor as [aside]'s own close is. */
    fun closeAside(own: PingedDatabase) {
        synchronized(this) { runCatching { own.close() } }
    }

    private fun openAsideLocked(context: Context, given: ByteArray? = null): PingedDatabase {
        refuseWhileGated()
        // The key before the file, so that each refusal is the only thing
        // between its own state and the harm it prevents: with both gone, a
        // key lookup that could mint would mint here whatever the file check
        // after it then said.
        val key = given ?: DatabaseKey.existingRawKeyPassphrase(context)
        if (!context.getDatabasePath(DatabaseFactory.NAME).exists()) {
            throw DatabaseReplacedException(
                "There is no database file to read: a delete or a restore has removed it " +
                    "and nothing has opened its replacement yet. Nothing was read.",
            )
        }
        return DatabaseFactory.buildAside(context.applicationContext, key)
    }

    /**
     * Whether [thrown] is what a connection that has met a damaged page
     * answers: code 11 from the read that met it, or code 26 from every
     * statement after. Either way the connection it came from is not to be
     * trusted again.
     *
     * **Only from a statement on an instance that had already opened.** Code
     * 26 from a *fresh* open -- a damaged first page, a wrong key -- never
     * reaches a caller as [SQLiteNotADatabaseException]: [DatabaseFactory]
     * translates it to [DatabaseUnreadableException], and that family is
     * not poison. The class itself, not its cause, for the same reason.
     */
    fun poisons(thrown: Throwable): Boolean =
        thrown is SQLiteDatabaseCorruptException || thrown is SQLiteNotADatabaseException

    /**
     * How many [leasing] blocks are running, in any thread; guarded by
     * [leaseLock], with [retired].
     */
    private var leases = 0

    /**
     * Instances [retire] has stopped serving and nothing has closed yet:
     * closed when [leases] next falls to zero, or by [reset].
     */
    private val retired = ArrayList<PingedDatabase>()

    /**
     * [leases]' and [retired]'s lock, never this object's monitor. **Taken
     * after the monitor, never before**: [reset] and [retire] hold the
     * monitor and take this, so nothing holding this may ask for the
     * monitor. Nothing does -- [closeRetiredLocked] closes instances nobody
     * is using, which asks nothing of this object.
     *
     * Not the monitor itself, because [whileLive] and [aside] hold that for
     * the length of an export or an integrity check -- 1,243ms and 644ms at
     * 50,000 captures -- and every capture takes a lease.
     */
    private val leaseLock = ReentrantLock()

    /** Signalled by [release] when [leases] falls to zero; [reset] waits on it. */
    private val leasesDown = leaseLock.newCondition()

    /**
     * Runs [block] with no instance [shared] has served closed underneath
     * it, except by a [reset] that has waited [RESET_LEASE_WAIT_MILLIS] for
     * it.
     *
     * **Why, and why this is the only thing that closes a retired
     * instance.** Room refuses every statement on a closed instance, the
     * `END` or `ROLLBACK` of a transaction already open on it included, so
     * closing one mid-transaction leaves SQLCipher's session holding the
     * connection with its write lock taken, for the life of the process.
     * Measured on emulator-5554 when an instance was closed as soon as it
     * met damage: a capture's `@Transaction` failed with code 21 between its
     * `BEGIN` and its `END`, and every open of `pinged.db` after that --
     * [shared]'s, and a fresh [DatabaseFactory.build] 10s later -- failed
     * with code 5, so nothing was stored until the process died. The same
     * close failed guarded readers with code 21, "the connection pool has
     * been closed" and "attempt to re-open an already-closed object": 14 of
     * 120 reads, six at a time on a poisoned instance.
     *
     * **Every statement on an instance [shared] served belongs inside one,
     * or inside [whileLive]**, which no [retire] can land under:
     * `CaptureStorage.guarded` takes one around its block and its retries,
     * the ledger feed around each page, and the settings screen around its
     * read; the export runs in [whileLive]. A statement outside both can meet
     * a retired instance closing -- code 21, or `IllegalStateException` --
     * and a transaction outside one is the lock above. Two need neither.
     * `DatabaseFactory.build`'s seed runs inside [shared]'s monitor, before
     * the instance is published, where nothing else can hold it and neither
     * [retire] nor [reset] can reach it. Room's own work on an instance --
     * the invalidation tracker -- is waited for by `RoomDatabase.close`
     * itself: `syncTriggers` and `notifyInvalidation` take its
     * `CloseBarrier`, and `close` spins until they let go (Room 2.8.4).
     *
     * **A count, across every instance**, not a lease per instance: a block
     * that asks [shared] twice can be handed two instances, and a count it
     * took before either covers both. The cost is that a retired instance
     * stays open while any lease is up. Leases are a capture's statements, a
     * screen's read or a stage-two run, so the count is at zero between
     * them. `inline` so [block] may suspend; not a lock, so which thread
     * releases it does not matter.
     *
     * Never waits for anything but [leaseLock], and the release closes
     * retired instances under it.
     */
    inline fun <T> leasing(block: () -> T): T {
        lease()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** [leasing]'s; public only because an `inline` body calls it. */
    @PublishedApi internal fun lease() {
        leaseLock.withLock { leases++ }
    }

    /** [leasing]'s: the last lease out closes what [retire] set aside. */
    @PublishedApi internal fun release() {
        leaseLock.withLock {
            check(leases > 0) { "A lease on Pinged's database was released twice" }
            if (--leases == 0) {
                closeRetiredLocked()
                leasesDown.signalAll()
            }
        }
    }

    private fun closeRetiredLocked() {
        retired.forEach { runCatching { it.close() } }
        retired.clear()
    }

    /**
     * Stops serving [poisoned] if it is still the instance [shared] serves,
     * **without closing it**, and says whether it did. Announces nothing:
     * the caller does, once, through [announceReplacement], after the retry
     * the next [shared] opens for.
     *
     * **Not closed**, because other threads may be using it -- see
     * [leasing], which closes it once none is. It answers them as it did
     * before, code 26 included, and [whileLive] refuses it.
     *
     * **Compare-and-replace.** A caller holds the instance it met code 26 on,
     * and another caller may already have replaced it; retiring whatever is
     * current instead would retire a healthy instance under someone who has
     * just opened it.
     *
     * Nothing here retries, and a reopened instance meets the same damage as
     * soon as the same page is read again; the caller bounds that.
     *
     * The lock order is [reset]'s: this object's monitor, then [leaseLock].
     * Not from inside a transaction on the current instance, as for
     * [whileLive], and it waits for a [whileLive] or [aside] block to finish.
     */
    fun retire(poisoned: PingedDatabase): Boolean = synchronized(this) {
        if (db !== poisoned) return false
        db = null
        leaseLock.withLock {
            poisoned.retire()
            retired += poisoned
            // A retire made outside every lease -- Room's own work, or a
            // caller holding none -- is closed by the next lease's release,
            // not here, where Room's own coroutine may be the caller.
        }
        true
    }

    /**
     * [retire] and its announcement, for a caller with nothing to retry.
     * The announcement is [announceReplacement]'s: [generation] and not
     * [rewrites].
     */
    fun replacePoisoned(poisoned: PingedDatabase) {
        if (retire(poisoned)) announceReplacement()
    }

    /**
     * [generation] moved for a replacement made for damage, and [rewrites]
     * left alone: the rows are the same, and a re-read keyed on [rewrites]
     * would meet the same damage and replace again, for as long as the page
     * is damaged.
     *
     * **Nothing while a gate is up.** A gate rising between the replacement
     * and this would otherwise move [generation] mid-delete, which that
     * value's KDoc says a holder must not see; the gate's own lowering
     * announces for it. Under the monitor, so no gate can rise between the
     * check and the move.
     */
    fun announceReplacement() {
        synchronized(this) { if (gates == 0) _generation.update { it + 1 } }
    }

    /**
     * What Room's own work on [instance] threw, which no caller of ours
     * awaits: the invalidation tracker's trigger sync, above all.
     *
     * **A sync that fails leaves [instance]'s tracker believing its
     * triggers are installed** -- read off room-runtime 2.8.4's bytecode:
     * `ObservedTableStates.onSync` clears `needsSync` and records each
     * table observed before it runs the sync, and on a failure resets only
     * `inProgressSync` -- so the ledger feed stops hearing about writes for
     * as long as that instance is served. So [instance] is replaced, and the
     * feed rebinds on [generation].
     *
     * **Not for damage.** A poisoned instance is replaced by the next
     * `CaptureStorage.guarded` caller that meets it, which bounds it by the
     * callers. Replaced from here it loops: the feed rebinds, reads its
     * damaged page onto the new instance, whose sync then fails in turn.
     *
     * **Backed off, for a failure that does not pass** -- a full disk -- on
     * which every instance's sync fails as soon as the feed rebinds onto it.
     * The first replacement is at once and the second
     * [ROOM_FAILURE_INTERVAL_MILLIS] after it; each after that waits twice
     * as long as the one before, up to [ROOM_FAILURE_MAX_INTERVAL_MILLIS],
     * so the loop that remains is one open and one reload of the feed's first
     * page every five minutes. A failure more than twice its interval after
     * the last replacement starts again at once, as does one after a
     * [reset]. A failure inside the interval replaces once it ends rather
     * than being dropped, which would leave the feed deaf for good.
     */
    internal fun roomWorkFailed(instance: PingedDatabase, thrown: Throwable) {
        if (poisons(thrown)) return
        val now = SystemClock.elapsedRealtime()
        val wait = synchronized(this) {
            if (db !== instance) return
            val interval = roomFailureInterval(roomReplacementsInARow)
            // With no replacement yet, the interval is 0 and `since` is the
            // whole uptime, so neither comparison holds.
            val since = now - lastRoomReplacementAt
            if (since < interval) {
                interval - since
            } else {
                roomReplacementsInARow = if (since < 2 * interval) roomReplacementsInARow + 1 else 1
                retire(instance)
                lastRoomReplacementAt = now
                0L
            }
        }
        if (wait == 0L) {
            announceReplacement()
        } else {
            roomFailureScope.launch {
                delay(wait)
                roomWorkFailed(instance, thrown)
            }
        }
    }

    /**
     * How long after the last of [replacements] replacements in a row the
     * next may be made: none before the first, then
     * [ROOM_FAILURE_INTERVAL_MILLIS], doubling to
     * [ROOM_FAILURE_MAX_INTERVAL_MILLIS].
     */
    internal fun roomFailureInterval(replacements: Int): Long =
        if (replacements <= 0) {
            0L
        } else {
            (ROOM_FAILURE_INTERVAL_MILLIS shl (replacements - 1).coerceAtMost(16))
                .coerceAtMost(ROOM_FAILURE_MAX_INTERVAL_MILLIS)
        }

    /**
     * When [roomWorkFailed] last replaced an instance, `0L` for none since
     * [reset]; under the monitor, and written only with
     * [roomReplacementsInARow], so `0L` here is always zero replacements.
     */
    private var lastRoomReplacementAt = 0L

    /** How many of [roomWorkFailed]'s replacements came in a row; under the monitor. */
    private var roomReplacementsInARow = 0

    /** [roomWorkFailed]'s deferred replacements. */
    private val roomFailureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [roomWorkFailed]'s first interval. A replacement costs an open, 4ms
     * (`OpenTest`), and one reload of the feed's first page; a failure
     * replaced as often as it recurs was measured, for damage, at six
     * replacements in two seconds.
     */
    const val ROOM_FAILURE_INTERVAL_MILLIS = 5_000L

    /** [roomWorkFailed]'s longest interval, reached at the eighth replacement in a row. */
    const val ROOM_FAILURE_MAX_INTERVAL_MILLIS = 300_000L

    /**
     * [reset]'s bound on its wait for [leasing] leases: 75 times the longest
     * lease a gate was measured to leave up (66ms, one stage-two capture;
     * see [reset]), and short enough that a reset from inside a lease, which
     * waits for itself, costs five seconds rather than a hang.
     */
    const val RESET_LEASE_WAIT_MILLIS = 5_000L

    private const val TAG = "Databases"
}
