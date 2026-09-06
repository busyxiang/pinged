package my.pinged.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting a category that transactions point at.
 *
 * The failure this prevents is not a crash, it is silence: with no foreign key,
 * `DELETE FROM category WHERE id = ?` succeeds on an in-use category and leaves
 * `txn` rows pointing at nothing. Those rows then either disappear from every
 * total (an INNER JOIN, the natural way to write the list query) or throw at
 * render (a LEFT JOIN into a non-null Kotlin field). The first loses money with
 * no error anywhere.
 *
 * The declared constraint is only half the story. This database opens through
 * SQLCipher's `SupportOpenHelperFactory`, so whether `PRAGMA foreign_keys` is
 * actually ON on the connection Room writes through has to be measured. A
 * foreign key that is declared but not enforced is worse than none, because it
 * reads as protection and provides none.
 */
@RunWith(AndroidJUnit4::class)
class CategoryDeleteTest {
    private lateinit var db: PingedDatabase

    /** Not Uncategorized: an ordinary, deletable category. */
    private var food: Long = 0
    private var groceries: Long = 0
    private var uncategorized: Long = 0

    @Before fun setUp() {
        db = freshDatabase()
        val byName = db.categoryDao().all().associate { it.name to it.id }
        food = byName.getValue("Food & Drinks")
        groceries = byName.getValue("Groceries")
        uncategorized = byName.getValue(Seed.UNCATEGORIZED)
    }

    @After fun tearDown() {
        db.close()
    }

    // ---- is the constraint live at all? --------------------------------

    /**
     * Reads the pragma back off both paths that write to this database: the
     * connection Room's generated `onOpen` set it on, and the open helper the rest
     * of the module reaches for. SQLCipher keeps its own connection pool and
     * applies `foreignKeyConstraintsEnabled` from its `SQLiteDatabaseConfiguration`
     * to each connection it opens, so a per-connection `execSQL` is not
     * automatically pool-wide. Both numbers are logged because they are the
     * evidence.
     */
    @Test fun theForeignKeyPragmaIsOnEveryConnectionThisModuleWritesThrough() {
        val viaRoom = db.query(SimpleSQLiteQuery("PRAGMA foreign_keys")).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
        val viaWritable = db.openHelper.writableDatabase.foreignKeysPragma()
        val viaReadable = db.openHelper.readableDatabase.foreignKeysPragma()
        Log.i(
            OpenTest.REPORT_TAG,
            "PRAGMA foreign_keys: room=$viaRoom writable=$viaWritable readable=$viaReadable",
        )
        assertEquals("PRAGMA foreign_keys is OFF on Room's connection", 1, viaRoom)
        assertEquals("PRAGMA foreign_keys is OFF on the writable connection", 1, viaWritable)
        assertEquals("PRAGMA foreign_keys is OFF on the readable connection", 1, viaReadable)
    }

