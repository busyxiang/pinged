package my.pinged.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException

/**
 * What reaches the whole of `pinged.db` rather than one statement's worth: the
 * export, the integrity check, salvage, and the delete and the restore that
 * replace the file (#302, decision 9). [DatabaseAccess] is for a block that
 * reads or writes rows; this is for a caller that has to hold the file still,
 * or take it away, and each entry keeps the monitor, the [Databases.Holder] and
 * the refusals of the [Databases] function it stands on, unchanged.
 *
 * **Nothing here retries.** A block that meets damage throws it: an export on
 * the shared instance rethrows code 11 or 26 to `Transfers`, which replaces the
 * instance through a [DatabaseAccess.damageScope] it opens, and the check and
 * salvage read instances of their own, which poison nothing anyone else holds.
 * **The caller owns the damage scope**: nothing here opens, joins or exits one,
 * and [readable] retires through the one it is handed. These entries are not
 * [DatabaseAccess]'s, and none runs under its lease.
 *
 * **Nor do they take `Transfers`' turn.** Serialising an export, a check, a
 * salvage, a delete and a restore against each other is the caller's: [salvage]
 * relies on it (it keeps no gate down across its instances), and so does
 * [replaceAll]'s nesting.
 *
 * **Never from inside a transaction on the current instance**, for every entry
 * but [replaceAll]'s block: each takes [Databases]' monitor and then waits for
 * the connection, and the other order deadlocks (`Databases.whileLive`).
 * [DatabaseAccess.read] throws at that; these do not, because their callers are
 * the transfer jobs, which hold no transaction.
 */
object WholeFile {
    /**
     * The instance [Databases.shared] serves, once a transaction on it has
     * read, for the caller that goes on to [export] it: the one place this
     * module hands the shared instance out, and only after [probe] has found
     * it live. **Takes no lease**, as [Databases.shared] does not, and cannot
     * take one that lasts: the export holds the instance for a document's
     * worth of IO, 1,243ms at 50,000 captures, and `Databases.reset` waits
     * for every lease up to its bound. What the caller does with the instance
     * is run under [export]'s monitor, which refuses one a replacement has
     * since closed or set aside, and nothing else is to be run on it.
     *
     * Each probe is a transaction, because the export's read is one and runs
     * on the connection transactions take: a plain read can be served by
     * another connection of the pool and answer for the wrong one. A probe
     * that finds the instance replaced takes the next. A probe that meets
     * code 26, a connection another read poisoned, retires it through
     * [damage] and takes the next, up to [probes] in all; one that meets code
     * 11 is its own read on the export's path, which a new connection meets
     * again, so it is thrown and the instance stays served -- the caller
     * decides what to record. After [probes] the last instance taken is
     * returned unprobed.
     *
     * @param damage the caller's scope: it records and announces what this
     *   replaced, and owns the order (#302, decision 4).
     * @throws SQLiteException from a probe for any damage but code 26.
     * @throws DatabaseBeingDeletedException while a gate is up.
     * @throws DatabaseUnavailableException for the key gone or a file that will not open.
     */
    @OptIn(RawDatabaseAccess::class)
    fun readable(context: Context, damage: DatabaseAccess.DamageScope, probes: Int): PingedDatabase {
        var db = Databases.shared(context)
        repeat(probes) {
            try {
                probe(db) { db.runInTransaction<Int> { db.categoryDao().countAll() } }
                return db
            } catch (replaced: DatabaseReplacedException) {
                db = Databases.shared(context)
            } catch (thrown: SQLiteException) {
                if (!Databases.poisons(thrown) || thrown is SQLiteDatabaseCorruptException) throw thrown
                damage.replace(db)
                db = Databases.shared(context)
            }
        }
        return db
    }

    /**
     * [block] with the instance [db] known live and no replacement able to
     * close it under [block], and [Databases.holder] showing
     * [Databases.Holder.Export] from before the monitor is asked for until
     * after it is let go. `ExportJson.write` is the longest block here, 1,243ms
     * at 50,000 captures (`Transfers.export`); `ExportCsv.write` holds it the
     * same way.
     *
     * @throws DatabaseReplacedException, without running [block], if [db] has
     *   been closed or set aside.
     */
    fun <T> export(db: PingedDatabase, block: () -> T): T =
        Databases.whileLive(db, Databases.Holder.Export, block)

    /**
     * [export]'s refusal and monitor with nothing shown on [Databases.holder]:
     * the short read `Transfers` makes of an instance before it opens the
     * document, which a screen's read is not to be seen waiting behind.
     *
     * @throws DatabaseReplacedException, without running [block], as [export].
     */
    fun <T> probe(db: PingedDatabase, block: () -> T): T = Databases.whileLive(db, null, block)

