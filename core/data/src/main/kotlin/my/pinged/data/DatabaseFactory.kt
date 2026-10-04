package my.pinged.data

import android.database.sqlite.SQLiteException
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    const val NAME = "pinged.db"

    /**
     * Two per-connection settings the shipped database cannot be correct without.
     *
     * ## Foreign keys, pool-wide
     *
     * Room's generated open delegate issues `PRAGMA foreign_keys = ON`, but that
     * pragma is per-connection and SQLCipher runs an AOSP-style
     * `SQLiteConnectionPool` behind one `SQLiteDatabase`. Room's `execSQL` is a
     * write, so it lands on the primary connection and nowhere else: measured on
     * emulator-5554, API 37, `PRAGMA foreign_keys` read back through the same
     * database returns **0**, because a read is served by a secondary connection
     * the pragma never touched.
     *
     * Writes do go through the primary, so RESTRICT was firing -- by accident of
     * routing rather than by construction.
     * `SQLiteDatabase.setForeignKeyConstraintsEnabled` is the pool-wide form: it
     * reconfigures the pool, so every connection it holds now or opens later
     * applies it. `MigrationTestHelper` builds its own database and does not get
     * this callback; it only creates schemas.
     *
     * ## `PRAGMA synchronous = FULL`
     *
     * Room turns WAL on by default and AOSP's SQLite pairs WAL with `NORMAL`,
     * which does not fsync at commit -- it fsyncs at checkpoint. That is durable
     * against process death and **not** against the machine stopping, so a battery
     * pull discards the un-checkpointed tail of the WAL: the captures the user just
     * watched arrive. This database is the only copy of the user's financial
     * history and there is no server to re-fetch from.
     *
     * The cost is bounded by how little this app writes -- spec 15 puts capture
     * volume at ~850 a month. `WritePathTest` measures and logs the penalty rather
     * than restating it here to go stale.
     *
     * **`synchronous` is per-connection and has no pool-wide setter**, unlike
     * `foreign_keys`: `SQLiteConnection.setSyncMode` is private and driven by a
     * framework resource. So this reaches the connection serving `onOpen`, and is
     * correct only because that is the primary and every WAL write goes through it.
     * Read connections stay at NORMAL, which is free, so `PRAGMA synchronous` reads
     * back as 1 through an ordinary query and 2 inside a transaction --
     * `WritePathTest` asserts the second, because the first measures a connection
     * nothing durable happens on.
     */
    private val configureConnection = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            // Order matters and is not interchangeable.
            // `setForeignKeyConstraintsEnabled` calls
            // `SQLiteConnectionPool.reconfigure`, and a reconfigure re-runs
            // each connection's `setWalModeFromConfiguration`, which resets
            // `synchronous` to the framework's WAL default. So the pragma has
            // to come second. Anything added later that reconfigures the pool
            // will silently revert it, which is what `WritePathTest` is for.
            db.setForeignKeyConstraintsEnabled(true)
            // `execSQL`, not `query`. Both work, but they are routed
            // differently: SQLCipher's pool gives `execSQL` primary-connection
            // affinity and a `query` read-only affinity, so a `query` can set
            // FULL on a secondary connection and leave the write connection at
            // NORMAL. Measured on emulator-5554: with the pragma issued
            // through `query`, `PRAGMA synchronous` read back inside a
            // transaction was 2 only when the pool had not yet opened a
            // secondary -- i.e. it worked by luck of timing.
            db.execSQL("PRAGMA synchronous = FULL")
        }
    }

    /**
     * Idempotent. Called wherever this object makes a SQLCipher open helper
     * rather than left to the caller, because anything that builds one
     * without it -- MigrationTestHelper, for instance -- dies on the first
     * native call instead of at a readable place.
     */
    private fun loadNativeLibrary() {
        System.loadLibrary("sqlcipher")
    }

    /**
     * The open-helper factory the app ships. MigrationTestHelper is given
     * this same factory; without it the migration tests would exercise an
     * unencrypted schema the app never ships (spec 13).
     */
    fun openHelperFactory(context: Context): SupportOpenHelperFactory {
        loadNativeLibrary()
        return SupportOpenHelperFactory(DatabaseKey.rawKeyPassphrase(context))
    }

    /**
     * The builder [build], [buildAside] and [buildAt] all finish with: same
     * [configureConnection] callback, differing in [name] and in which key
     * [factory] carries.
     *
     * Room 2.8 has no `databaseBuilder` overload taking a [File] -- only this
     * `String` one -- so [buildAt] passes an absolute path rather than a bare
     * name. That works because SQLCipher's `SQLiteOpenHelper.getDatabaseLocked`
     * resolves the name through `Context.getDatabasePath`, and *that* framework
     * method special-cases a name starting with `/`: it takes everything before
     * the last separator as the directory instead of the app's own databases
     * directory, so the path is honoured exactly rather than nested under it.
     */
    private fun builderAt(
        context: Context,
        name: String,
        factory: SupportOpenHelperFactory,
        onRoomFailure: (Throwable) -> Unit = {},
    ): RoomDatabase.Builder<PingedDatabase> =
        Room.databaseBuilder(context, PingedDatabase::class.java, name)
            .openHelperFactory(factory)
            .addCallback(configureConnection)
            .setQueryCoroutineContext(Dispatchers.IO + roomFailures(onRoomFailure))

    /**
     * What Room's own coroutines throw, logged and handed to [onRoomFailure]
     * instead of killing the app.
     *
     * **Room runs work on its connection that no caller of ours awaits**: the
     * invalidation tracker syncs its triggers when the feed's `PagingSource`
     * subscribes, in a coroutine on Room's scope. Room 2.8.4 builds that
     * scope from the context given here plus a
     * `SupervisorJob`, and with no context given, from its executor and no
     * handler at all. Measured on emulator-5554: with the ledger's month on a
     * damaged page, a trigger sync ran `BEGIN` on a connection the month's
     * read had just poisoned, threw code 26 out of
     * `TriggerBasedInvalidationTracker.syncTriggers`, and nothing caught it --
     * in the app, a process kill on opening the ledger.
     *
     * [build]'s instance hands the failure to `Databases.roomWorkFailed`,
     * which says what a swallowed sync costs and replaces the instance for
     * it; the others are nobody's to replace.
     *
     * [Dispatchers.IO] for the dispatcher Room requires the context to carry.
     * The feed's pages are the only other work on it: nothing else here is a
     * `suspend` DAO or a `Flow`.
     */
    private fun roomFailures(onRoomFailure: (Throwable) -> Unit) = CoroutineExceptionHandler { _, thrown ->
        Log.e(TAG, "Room's own work on the database failed", thrown)
        onRoomFailure(thrown)
    }

    /**
     * The database the app runs on: opened, configured, **and seeded**.
     *
     * Seeding lives here because this is the one place that knows how to build
     * this database, and because seeding *inside* it is what makes "a reader can
     * never observe an unseeded database" true by construction: [Databases]
     * publishes the handle only after this returns, so a `ParseWorker` racing first launch
     * blocks in [Databases.shared] and gets a seeded database or none. Without a
     * production caller the shipped APK left `category` empty and `ParseWorker`
     * retried for ever while ~350 tests stayed green, each seeding the table itself.
     *
     * ## `onCreate` versus a guarded call here
     *
     * `RoomDatabase.Callback.onCreate` fires exactly once, with no guard to get
     * wrong -- and **cannot repair a database that already exists**. Every device
     * that has run a build without seeding holds a `pinged.db` with an empty
     * `category` table, and the only `onCreate`-shaped repair is a version bump
     * with a migration, which seeding rows must not force on `schemas/1.json`.
     *
     * The DAOs are also unusable inside it: mid-creation, `onCreate` gets a raw
     * [SupportSQLiteDatabase], so the fourteen rows and their column mapping would
     * live in a second place `Seed.categories()` cannot keep in step.
     *
     * ## What the guard costs
     *
     * One `SELECT COUNT(*)` over a fourteen-row table per `build`, and `build` runs
     * once per process, plus once after each delete or restore replaces the
     * database. It does make the open eager, where Room would defer it to
     * the first query -- on the listener's cold-start path that query is the
     * allow-list lookup microseconds later, so the open is moved, not added.
     * `OpenTest.rawKeyModeMakesOpeningFast` holds the whole of `build` plus a query
     * under 150ms.
     */
    fun build(context: Context): PingedDatabase {
        // `openHelperFactory` reads the key, so a missing one throws
        // [DatabaseKeyUnavailableException] from here and needs no translation.
        // Room's scope is built with the instance, and a failure in it has to
        // name the instance, so the handler reads it back from here.
        var built: PingedDatabase? = null
        val database = builderAt(context, NAME, openHelperFactory(context)) { thrown ->
            built?.let { Databases.roomWorkFailed(it, thrown) }
        }.build().also { built = it }

        // Room's `build()` opens nothing, so the seed below is the first moment the
        // file is actually read. A file that will not open through a present key
        // arrives as `SQLiteNotADatabaseException`, which extends the framework's
        // `SQLiteException` -- as do the full-disk and IO cases. All three mean the
        // same thing to a caller, and uncaught they reach the listener's handler while
        // the health report still says capture is fine.
        //
        // The close matters: `FreshInstallTest` documents what leaving a half-open
        // SQLCipher pool on a file the caller is about to delete costs.
        return try {
            val seeded = database.categoryDao().seedIfEmpty()
            if (seeded > 0) Log.i(TAG, "Seeded $seeded categories into a new database")
            database
        } catch (unreadable: SQLiteException) {
            runCatching { database.close() }
            throw DatabaseUnreadableException(
                "The database exists and cannot be read through its key. That is a " +
                    "corrupt file, a full disk or a storage error; the key itself is " +
                    "present.",
                unreadable,
            )
        }
    }

    /**
     * `pinged.db` opened as [build] opens it, for `Databases.aside` alone:
     * under [key], which that reads with [DatabaseKey.existingRawKeyPassphrase]
     * and never mints, and **not seeded** -- a second instance writes nothing
     * its caller did not ask for.
     *
     * Opened here rather than at its caller's first statement, by one read of
     * the fourteen-row `category` table, so that a file that will not open --
     * code 26 on a fresh connection, a damaged first page or a wrong key --
     * arrives as [DatabaseUnreadableException], as it does from [build], and
     * not as the poison a statement on an open connection throws.
     */
    fun buildAside(context: Context, key: ByteArray): PingedDatabase {
        loadNativeLibrary()
        val database = builderAt(context, NAME, SupportOpenHelperFactory(key)).build()
        return try {
            database.categoryDao().countAll()
            database
        } catch (unreadable: SQLiteException) {
            runCatching { database.close() }
            throw DatabaseUnreadableException(
                "The database exists and a second connection to it cannot read it " +
                    "through its key: a corrupt file, a full disk or a storage error.",
                unreadable,
            )
        }
    }

    /**
     * The same pragma-configured schema [build] opens -- encrypted, but under
     * a key generated for this call and never persisted -- at a caller-chosen
     * [file] instead of the app's own `pinged.db`.
     *
     * Its only caller is `Restore`'s probe, in `:feature:ledger`: importing an
     * untrusted document into a throwaway database at [file] is the only
     * honest way to know whether it restores, because `ImportJson`'s guards --
     * the format version, the six sections, `verifyReferences` -- need a real
     * schema to run against. Deliberately unseeded, unlike [build]: the probe
     * never keeps its `category` table past the import, which replaces it
     * wholesale (`ImportJson`'s `CATEGORY_SECTION.clearFirst`), so seeding it
     * first would only be discarded.
     *
     * ## The key is random, not the caller's
     *
     * [file] is a throwaway path the caller deletes before returning, so the
     * key that opens it needs no more durability than this call -- and it must
     * have none, for two reasons that both end in the same wrong place.
     *
     * Passing this call's own [openHelperFactory] would read the app's real
     * key via [DatabaseKey.rawKeyPassphrase] before the probe schema is even
     * created -- Kotlin evaluates a default-less argument eagerly -- and that
     * throws [DatabaseKeyUnavailableException] in exactly spec 11.1's
     * key-gone state, which is the headline reason a restore runs at all. A
     * probe that cannot open in the state its caller exists for is worse than
     * no probe.
     *
     * The seemingly obvious fix -- passing [file]'s own name through to
     * [DatabaseKey.rawKeyPassphrase] so it asks about the probe's path instead
     * -- is worse than the bug it fixes. With the app's key gone, the probe's
     * path does not exist either, so that guard falls through to minting and
     * *persisting* a new key at the app's own `noBackupFilesDir/db.key` --
     * which is precisely what [DatabaseKey.rawKeyPassphrase]'s own KDoc warns
     * makes an intact database "permanently unopenable and indistinguishable
     * from corruption." Validating a file would destroy the user's real
     * ledger to do it.
     *
     * [DatabaseKey.randomRawKeyPassphrase] touches neither path: fresh bytes,
     * used once, in memory only.
     */
    fun buildAt(context: Context, file: File): PingedDatabase {
        loadNativeLibrary()
        val factory = SupportOpenHelperFactory(DatabaseKey.randomRawKeyPassphrase())
        return builderAt(context, file.absolutePath, factory).build()
    }

    private const val TAG = "PingedData"
}
