package my.pinged.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Seed
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.ParseStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The constraints added while schema version 1 is still unshipped, and the
 * wrong answers each of them was previously able to produce.
 */
@RunWith(AndroidJUnit4::class)
class SchemaConstraintTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
    }

    @After fun tearDown() = db.close()

    // ---- category.name is unique ---------------------------------------

    /**
     * `name` is the one seeded string that crosses into SQL, and the lookup is
     * `WHERE name = ? LIMIT 1`. With the column unconstrained, a second
     * `Uncategorized` -- reachable through the rename dialog and through "add
     * a category" -- made that `LIMIT 1` pick whichever row the plan reached
     * first, so which of two categories new uncategorized spending landed in
     * was a property of the query plan.
     */
    @Test fun asecondCategoryWithTheSameNameIsRefused() {
        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.categoryDao().insertAll(
                listOf(Category(name = Seed.UNCATEGORIZED, iconKey = "circle-dashed", sortOrder = 99)),
            )
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "duplicate category name: ${thrown.message}")
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
    }

    @Test fun aDistinctCategoryNameIsStillAccepted() {
        db.categoryDao().insertAll(
            listOf(Category(name = "Pets", iconKey = "dog", sortOrder = 14)),
        )
        assertEquals(Seed.categories().size + 1, db.categoryDao().countAll())
    }

    // ---- CategoryDao.uncategorizedId returned a literal 0 ---------------

    /**
     * Declared non-null, Room generates `if (statement.step()) getLong(0) else 0L`,
     * so a missing row returns `0` -- not a valid `autoGenerate` id, and matching no
     * category. Spec 4 gives `txn.category_id` a foreign key with no DDL default
     * precisely so an unresolved category fails with SQLite 787 instead of landing
     * somewhere plausible; a silent `0` is that plausible landing, and moves the
     * failure far from the missing seed.
     */
    @Test fun uncategorizedIdIsNullRatherThanZeroWhenTheRowIsMissing() {
        assertNull(db.categoryDao().uncategorizedIdOrNull("No Such Category"))
    }

    @Test fun requireUncategorizedIdFailsNamingTheMissingCategory() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            db.categoryDao().requireUncategorizedId("No Such Category")
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "missing category: ${thrown.message}")
        assertTrue(
            "The failure does not name the category it looked for: ${thrown.message}",
            thrown.message!!.contains("No Such Category"),
        )
    }

    @Test fun requireUncategorizedIdReturnsTheSeededRow() {
        val id = db.categoryDao().requireUncategorizedId()
        assertTrue("id 0 is not a valid autoGenerate id", id > 0)
        assertEquals(Seed.UNCATEGORIZED, db.categoryDao().all().single { it.id == id }.name)
    }

    // ---- merchant_rule uniqueness --------------------------------------

    /**
     * Two equal-priority `LEARNED` rows for one pattern made the resolved
     * category depend on which row the plan reached first, so one merchant
     * could file under two different categories on two different days with
     * nothing in the app able to explain it.
     */
    @Test fun twoScopedRulesForTheSamePatternAndTypeAreRefused() {
        val food = db.categoryId("Food & Drinks")
        val shopping = db.categoryId("Shopping")
        db.insertMerchantRule("MCD KLCC", food, scopedPackage = "com.maybank2u.life")

        assertThrows(SQLiteConstraintException::class.java) {
            db.insertMerchantRule("MCD KLCC", shopping, scopedPackage = "com.maybank2u.life")
        }
        assertEquals(listOf(food), db.merchantRuleCategoryIds())
    }

    /**
     * **The other half of the same finding, and the shape that actually happens.**
     *
     * While `scoped_package` was nullable the unique index did not reach this case:
     * SQLite treats every NULL in a unique index as distinct, so two *unscoped*
     * rules for one pattern and match type both inserted -- and unscoped is exactly
     * what spec 6.1's learned-rule writer produces, since spec 4's `merchant_rule`
     * does not list `scoped_package` at all. The index defended only the case that
     * could not occur.
     *
     * Room's `@Index` takes only `value`, `name`, `unique` and `orders`, so a
     * `COALESCE(scoped_package, '')` expression index is not declarable (spec 15.1
     * makes the same point about partial indexes). The fix is the column: NOT NULL
     * with `''` as the "not scoped" sentinel, which collides with itself.
     */
    @Test fun twoUnscopedRulesForTheSamePatternAndTypeAreRefused() {
        val food = db.categoryId("Food & Drinks")
        val shopping = db.categoryId("Shopping")
        db.insertMerchantRule("PASAR MALAM", food)

        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.insertMerchantRule("PASAR MALAM", shopping)
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "duplicate unscoped rule: ${thrown.message}")
        assertEquals(
            "The second unscoped rule landed, so the resolved category is still " +
                "a property of the query plan",
            listOf(food),
            db.merchantRuleCategoryIds(),
        )
    }

    /**
     * The sentinel is the *default*, so the writer does not have to remember
     * it: a rule written with no scope at all and a rule written with an
     * explicit `MerchantRule.UNSCOPED` are the same rule and collide.
     */
    @Test fun theExplicitSentinelAndTheDefaultAreTheSameRule() {
        val food = db.categoryId("Food & Drinks")
        val shopping = db.categoryId("Shopping")
        db.insertMerchantRule("KEDAI MAKAN", food, scopedPackage = MerchantRule.UNSCOPED)
        assertThrows(SQLiteConstraintException::class.java) {
            db.insertMerchantRule("KEDAI MAKAN", shopping)
        }
        assertEquals(listOf(food), db.merchantRuleCategoryIds())
    }

    /**
     * The column refuses a null outright, which is what makes the sentinel the only
     * representation of "unscoped" rather than one of two -- with both available, a
     * writer reaching for null would put the pre-fix behaviour back.
     *
     * Production cannot express this (`MerchantRule.scopedPackage` is a non-null
     * `String`), so it goes in through the raw fixture.
     */
    @Test fun anExplicitNullScopeIsRefusedByTheColumn() {
        val food = db.categoryId("Food & Drinks")
        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.insertMerchantRule("WARUNG", food, scopedPackage = null)
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "null scoped_package: ${thrown.message}")
        assertEquals(emptyList<Long>(), db.merchantRuleCategoryIds())
    }

    /** And the declared column, so nobody quietly relaxes it back. */
    @Test fun scopedPackageIsDeclaredNotNull() {
        db.openHelper.readableDatabase.query("PRAGMA table_info(merchant_rule)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val notNullIdx = c.getColumnIndexOrThrow("notnull")
            val defaultIdx = c.getColumnIndexOrThrow("dflt_value")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == "scoped_package") {
                    assertEquals("scoped_package is nullable again", 1, c.getInt(notNullIdx))
                    assertEquals("''", c.getString(defaultIdx))
                    return
                }
            }
        }
        throw AssertionError("No column merchant_rule.scoped_package")
    }

    /**
     * **The case variant, which the unique index accepted until `pattern` got
     * `COLLATE NOCASE`.**
     *
     * Spec 4 says `pattern` is matched against `merchant_raw` case-insensitively.
     * The column was BINARY, so `MCD KLCC` and `mcd klcc` were two rows to the
     * unique index -- and the case-insensitive lookup then matched both at the same
     * priority: one merchant, two categories, decided by whichever the plan reached
     * first. Measured on emulator-5554 before the collation, the second insert was
     * ACCEPTED and the lookup returned both rows, categories `[1, 7]`.
     *
     * Spec 6.1's writer normalizes to uppercase, so the learning path could not do
     * this to itself -- but the bundled dictionary is authored mixed-case and an
     * imported pack (spec 5.9) is authored by a stranger.
     */
    @Test fun aCaseVariantOfAnExistingPatternIsTheSameRule() {
        val food = db.categoryId("Food & Drinks")
        val shopping = db.categoryId("Shopping")
        db.insertMerchantRule("MCD KLCC", food)

        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.insertMerchantRule("mcd klcc", shopping)
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "case-variant rule: ${thrown.message}")
        assertEquals(listOf(food), db.merchantRuleCategoryIds())
    }

    /**
     * And the lookup spec 4 describes now finds exactly one rule, which is the
     * property the collation was added for -- refusing the insert is the
     * mechanism, not the goal.
     */
    @Test fun theCaseInsensitiveLookupFindsExactlyOneRule() {
        val food = db.categoryId("Food & Drinks")
        db.insertMerchantRule("MCD KLCC", food)
        db.openHelper.readableDatabase.query(
            "SELECT pattern, category_id FROM merchant_rule WHERE pattern = ? " +
                "ORDER BY priority DESC",
            arrayOf<Any>("mcd klcc"),
        ).use { c ->
            val hits = mutableListOf<Pair<String, Long>>()
            while (c.moveToNext()) hits += c.getString(0) to c.getLong(1)
            android.util.Log.i(OpenTest.REPORT_TAG, "case-insensitive lookup: $hits")
            // A plain `=` is case-insensitive now, because the column is: the
            // lookup does not have to remember a COLLATE clause, which is the
            // other half of putting the guarantee on the column.
            assertEquals(listOf("MCD KLCC" to food), hits)
        }
    }

    /**
     * **The collation is invisible to every mechanism that normally catches a
     * schema change, so it is asserted off the live table.**
     *
     * Room's generated DDL carries it and so does `schemas/1.json`, but the
     * **identity hash does not cover it** -- the hash did not move when it was
     * added -- and `PRAGMA table_info`, which `onValidateSchema` reads, does not
     * report collation. So deleting `collate = ColumnInfo.NOCASE` would compile,
     * export an identical schema, need no `Migration`, and silently restore the
     * two-categories-for-one-merchant bug.
     *
     * Same blindness `EnumVocabularyTest` exists for, same answer: assert the thing
     * on disk. `sqlite_master` holds the DDL as executed.
     */
    @Test fun theCollationIsPresentOnTheLiveTable() {
        db.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'merchant_rule'",
        ).use { c ->
            assertTrue("No merchant_rule table", c.moveToFirst())
            val ddl = c.getString(0)
            android.util.Log.i(OpenTest.REPORT_TAG, "merchant_rule DDL: $ddl")
            assertTrue(
                "pattern lost COLLATE NOCASE, and neither the identity hash nor " +
                    "Room's schema validation can see that. DDL: $ddl",
                ddl.contains("`pattern` TEXT NOT NULL COLLATE NOCASE"),
            )
        }
    }

    /**
     * A pattern that differs by more than case is still its own rule; the
     * collation must not have collapsed anything else.
     */
    @Test fun theCollationOnlyFoldsCase() {
        val food = db.categoryId("Food & Drinks")
        db.insertMerchantRule("MCD KLCC", food)
        db.insertMerchantRule("MCD KLCC 2", food)
        db.insertMerchantRule("MCD  KLCC", food)
        assertEquals(3, db.merchantRuleCategoryIds().size)
    }

    // ---- category.name, and why it is deliberately left BINARY ----------

    /**
     * **A recorded decision, not an oversight.** `category.name` carries a unique
     * index and SQLite's default BINARY collation, so `Groceries` and `groceries`
     * can both exist. `merchant_rule.pattern` was given `COLLATE NOCASE` and this
     * column deliberately was not:
     *
     * 1. **No case-insensitive semantic is declared for it.** The `pattern` fix
     *    closed a contradiction with spec 4; spec 4 says nothing of the kind here,
     *    so NOCASE would invent a rule rather than enforce one.
     * 2. **Nothing resolves a category by a name that can vary.** The single name
     *    lookup compares against [Seed.UNCATEGORIZED], a code constant, matched
     *    against a seeded row written from that constant and protected against
     *    rename. An added `uncategorized` in another case is a separate unprotected
     *    row the exact match never reaches -- no wrong-money path, unlike two rules
     *    for one merchant.
     * 3. **The failure is visible and already has a recovery.** Two near-identical
     *    categories appear immediately in the list and the charts, and
     *    `CategoryDao.mergeInto` collapses them.
     *
     * NOCASE would also be a partial guarantee sold as a whole one: it folds ASCII
     * `A-Z` only, so it refuses `groceries` beside `Groceries` and accepts `CAFE`
     * beside an accented `cafe`. "That name is taken" belongs in the add/rename
     * validator, where it can fold properly and explain itself -- and being Kotlin,
     * is not blocked by the v1 freeze.
     *
     * This asserts today's behaviour so the decision is revisited deliberately
     * rather than discovered.
     */
    @Test fun categoryNamesAreCaseSensitiveDeliberately() {
        db.categoryDao().insertAll(
            listOf(Category(name = "groceries", iconKey = "shopping-basket", sortOrder = 14)),
        )
        assertEquals(Seed.categories().size + 1, db.categoryDao().countAll())
        // The exact-match lookup is unaffected, which is the point of (2).
        val uncategorized = db.categoryDao().requireUncategorizedId()
        db.categoryDao().insertAll(
            listOf(Category(name = "UNCATEGORIZED", iconKey = "circle-dashed", sortOrder = 15)),
        )
        assertEquals(
            "The Uncategorized lookup moved to a different row",
            uncategorized,
            db.categoryDao().requireUncategorizedId(),
        )
        assertTrue(
            "The row the lookup resolves to is no longer the protected one",
            db.categoryDao().isProtected(uncategorized) == true,
        )
    }

    /** The parts of the key that legitimately make a different rule. */
    @Test fun theSamePatternUnderADifferentTypeOrScopeIsADifferentRule() {
        val food = db.categoryId("Food & Drinks")
        db.insertMerchantRule("MCD", food, matchType = MatchType.EXACT, scopedPackage = "com.a")
        db.insertMerchantRule("MCD", food, matchType = MatchType.CONTAINS, scopedPackage = "com.a")
        db.insertMerchantRule("MCD", food, matchType = MatchType.EXACT, scopedPackage = "com.b")
        // And unscoped is a fourth rule, not a duplicate of any of them: `''`
        // is a value in the key like any other.
        db.insertMerchantRule("MCD", food, matchType = MatchType.EXACT)
        assertEquals(4, db.merchantRuleCategoryIds().size)
    }

    // ---- txn(raw_capture_id) is still unique ---------------------------

    @Test fun oneCaptureStillCannotProduceTwoTransactions() {
        val uncategorized = db.categoryDao().requireUncategorizedId()
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "once"))
        db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = uncategorized))
        assertThrows(SQLiteConstraintException::class.java) {
            db.txnDao().insert(sampleTxn(rawCaptureId = capture, categoryId = uncategorized))
        }
        assertEquals(1, db.txnDao().countAll())
    }

    // ---- spec 15.5's keyset cursor -------------------------------------

    /**
     * The re-parse cursor of spec 15.5, which "records its position so an
     * interrupted run resumes rather than restarting". The position is the
     * last `id`, and that is what makes resumption exact: an `OFFSET` cursor's
     * position shifts under any concurrent insert, and the listener is
     * inserting throughout.
     */
    @Test fun theKeysetCursorWalksAStatusInIdOrderAndResumesExactly() {
        val dao = db.rawCaptureDao()
        val unmatched = (0 until 7).map {
            dao.insert(
                sampleCapture(hash = "u$it", sbnKey = "us$it", status = ParseStatus.UNMATCHED),
            )
        }
        // Interleaved rows of another status, which the cursor must skip.
        repeat(7) { dao.insert(sampleCapture(hash = "m$it", sbnKey = "ms$it", status = ParseStatus.MATCHED)) }

        val walked = mutableListOf<Long>()
        var cursor = 0L
        while (true) {
            val chunk = dao.pageAfter(ParseStatus.UNMATCHED, cursor, 3)
            if (chunk.isEmpty()) break
            walked += chunk.map { it.id }
            cursor = chunk.last().id
        }
        assertEquals(unmatched, walked)
    }

    /**
     * Resumption under a concurrent insert, which is the case `OFFSET` gets
     * wrong. A row inserted behind the cursor shifts every offset; the keyset
     * cursor does not notice, and a row inserted *ahead* of it is picked up.
     */
    @Test fun theKeysetCursorIsNotDisturbedByInsertsBehindIt() {
        val dao = db.rawCaptureDao()
        val first = (0 until 4).map {
            dao.insert(sampleCapture(hash = "a$it", sbnKey = "as$it", status = ParseStatus.UNMATCHED))
        }
        val chunk = dao.pageAfter(ParseStatus.UNMATCHED, 0L, 2)
        assertEquals(first.take(2), chunk.map { it.id })

        // The listener writes while the job is between chunks.
        val late = dao.insert(
            sampleCapture(hash = "late", sbnKey = "lates", status = ParseStatus.UNMATCHED),
        )

        val rest = dao.pageAfter(ParseStatus.UNMATCHED, chunk.last().id, 10)
        assertEquals(first.drop(2) + late, rest.map { it.id })
    }

    @Test fun theKeysetCursorHonoursItsLimit() {
        val dao = db.rawCaptureDao()
        repeat(10) { dao.insert(sampleCapture(hash = "k$it", sbnKey = "ks$it", status = ParseStatus.REJECTED)) }
        assertEquals(4, dao.pageAfter(ParseStatus.REJECTED, 0L, 4).size)
        assertEquals(0, dao.pageAfter(ParseStatus.REJECTED, Long.MAX_VALUE, 4).size)
    }
}