    /**
     * [block] on an instance of its own, opened for it and closed after, never
     * the one [Databases.shared] serves, with [Databases.holder] showing
     * [Databases.Holder.IntegrityCheck]. The reasons for the separate
     * instance, the monitor and each refusal are [Databases.aside]'s, and
     * [Integrity.check] is the one caller.
     *
     * @throws DatabaseBeingDeletedException while a gate is up.
     * @throws DatabaseReplacedException if there is no file to open.
     * @throws DatabaseKeyUnavailableException if the key file is gone.
     * @throws DatabaseUnreadableException if the file will not open through its key.
     */
    fun <T> check(context: Context, block: (PingedDatabase) -> T): T =
        Databases.aside(context, Databases.Holder.IntegrityCheck, block)

    /**
     * The instances a salvage opens and closes, one at a time. A reader that
     * walks a damaged file discards its connection after every unreadable row
     * and opens the next, so [salvage] hands out an opener rather than one
     * instance.
     */
    interface Salvage {
        /**
         * A new instance of its own, keyed as [Databases.aside]'s is, for the
         * monitor to be held for the open alone.
         *
         * @throws DatabaseBeingDeletedException while a gate is up, and the
         *   rest of [check]'s refusals.
         */
        fun open(): PingedDatabase

        /** Closes an instance [open] handed out, under the monitor as [check]'s own close is. */
        fun close(own: PingedDatabase)
    }

    /**
     * [block] with the means to open and close instances beside the one
     * [Databases.shared] serves, for a read that walks the whole file
     * (`SalvageJson`).
     *
     * **The key is read once and wiped once**, here and not by the caller:
     * unwrapping it costs a Keystore load and an AES-GCM decrypt, 0.60ms on
     * emulator-5554, which a damaged interior page at 50,000 captures paid 7,410
     * times when every reopen did it. sqlcipher-android 4.18.0 does not clear a
     * passphrase it is given and keeps the array to open each pooled
     * connection, so the one array keys every instance and is wiped only once
     * [block] has returned, which is after the last instance it keyed is
     * closed -- a reader that closes what it opened, as `RowReader` does.
     *
     * **Holds no gate down while [block] runs**: only `Transfers`' turn keeps a
     * delete or a restore from raising one over an open instance, which would
     * close and unlink the file under a connection that then checkpoints into
     * whatever replaced it (`Databases.openAside`).
     *
     * @throws DatabaseKeyUnavailableException if the key file is gone, before
     *   anything is opened.
     */
    fun <T> salvage(context: Context, block: (Salvage) -> T): T {
        val key = DatabaseKey.existingRawKeyPassphrase(context)
        try {
            return block(
                object : Salvage {
                    override fun open(): PingedDatabase = Databases.openAside(context, key)
                    override fun close(own: PingedDatabase) = Databases.closeAside(own)
                },
            )
        } finally {
            key.fill(0)
        }
    }

    /**
     * Closes the file, holds every other caller of [Databases.shared] off, and
     * runs [block]; then, once no gate is left up, announces the replacement
     * through [Databases.generation] and [Databases.rewrites]. A delete holds it
     * across the removal of the file and its key, and a restore across a wipe
     * and an import, so the listener, stage two and the resume probe are
     * refused for the whole of either and record that they were.
     *
     * **Gates nest**: the wipe a restore runs raises its own inside the
     * restore's, and lowering the inner one leaves the outer up
     * (`Databases.whileDeleting`). Lowered in a `finally`, so a throwing
     * [block] does not leave capture dead for the life of the process.
     *
     * [block] is not refused by its own gate: it opens what it needs through
     * [DatabaseFactory] directly. It may suspend, and it may call [dropInstance].
     */
    suspend fun <T> replaceAll(block: suspend () -> T): T = Databases.whileDeleting { block() }

    /**
     * Closes the instance [Databases.shared] serves, once no lease is up or
     * after [Databases.RESET_LEASE_WAIT_MILLIS], so that the next access
     * builds on the file as it now is, and announces that through
     * [Databases.generation] unless a gate holds the announcement back.
     *
     * For what has written the file around the shared instance: the end of a
     * restore's import, inside [replaceAll]. Outside it, for a test's fixtures
     * to start clean. The wait, its bound and what a lease that outlasts it
     * costs are [Databases.reset]'s.
     */
    @OptIn(RawDatabaseAccess::class)
    fun dropInstance() = Databases.reset()
}