    /**
     * The test that proves enforcement rather than declaration. It calls
     * [my.pinged.data.dao.CategoryDao.deleteRow] directly, bypassing
     * `deleteIfUnused`'s Kotlin-side count check, because the Kotlin check
     * would pass this test on a database with no constraint at all.
     */
    @Test fun foreignKeysAreEnforcedNotJustDeclared() {
        db.txnDao().insert(sampleTxn(categoryId = food))

        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.categoryDao().deleteRow(food)
        }
        Log.i(
            OpenTest.REPORT_TAG,
            "in-use category delete threw ${thrown.javaClass.name}: ${thrown.message}",
        )
        assertTrue(
            "Wrong constraint fired: ${thrown.message}",
            thrown.message.orEmpty().contains("FOREIGN KEY constraint failed"),
        )
        // And nothing was lost on the way past.
        assertEquals(1, db.txnDao().countAll())
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
    }

    /**
     * `merchant_rule` is the quieter half of the same problem, so it gets its
     * own test: no transaction is involved here, only a learned rule.
     */
    @Test fun aCategoryHeldOnlyByALearnedRuleCannotBeDeletedEither() {
        db.insertMerchantRule("MCD KLCC", food)
        assertEquals(0, db.categoryDao().countTransactions(food))

        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.categoryDao().deleteRow(food)
        }
        Log.i(OpenTest.REPORT_TAG, "rule-only category delete threw: ${thrown.message}")
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
    }

    /**
     * The insert side of the same constraint. A transaction whose category was
     * never created must be refused at write time; otherwise a bug in the
     * categorizer writes an orphan directly instead of a delete producing one.
     */
    @Test fun aTransactionCannotBeWrittenWithACategoryThatDoesNotExist() {
        val thrown = assertThrows(SQLiteConstraintException::class.java) {
            db.txnDao().insert(sampleTxn(categoryId = 9_999))
        }
        Log.i(OpenTest.REPORT_TAG, "orphan insert threw: ${thrown.message}")
        assertEquals(0, db.txnDao().countAll())
    }

    // ---- blast radius counts -------------------------------------------

    @Test fun theCountsTheSheetShowsAreBothCorrect() {
        db.txnDao().insert(sampleTxn(categoryId = food, occurredAt = 1_000))
        db.txnDao().insert(sampleTxn(categoryId = food, occurredAt = 2_000))
        db.txnDao().insert(sampleTxn(categoryId = groceries, occurredAt = 3_000))
        db.insertMerchantRule("MCD KLCC", food)
        db.insertMerchantRule("TEALIVE", food)
        db.insertMerchantRule("JAYA GROCER", groceries)

        assertEquals(2, db.categoryDao().countTransactions(food))
        assertEquals(2, db.categoryDao().countRules(food))
        assertEquals(1, db.categoryDao().countTransactions(groceries))
        assertEquals(1, db.categoryDao().countRules(groceries))
        assertEquals(0, db.categoryDao().countTransactions(uncategorized))
    }

    // ---- deleteIfUnused ------------------------------------------------

    @Test fun deleteIfUnusedRemovesACategoryNothingPointsAt() {
        db.categoryDao().deleteIfUnused(food)
        assertEquals(13, db.categoryDao().countAll())
        assertNull(db.categoryDao().isProtected(food))
    }

    @Test fun deleteIfUnusedRefusesACategoryWithTransactions() {
        db.txnDao().insert(sampleTxn(categoryId = food))
        val thrown = assertThrows(IllegalStateException::class.java) {
            db.categoryDao().deleteIfUnused(food)
        }
        assertTrue("Unhelpful message: ${thrown.message}", thrown.message.orEmpty().contains("in use"))
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
        assertEquals(1, db.txnDao().countAll())
    }

    @Test fun deleteIfUnusedRefusesACategoryWithOnlyALearnedRule() {
        db.insertMerchantRule("MCD KLCC", food)
        assertThrows(IllegalStateException::class.java) {
            db.categoryDao().deleteIfUnused(food)
        }
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
        assertEquals(listOf(food), db.merchantRuleCategoryIds())
    }

    @Test fun deleteIfUnusedRefusesUncategorized() {
        // Protected, and spec 7.1's confidence gate resolves to it by id: with
        // it gone the app has nowhere to put an unknown merchant.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.categoryDao().deleteIfUnused(uncategorized)
        }
        assertTrue(thrown.message.orEmpty().contains("protected"))
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
    }

    @Test fun deleteIfUnusedRefusesACategoryThatIsNotThere() {
        assertThrows(IllegalArgumentException::class.java) {
            db.categoryDao().deleteIfUnused(9_999)
        }
    }

    // ---- mergeInto -----------------------------------------------------

    /**
     * Both child tables, in one call. The `merchant_rule` half is the one a
     * future edit is likely to drop, which is why it is asserted separately
     * from the `txn` half rather than folded into a single "merge worked".
     */
    @Test fun mergeIntoMovesTransactionsAndRulesThenDeletesTheSource() {
        val moved = db.txnDao().insert(sampleTxn(categoryId = food, occurredAt = 1_000))
        val untouched = db.txnDao().insert(sampleTxn(categoryId = groceries, occurredAt = 2_000))
        db.insertMerchantRule("MCD KLCC", food)
        db.insertMerchantRule("JAYA GROCER", groceries)

        db.categoryDao().mergeInto(fromId = food, toId = groceries)

        val txns = db.txnDao().recent(10).associate { it.id to it.categoryId }
        assertEquals("The merged transaction did not move", groceries, txns[moved])
        assertEquals("An unrelated transaction changed category", groceries, txns[untouched])
        assertEquals(
            "The learned rule did not move with the category",
            listOf(groceries, groceries),
            db.merchantRuleCategoryIds(),
        )
        assertEquals("The source category survived the merge", 13, db.categoryDao().countAll())
        assertNull(db.categoryDao().isProtected(food))
        assertEquals(2, db.categoryDao().countTransactions(groceries))
        assertEquals(2, db.categoryDao().countRules(groceries))
    }

    @Test fun mergeIntoWorksForACategoryHeldOnlyByRules() {
        db.insertMerchantRule("MCD KLCC", food)
        db.categoryDao().mergeInto(fromId = food, toId = groceries)
        assertEquals(listOf(groceries), db.merchantRuleCategoryIds())
        assertEquals(13, db.categoryDao().countAll())
    }

    @Test fun mergeIntoRefusesMergingACategoryIntoItself() {
        db.txnDao().insert(sampleTxn(categoryId = food))
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.categoryDao().mergeInto(fromId = food, toId = food)
        }
        assertTrue("Unhelpful message: ${thrown.message}", thrown.message.orEmpty().contains("itself"))
        // The self-merge would have deleted the surviving row after
        // "reassigning" its rows onto itself.
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
        assertEquals(1, db.categoryDao().countTransactions(food))
    }

    /**
     * The load-bearing refusal. Uncategorized is protected because spec 7.1's
     * confidence gate resolves to it by id: merge it away and an unknown
     * merchant has nowhere to land.
     */
    @Test fun mergeIntoRefusesUncategorizedAsTheSource() {
        db.txnDao().insert(sampleTxn(categoryId = uncategorized))
        db.insertMerchantRule("PASAR MALAM", uncategorized)
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.categoryDao().mergeInto(fromId = uncategorized, toId = food)
        }
        assertTrue("Unhelpful message: ${thrown.message}", thrown.message.orEmpty().contains("protected"))
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
        assertEquals(1, db.categoryDao().countTransactions(uncategorized))
        assertEquals(listOf(uncategorized), db.merchantRuleCategoryIds())
    }

    /**
     * The other direction is allowed, and is what makes deleting a used category
     * possible at all. `is_protected` stops Uncategorized being renamed, re-iconed
     * or deleted (spec 4); a merge into it does none of those. It is also the
     * truthful destination -- spec 7.1 makes Uncategorized the home for "we don't
     * know what this is", which is what a transaction becomes when its category is
     * removed.
     */
    @Test fun mergeIntoAcceptsUncategorizedAsTheTarget() {
        val moved = db.txnDao().insert(sampleTxn(categoryId = food))
        db.insertMerchantRule("MCD KLCC", food)

        db.categoryDao().mergeInto(fromId = food, toId = uncategorized)

        assertEquals("The source category survived", 13, db.categoryDao().countAll())
        assertNull(db.categoryDao().isProtected(food))
        // Uncategorized itself is untouched, and still protected.
        assertEquals(true, db.categoryDao().isProtected(uncategorized))
        assertEquals(uncategorized, db.categoryDao().requireUncategorizedId())
        // ...and it now carries what the deleted category was holding.
        assertEquals(
            uncategorized,
            db.txnDao().recent(10).single { it.id == moved }.categoryId,
        )
        assertEquals(listOf(uncategorized), db.merchantRuleCategoryIds())
        assertEquals(1, db.categoryDao().countTransactions(uncategorized))
        assertEquals(1, db.categoryDao().countRules(uncategorized))
    }

    @Test fun mergeIntoRefusesAMissingTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            db.categoryDao().mergeInto(fromId = food, toId = 9_999)
        }
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
    }

    /**
     * The rollback, asserted from the outside. `mergeInto` is one
     * `@Transaction`, so a refusal must leave nothing half-applied -- not the
     * reassignment, not the delete. Here the target is missing, which is
     * caught before any write; the constraint backstop covers the other
     * direction, where a reassignment is missed and the DELETE fails.
     */
    @Test fun aRefusedMergeLeavesEveryRowWhereItWas() {
        val id = db.txnDao().insert(sampleTxn(categoryId = food))
        db.insertMerchantRule("MCD KLCC", food)
        runCatching { db.categoryDao().mergeInto(fromId = food, toId = 9_999) }
        assertEquals(food, db.txnDao().recent(10).single { it.id == id }.categoryId)
        assertEquals(listOf(food), db.merchantRuleCategoryIds())
        assertEquals(Seed.categories().size, db.categoryDao().countAll())
    }
}
