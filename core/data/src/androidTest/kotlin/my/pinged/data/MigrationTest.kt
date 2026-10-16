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
        const val MIGRATED_TO_3 = "migration-2-3.db"
        const val MIGRATED_1_TO_3 = "migration-1-3.db"
        const val MIGRATED_3_TO_4 = "migration-3-4.db"
        const val MIGRATED_2_TO_4 = "migration-2-4.db"
        const val MIGRATED_1_TO_4 = "migration-1-4.db"
        const val MIGRATED_4_TO_5 = "migration-4-5.db"
        const val MIGRATED_3_TO_5 = "migration-3-5.db"
        const val MIGRATED_2_TO_5 = "migration-2-5.db"
        const val MIGRATED_1_TO_5 = "migration-1-5.db"

        /** One unread capture, in the columns every version from v1 shares. */
        const val CAPTURE_SQL =
            "INSERT INTO raw_capture (id, source_package, posted_at, when_millis, captured_at, sbn_key, " +
                "notif_id, notif_tag, user_handle, channel_id, flags, arrival, title, text, big_text, " +
                "sub_text, extras_json, content_hash, parse_status, rejected_by_rule_id, matched_rule_id, " +
                "reject_collision_id, pack_version, duplicate_of_id, user_reject_rule_id) VALUES " +
                "(3, 'com.maybank2u.life', 1, NULL, 1, 'k', 1, NULL, 0, NULL, 0, 'POSTED', 'Maybank', " +
                "'Your FD of RM5,000.00 has matured.', NULL, NULL, NULL, 'h', 'UNMATCHED', NULL, NULL, " +
                "NULL, 1, NULL, NULL)"
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

    /**
     * Spec 15.2's v2 -> v3: a transaction written before the update is found
     * by search after it. Room creates the external-content FTS table empty
     * and its triggers index only later writes, so this is the case
     * `PingedDatabase.IndexExistingRows` exists for; without its `rebuild`
     * the migration validates and the row is unfindable.
     */
    @Test fun versionTwoMigratesToThreeWithEveryRowSearchable() {
        helper.createDatabase(MIGRATED_TO_3, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO category (id, name, icon_key, sort_order, is_protected) " +
                    "VALUES (1, 'Uncategorized', 'circle-dashed', 0, 1)",
            )
            v2.execSQL(
                "INSERT INTO txn (id, raw_capture_id, amount_sen, currency, direction, occurred_at, " +
                    "local_date, merchant_raw, merchant_display, merchant_key, category_id, " +
                    "source_package, source_label, confidence, state, pending_reason, is_excluded, " +
                    "exclusion_reason, note, user_edited, created_at, updated_at) VALUES " +
                    "(7, NULL, 1250, 'MYR', 'EXPENSE', 1788739200000, 20260907, " +
                    "'YUENKEEHOMETOWNCAFE', 'Yuen Kee Hometown Cafe', 'YUENKEEHOMETOWNCAFE', 1, " +
                    "NULL, NULL, 'HIGH', 'COMMITTED', NULL, 0, NULL, 'kopi with Ah Meng', 0, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(MIGRATED_TO_3, 3, true).use { v3 ->
            fun match(words: String): List<String?> =
                v3.column("SELECT rowid FROM txn_fts WHERE txn_fts MATCH '$words'")
            assertEquals("the shown name", listOf("7"), match("hometown*"))
            assertEquals("the raw name", listOf("7"), match("yuenkee*"))
            assertEquals("the note", listOf("7"), match("meng*"))
            assertEquals(1L, v3.count("txn"))
            assertEquals(3L, v3.pragma("user_version"))
        }
    }

    /**
     * The other released version, v1, straight to v3: both steps in turn,
     * so a v1 phone that skipped v2 still ends with every row searchable.
     */
    @Test fun versionOneMigratesToThreeWithEveryRowSearchable() {
        helper.createDatabase(MIGRATED_1_TO_3, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO category (id, name, icon_key, sort_order, is_protected) " +
                    "VALUES (1, 'Uncategorized', 'circle-dashed', 0, 1)",
            )
            v1.execSQL(
                "INSERT INTO txn (id, raw_capture_id, amount_sen, currency, direction, occurred_at, " +
                    "local_date, merchant_raw, merchant_display, merchant_key, category_id, " +
                    "source_package, source_label, confidence, state, pending_reason, is_excluded, " +
                    "exclusion_reason, note, user_edited, created_at, updated_at) VALUES " +
                    "(9, NULL, 800, 'MYR', 'EXPENSE', 1788739200000, 20260907, " +
                    "'NASIKANDARPELITA', 'Nasi Kandar Pelita', 'NASIKANDARPELITA', 1, " +
                    "NULL, NULL, 'HIGH', 'COMMITTED', NULL, 0, NULL, NULL, 0, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(MIGRATED_1_TO_3, 3, true).use { v3 ->
            assertEquals(listOf("9"), v3.column("SELECT rowid FROM txn_fts WHERE txn_fts MATCH 'pelita*'"))
            assertEquals(0L, v3.count("merchant_alias"))
            assertEquals(3L, v3.pragma("user_version"))
        }
    }

    /**
     * v3 -> v4 (#155): `seed_name` is added and given to the seeded rows by
     * name, the transaction filed under one survives and is still found by
     * search, and the result validates against `4.json`. A v3 table holds
     * only seeded rows, since nothing before v4 could add or rename one, so
     * every row here is given its own name.
     */
    @Test fun versionThreeMigratesToFourNamingEachSeededRow() {
        helper.createDatabase(MIGRATED_3_TO_4, 3).use { v3 ->
            v3.insertSeededPair()
            v3.insertSampleTxn(categoryId = 1)
        }

        helper.runMigrationsAndValidate(MIGRATED_3_TO_4, 4, true, MIGRATION_3_4).use { v4 ->
            assertEquals(
                listOf("Food & Drinks" to "Food & Drinks", "Uncategorized" to "Uncategorized"),
                v4.seedNames(),
            )
            v4.query("SELECT category_id FROM txn WHERE id = 7").use { c ->
                assertTrue("the v3 transaction is gone", c.moveToFirst())
                assertEquals(1L, c.getLong(0))
            }
            assertEquals(listOf("7"), v4.column("SELECT rowid FROM txn_fts WHERE txn_fts MATCH 'yuenkee*'"))
            assertEquals(4L, v4.pragma("user_version"))
        }
    }

    /** A v2 file reaches v4 through the search step and then this one. */
    @Test fun versionTwoMigratesToFour() {
        helper.createDatabase(MIGRATED_2_TO_4, 2).use { v2 ->
            v2.insertSeededPair()
            v2.insertSampleTxn(categoryId = 1)
        }

        helper.runMigrationsAndValidate(MIGRATED_2_TO_4, 4, true, MIGRATION_3_4).use { v4 ->
            assertEquals(
                listOf("Food & Drinks" to "Food & Drinks", "Uncategorized" to "Uncategorized"),
                v4.seedNames(),
            )
            assertEquals(listOf("7"), v4.column("SELECT rowid FROM txn_fts WHERE txn_fts MATCH 'yuenkee*'"))
            assertEquals(4L, v4.pragma("user_version"))
        }
    }

    /** A v1 file, the oldest released, reaches v4 through all three steps. */
    @Test fun versionOneMigratesToFour() {
        helper.createDatabase(MIGRATED_1_TO_4, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO category (id, name, icon_key, sort_order, is_protected) " +
                    "VALUES (1, 'Uncategorized', 'circle-dashed', 0, 1)",
            )
            v1.insertSampleTxn(categoryId = 1)
        }
        helper.runMigrationsAndValidate(MIGRATED_1_TO_4, 4, true, MIGRATION_3_4).use { v4 ->
            assertEquals(listOf("Uncategorized" to "Uncategorized"), v4.seedNames())
            assertEquals(1L, v4.count("txn"))
            assertEquals(listOf("7"), v4.column("SELECT rowid FROM txn_fts WHERE txn_fts MATCH 'yuenkee*'"))
            assertEquals(0L, v4.count("merchant_alias"))
            assertEquals(4L, v4.pragma("user_version"))
        }
    }

    /**
     * Spec 5.7's v4 -> v5: one new table, empty, and every v4 row kept -- the
     * capture's `user_reject_rule_id` among them, which v1 already had.
     */
    @Test fun versionFourMigratesToFiveKeepingEveryRow() {
        helper.createDatabase(MIGRATED_4_TO_5, 4).use { v4 ->
            v4.execSQL(CAPTURE_SQL)
            v4.execSQL("INSERT INTO merchant_alias (merchant_key, canonical_key) VALUES ('A', 'B')")
        }

        helper.runMigrationsAndValidate(MIGRATED_4_TO_5, 5, true).use { v5 ->
            v5.query("SELECT id, parse_status, user_reject_rule_id FROM raw_capture").use { c ->
                assertTrue("the v4 capture is gone", c.moveToFirst())
                assertEquals(3L, c.getLong(0))
                assertEquals("UNMATCHED", c.getString(1))
                assertTrue(c.isNull(2))
                assertEquals(1, c.count)
            }
            assertEquals(1L, v5.count("merchant_alias"))
            assertEquals(0L, v5.count("user_reject_rule"))
            assertEquals(5L, v5.pragma("user_version"))
        }
    }

    /** v3, through the categories step and then this one. */
    @Test fun versionThreeMigratesToFive() {
        helper.createDatabase(MIGRATED_3_TO_5, 3).use { v3 ->
            v3.execSQL(CAPTURE_SQL)
            v3.insertSeededPair()
        }

        helper.runMigrationsAndValidate(MIGRATED_3_TO_5, 5, true, MIGRATION_3_4).use { v5 ->
            assertEquals(1L, v5.count("raw_capture"))
            assertEquals(2L, v5.count("category"))
            assertEquals(0L, v5.count("user_reject_rule"))
            assertEquals(5L, v5.pragma("user_version"))
        }
    }

    /** v2, through the search step, the categories step and this one. */
    @Test fun versionTwoMigratesToFive() {
        helper.createDatabase(MIGRATED_2_TO_5, 2).use { v2 ->
            v2.execSQL(CAPTURE_SQL)
            v2.execSQL("INSERT INTO merchant_alias (merchant_key, canonical_key) VALUES ('A', 'B')")
        }

        helper.runMigrationsAndValidate(MIGRATED_2_TO_5, 5, true, MIGRATION_3_4).use { v5 ->
            assertEquals(1L, v5.count("raw_capture"))
            assertEquals(1L, v5.count("merchant_alias"))
            assertEquals(0L, v5.count("user_reject_rule"))
            assertEquals(5L, v5.pragma("user_version"))
        }
    }

    /** And from v1, through all four steps, as a phone that skipped every release takes it. */
    @Test fun versionOneMigratesToFive() {
        helper.createDatabase(MIGRATED_1_TO_5, 1).use { v1 -> v1.execSQL(CAPTURE_SQL) }

        helper.runMigrationsAndValidate(MIGRATED_1_TO_5, 5, true, MIGRATION_3_4).use { v5 ->
            assertEquals(1L, v5.count("raw_capture"))
            assertEquals(0L, v5.count("merchant_alias"))
            assertEquals(0L, v5.count("user_reject_rule"))
            assertEquals(5L, v5.pragma("user_version"))
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
     * 38 at schema v5, logged on every run. Both sides are built at
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

    private fun SupportSQLiteDatabase.seedNames(): List<Pair<String, String?>> =
        query("SELECT name, seed_name FROM category ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    /** Two seeded categories, ids 1 and 2, as raw SQL at v3 or before. */
    private fun SupportSQLiteDatabase.insertSeededPair() {
        execSQL(
            "INSERT INTO category (id, name, icon_key, sort_order, is_protected) VALUES " +
                "(1, 'Food & Drinks', 'utensils', 0, 0), (2, 'Uncategorized', 'circle-dashed', 15, 1)",
        )
    }

    /** One `txn` row, id 7, written as raw SQL at whatever version [this] is. */
    private fun SupportSQLiteDatabase.insertSampleTxn(categoryId: Long) {
        execSQL(
            "INSERT INTO txn (id, raw_capture_id, amount_sen, currency, direction, occurred_at, " +
                "local_date, merchant_raw, merchant_display, merchant_key, category_id, " +
                "source_package, source_label, confidence, state, pending_reason, is_excluded, " +
                "exclusion_reason, note, user_edited, created_at, updated_at) VALUES " +
                "(7, NULL, 1250, 'MYR', 'EXPENSE', 1788739200000, 20260907, " +
                "'YUENKEEHOMETOWNCAFE', 'Yuenkeehometowncafe', 'YUENKEEHOMETOWNCAFE', $categoryId, " +
                "NULL, NULL, 'HIGH', 'COMMITTED', NULL, 0, NULL, NULL, 0, 1, 1)",
        )
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
