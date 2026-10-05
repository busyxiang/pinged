package my.pinged.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PingedDatabase::class.java,
        emptyList(),
        // Without this the helper opens a PLAIN database and the migration
        // tests exercise a schema the app does not ship (spec 13). And
        // without the foreign-key wrapper it opens an unconstrained one --
        // see [foreignKeyEnforcing].
        foreignKeyEnforcing(
            DatabaseFactory.openHelperFactory(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
        ),
    )

    private companion object {
        const val MIGRATED = "migration-1-2.db"
    }

    @Test fun schemaVersionOneIsCreatable() {
        helper.createDatabase("migration-test.db", 1).close()
    }

    @Test fun theHarnessDatabaseIsAlsoEncrypted() {
        helper.createDatabase("migration-test-encrypted.db", 1).close()
        val file = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("migration-test-encrypted.db")
        // If this is the plaintext magic, the harness is validating a schema
        // the shipped app never creates, which is the whole reason the
        // SQLCipher factory is passed above. The assertion is shared with
        // EncryptionTest: the two copies had already diverged, and this was
        // the weaker one.
        file.assertNotPlaintextSqlite("The migration harness database")
    }

    /**
     * Spec 6.4's v1 -> v2, under the SQLCipher factory: every v1 row survives,
     * the two new tables are there and empty, and the result validates against
     * `2.json`, which `runMigrationsAndValidate` checks table by table.
     *
     * The rows are written as raw SQL into the harness's v1, the only shape a
     * real user's v1 file has.
     */
    @Test fun versionOneMigratesToTwoKeepingEveryRow() {
        helper.createDatabase(MIGRATED, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO category (id, name, icon_key, sort_order, is_protected) " +
                    "VALUES (1, 'Uncategorized', 'circle-dashed', 0, 1)",
            )
            v1.execSQL(
                "INSERT INTO txn (id, raw_capture_id, amount_sen, currency, direction, occurred_at, " +
                    "local_date, merchant_raw, merchant_display, merchant_key, category_id, " +
                    "source_package, source_label, confidence, state, pending_reason, is_excluded, " +
                    "exclusion_reason, note, user_edited, created_at, updated_at) VALUES " +
                    "(7, NULL, 1250, 'MYR', 'EXPENSE', 1788739200000, 20260907, " +
                    "'YUENKEEHOMETOWNCAFE', 'Yuenkeehometowncafe', 'YUENKEEHOMETOWNCAFE', 1, " +
                    "NULL, NULL, 'HIGH', 'COMMITTED', NULL, 0, NULL, NULL, 0, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(MIGRATED, 2, true).use { v2 ->
            v2.query("SELECT id, merchant_key, amount_sen FROM txn").use { c ->
                assertTrue("the v1 transaction is gone", c.moveToFirst())
                assertEquals(7L, c.getLong(0))
                assertEquals("YUENKEEHOMETOWNCAFE", c.getString(1))
                assertEquals(1250L, c.getLong(2))
                assertEquals(1, c.count)
            }
            assertEquals(1L, v2.count("category"))
            assertEquals(0L, v2.count("merchant_alias"))
            assertEquals(0L, v2.count("merchant_name"))
            assertEquals(2L, v2.pragma("user_version"))
        }
    }

    // ---- harness/app DDL parity ----------------------------------------

    /**
     * **The harness must build the database the app builds, and Room cannot check
     * that for us.**
     *
     * `MigrationTestHelper.createDatabase` does not construct [PingedDatabase].
     * Verified against the room-testing 2.8.4 bytecode: all three helper flavours
     * route `createDatabase(version)` through `loadSchema(version).database` into
     * `CreateOpenDelegate(bundle).createAllTables`, whose body is
     * `databaseBundle.buildCreateQueries().forEach(::execSQL)`. The exported JSON is
     * the only source of the harness's DDL. (The driver-based constructor's
     * `databaseFactory` is not an escape hatch -- `databaseInstance` is read only by
     * `runMigrationsAndValidate`.)
     *
     * So whenever the JSON cannot represent something the generated
     * `createAllTables` emits, the harness's v1 differs from every real user's v1
     * -- and every mechanism that normally catches a schema change is blind at
     * once: the identity hash is computed from the same bundle the JSON is written
     * from, and `onValidateSchema` compares `PRAGMA table_info`, which does not
     * report the missing property either.
     *
     * `sqlite_master.sql` holds each statement as executed, which is the one place
     * both databases are observable on equal terms, so the two are compared there:
     * **every schema object, tables and indices alike, as whole strings** --
     * 25 at schema v2, logged on every run. Both sides are built at
     * [PingedDatabase.VERSION], since the app only ever creates the current one.
     *
     * **Nothing is normalised and nothing is excluded** -- not whitespace, not `IF
     * NOT EXISTS`, not ordering, and no table is skipped. That is a measurement
     * rather than restraint: run strict on emulator-5554 the two produced identical
     * object sets and exactly one differing string, since both sides are generated
     * by the same Room compiler from the same entities.
     *
     * That one difference is also gone. `merchant_rule.pattern` carries `COLLATE
     * NOCASE`, Room's generated DDL had it and `schemas/1.json` did not, so the
     * harness built a column without it. The committed JSON had been written by an
     * older Room that did not export collation in `createSql` and had gone stale,
     * because the KSP task is normally up to date and never rewrites the file. Room
     * 2.8.4 exports it, and the identity hash did not move.
     */
    @Test fun theHarnessCreatesTheSameDdlAsTheApp() {
        val harness = harnessSchema()
        val shipped = shippedSchema()

        val differences = (harness.keys + shipped.keys).sorted().mapNotNull { name ->
            val h = harness[name]
            val s = shipped[name]
            if (h == s) null else "$name\n  harness: $h\n  shipped: $s"
        }
        differences.forEach { android.util.Log.i(OpenTest.REPORT_TAG, "DDL divergence: $it") }
        assertTrue(
            "The database MigrationTestHelper builds is not the database the app " +
                "builds, so a migration verified against the harness would be " +
                "verified against a schema no user has. Neither the identity hash " +
                "nor Room's schema validation can see this:\n" +
                differences.joinToString("\n"),
            differences.isEmpty(),
        )
    }

    /**
     * **The other harness/app divergence, and it is closed.**
     *
     * Pragmas are per-connection state and leave no trace in `sqlite_master`, so
     * [theHarnessCreatesTheSameDdlAsTheApp] cannot see this one.
     * `DatabaseFactory.build` installs a `RoomDatabase.Callback` that turns foreign
     * keys on pool-wide; `MigrationTestHelper` is handed only the open-helper
     * *factory*, not that callback, so the harness read `PRAGMA foreign_keys` as 0
     * while the app read 1. A v1.1 migration that orphans a `txn.category_id` would
     * have passed in the harness and failed RESTRICT on a real device.
     */
    @Test fun theHarnessEnforcesForeignKeysExactlyLikeTheApp() {
        val harness = helper.createDatabase("fk-pragma.db", 1)
            .use { it.pragma("foreign_keys") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DatabaseFactory.NAME)
        val shipped = DatabaseFactory.build(context).useDb {
            it.openHelper.readableDatabase.pragma("foreign_keys")
        }
        android.util.Log.i(
            OpenTest.REPORT_TAG,
            "PRAGMA foreign_keys -- harness: $harness, shipped: $shipped",
        )
        assertEquals(
            "The app stopped enforcing foreign keys pool-wide, which DatabaseFactory's " +
                "callback exists to guarantee",
            1L,
            shipped,
        )
        assertEquals(
            "The harness and the shipped database must agree about foreign keys, or a " +
                "migration that orphans a category reference passes here and fails on a " +
                "phone. This is the assertion the divergence note asked for.",
            shipped,
            harness,
        )
    }

    // ---- reading the two databases -------------------------------------

    private fun harnessSchema(): Map<String, String> =
        helper.createDatabase("ddl-parity-harness.db", PingedDatabase.VERSION).use { schemaObjects(it) }

    private fun shippedSchema(): Map<String, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DatabaseFactory.NAME)
        return DatabaseFactory.build(context).useDb {
            schemaObjects(it.openHelper.readableDatabase)
        }
    }

    private fun SupportSQLiteDatabase.count(table: String): Long =
        query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private fun schemaObjects(db: SupportSQLiteDatabase): Map<String, String> {
        val out = sortedMapOf<String, String>()
        db.query("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                out["${c.getString(0)} ${c.getString(1)}"] = c.getString(2)
            }
        }
        // The set itself is the evidence that nothing is being skipped, so it
        // is logged rather than only asserted on.
        android.util.Log.i(OpenTest.REPORT_TAG, "schema objects (${out.size}): ${out.keys}")
        return out
    }

}

