package my.pinged.data

import android.database.sqlite.SQLiteException
import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
     * Idempotent. Called from [openHelperFactory] rather than left to the
     * caller, because anything that builds a SQLCipher open helper without it
     * -- MigrationTestHelper, for instance -- dies on the first native call
     * instead of at a readable place.
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
     * The database the app runs on: opened, configured, **and seeded**.
     *
     * Seeding lives here because this is the one place that knows how to build
     * this database, and because seeding *inside* it is what makes "a reader can
     * never observe an unseeded database" true by construction: `Graph` publishes
     * the handle only after this returns, so a `ParseWorker` racing first launch
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
     * once per process. It does make the open eager, where Room would defer it to
     * the first query -- on the listener's cold-start path that query is the
     * allow-list lookup microseconds later, so the open is moved, not added.
     * `OpenTest.rawKeyModeMakesOpeningFast` holds the whole of `build` plus a query
     * under 150ms.
     */
    fun build(context: Context): PingedDatabase {
        // `openHelperFactory` reads the key, so a missing one throws
        // [DatabaseKeyUnavailableException] from here and needs no translation.
        val database = Room.databaseBuilder(context, PingedDatabase::class.java, NAME)
            .openHelperFactory(openHelperFactory(context))
            .addCallback(configureConnection)
            .build()

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

    private const val TAG = "PingedData"
}
