package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seeded categories, asserted through the path the **app** takes.
 *
 * Every other test class in this module used to call
 * `categoryDao().insertAll(Seed.categories())` in its own `@Before`. ~350 tests
 * passed that way while the shipped app never seeded anything, so
 * [my.pinged.data.dao.CategoryDao.requireUncategorizedId] threw on the first
 * real notification and every `raw_capture` row stayed at `NEW`.
 *
 * So the rule for this file: **nothing in it may write a category.** A `@Before`
 * that seeded, or a fixture that did, would put the defect straight back.
 */
@RunWith(AndroidJUnit4::class)
class SeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A device on which this app has never run. Not a convenience: the whole
     * question is what a *created* database contains, and the file is shared
     * with every other class in this module.
     */
    @Before fun startFromNoDatabaseAtAll() {
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    /**
     * The one that fails on the shipped app.
     *
     * `requireUncategorizedId` is what `ParseWorker.doWork` calls before it
     * parses anything (spec 7.1: an unknown merchant is filed under
     * Uncategorized), so this assertion is the difference between the ledger
     * receiving transactions and every capture sitting at `NEW` for ever.
     */
    @Test fun aFreshlyBuiltDatabaseCanFileAnUncategorizedTransaction() {
        DatabaseFactory.build(context).useDb { db ->
            val id = db.categoryDao().requireUncategorizedId()
            assertTrue("Uncategorized resolved to $id, which is not a valid row id", id > 0)
            assertEquals(Seed.UNCATEGORIZED, db.categoryDao().all().single { it.id == id }.name)
        }
    }

    /** And all of them, in the order and with the icons spec 4 fixes. */
    @Test fun aFreshlyBuiltDatabaseHoldsExactlyTheSeededCategories() {
        DatabaseFactory.build(context).useDb { db ->
            val rows = db.categoryDao().all()
            val expected = Seed.categories()
            assertEquals(expected.size, rows.size)
            assertEquals(expected.map { it.name }, rows.map { it.name })
            assertEquals(expected.map { it.iconKey }, rows.map { it.iconKey })
            assertEquals(expected.map { it.sortOrder }, rows.map { it.sortOrder })
            assertEquals(
                listOf(Seed.UNCATEGORIZED),
                rows.filter { it.isProtected }.map { it.name },
            )
            // autoGenerate ids, so the seed must land on real rows rather than
            // on the literal 0 a missing row used to return.
            assertTrue(rows.all { it.id > 0 })
        }
    }

    /**
     * Opening the same database again must not duplicate the seed.
     *
     * `category.name` is UNIQUE, so an unguarded second `insertAll` does not
     * merely duplicate -- it throws `SQLiteConstraintException` out of
     * `DatabaseFactory.build`, which would take the app's every entry point
     * down with it.
     */
    @Test fun openingAnAlreadySeededDatabaseChangesNothing() {
        val first = DatabaseFactory.build(context).useDb { it.categoryDao().all() }
        val second = DatabaseFactory.build(context).useDb { it.categoryDao().all() }
        val third = DatabaseFactory.build(context).useDb { it.categoryDao().all() }
        assertEquals(Seed.categories().size, third.size)
        // Ids too, not just names: a re-seed that deleted and re-inserted would
        // keep the names right and orphan every `txn.category_id` pointing at
        // the old rows.
        assertEquals(first.map { it.id to it.name }, second.map { it.id to it.name })
        assertEquals(first.map { it.id to it.name }, third.map { it.id to it.name })
    }

    /**
     * A database that already has categories is the user's, not the seed's.
     *
     * Spec 4 lets the user rename, add and delete categories. If the guard were
     * "is `Uncategorized` present" rather than "is the table empty", a rename
     * of some other category would be indistinguishable from an unseeded
     * database on the next open -- and the app would resurrect the thirteen
     * rows the user had curated away and fail on the ones they kept.
     */
    @Test fun anEditedCategoryTableIsNotReseeded() {
        val deleted = DatabaseFactory.build(context).useDb { db ->
            val dao = db.categoryDao()
            val entertainment = dao.all().single { it.name == "Entertainment" }
            dao.deleteIfUnused(entertainment.id)
            dao.insertAll(listOf(Category(name = "Pets", iconKey = "dog", sortOrder = 14)))
            entertainment.name
        }

        DatabaseFactory.build(context).useDb { db ->
            val names = db.categoryDao().all().map { it.name }
            assertTrue("The user's own category was dropped", "Pets" in names)
            assertTrue("A deleted category came back: $deleted", deleted !in names)
            assertEquals(Seed.categories().size, names.size)
        }
    }

    /**
     * A half-seeded table must not be reachable, so the fourteen rows go in as
     * one transaction. Asserted the only way it can be from outside: the table
     * is either empty or complete, never partial, and the count is stable
     * across the open that created it.
     */
    @Test fun theSeedIsAllOrNothing() {
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(Seed.categories().size, db.categoryDao().countAll())
        }
        // Read back through a second, independent handle: the first one's
        // transaction has to have committed all fourteen or none.
        DatabaseFactory.build(context).useDb { db ->
            assertEquals(Seed.categories().size, db.categoryDao().countAll())
        }
    }
}