/**
 * Wrap an open-helper factory so every connection it opens enforces foreign
 * keys, the way the shipped database does.
 *
 * This closes the fourth and last of this module's "constraint enforced only
 * incidentally" findings. The app turns foreign keys on from a
 * `RoomDatabase.Callback.onOpen`, which Room invokes -- and
 * `MigrationTestHelper` does not use Room to open the database, so that
 * callback never ran and `PRAGMA foreign_keys` read 0 in the harness against
 * 1 on a device. Pragmas leave no trace in `sqlite_master`, so the DDL parity
 * test could not see the difference either.
 *
 * The consequence was specific: the first real v1 -> v2 migration would have
 * been verified against a database where `ON DELETE RESTRICT` on
 * `txn.category_id` and `merchant_rule.category_id` does not fire. A
 * migration that orphans a category reference would pass here and fail on a
 * user's phone, which is the one failure the harness exists to prevent.
 *
 * The chaining is the whole of it: `MigrationTestHelper` accepts a factory
 * and nothing else, so the only way in is to rebuild the `Configuration` with
 * a callback that delegates every method and adds the pragma to `onOpen`.
 */
private fun foreignKeyEnforcing(
    delegate: SupportSQLiteOpenHelper.Factory,
): SupportSQLiteOpenHelper.Factory = SupportSQLiteOpenHelper.Factory { configuration ->
    val original = configuration.callback
    val chained = object : SupportSQLiteOpenHelper.Callback(original.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            original.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            original.onDowngrade(db, oldVersion, newVersion)
        override fun onCorruption(db: SupportSQLiteDatabase) = original.onCorruption(db)
        override fun onOpen(db: SupportSQLiteDatabase) {
            original.onOpen(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
    delegate.create(
        SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(chained)
            .build()
    )
}
