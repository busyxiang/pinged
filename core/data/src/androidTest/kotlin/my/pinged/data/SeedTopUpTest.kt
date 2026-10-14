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
 * An install that predates Loans & financing and Insurance & takaful (#134),
 * opened by a build that seeds them.
 *
 * Kept out of [SeedTest], whose rule is that nothing in it writes a category:
 * the old install has to be written here, because no build of the app can
 * produce it any more.
 */
@RunWith(AndroidJUnit4::class)
class SeedTopUpTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startFromNoDatabaseAtAll() {
        context.deleteDatabase(DatabaseFactory.NAME)
    }

    /**
     * A database left as the fourteen-row seed made it, with money and a
     * learned rule pointing at its categories, reopened.
     */
    private fun anInstallFromBeforeTheTopUp(): Pair<List<Long>, List<Long>> =
        DatabaseFactory.build(context).useDb { db ->
            val dao = db.categoryDao()
            dao.deleteAllForImport()
            dao.insertAll(FOURTEEN)
            val education = db.categoryId("Education")
            val uncategorized = dao.requireUncategorizedId()
            db.txnDao().insert(sampleTxn(categoryId = education))
            db.txnDao().insert(sampleTxn(occurredAt = 2_000L, categoryId = uncategorized))
            db.insertMerchantRule("PTPTN APPS", categoryId = education)
            db.txnCategoryIds() to db.merchantRuleCategoryIds()
        }

    @Test fun anInstallFromBeforeGainsTheTwoCategoriesAndKeepsEveryRow() {
        val (txns, rules) = anInstallFromBeforeTheTopUp()

        DatabaseFactory.build(context).useDb { db ->
            val rows = db.categoryDao().all()
            assertEquals(
                "The fourteen rows are not all still there under their own ids",
                FOURTEEN.map { it.id to it.name },
                rows.filter { it.id <= 14 }.sortedBy { it.id }.map { it.id to it.name },
            )
            assertEquals(14L, db.categoryDao().requireUncategorizedId())
            assertEquals(
                listOf(
                    "Food & Drinks", "Groceries", "Transport", "Petrol & tolls",
                    "Bills & utilities", "Telco & internet", "Shopping", "Health",
                    "Education", "Family", "Religious & zakat", "Government & fees",
                    "Entertainment", LOANS, INSURANCE, Seed.UNCATEGORIZED,
                ),
                rows.map { it.name },
            )
            assertEquals(listOf(13, 14, 15), rows.takeLast(3).map { it.sortOrder })
            assertEquals(listOf("hand-coins", "shield"), rows.filter { it.name == LOANS || it.name == INSURANCE }.map { it.iconKey })
            assertEquals(listOf(Seed.UNCATEGORIZED), rows.filter { it.isProtected }.map { it.name })
            assertEquals("A transaction moved category", txns, db.txnCategoryIds())
            assertEquals("A learned rule moved category", rules, db.merchantRuleCategoryIds())
        }
    }

    /** The top-up runs once: every later open finds nothing to do. */
    @Test fun aToppedUpInstallIsLeftAloneOnEveryLaterOpen() {
        anInstallFromBeforeTheTopUp()
        val first = DatabaseFactory.build(context).useDb { it.categoryDao().all() }
        val second = DatabaseFactory.build(context).useDb { it.categoryDao().all() }
        assertEquals(16, first.size)
        assertEquals(first, second)
    }

    /**
     * Once topped up, a deleted category stays deleted, the new ones included:
     * spec 4 lets the user delete an unused category, and a top-up that
     * re-inserted every missing seeded name would undo that on the next open.
     */
    @Test fun aCategoryDeletedAfterTheTopUpStaysDeleted() {
        anInstallFromBeforeTheTopUp()
        DatabaseFactory.build(context).useDb { db ->
            db.categoryDao().deleteIfUnused(db.categoryId(INSURANCE))
            db.categoryDao().deleteIfUnused(db.categoryId("Entertainment"))
        }
        DatabaseFactory.build(context).useDb { db ->
            val names = db.categoryDao().all().map { it.name }
            assertTrue("A deleted Insurance & takaful came back: $names", INSURANCE !in names)
            assertTrue("A deleted Entertainment came back: $names", "Entertainment" !in names)
            assertEquals(14, names.size)
        }
    }

    /**
     * A user who already made a category under one of the two names keeps it,
     * with its own id and icon, rather than the open dying on `category.name`'s
     * unique index.
     */
    @Test fun aCategoryTheUserAlreadyNamedLikeANewOneIsKept() {
        DatabaseFactory.build(context).useDb { db ->
            val dao = db.categoryDao()
            dao.deleteAllForImport()
            dao.insertAll(FOURTEEN + Category(id = 20, name = LOANS, iconKey = "coins", sortOrder = 14))
        }
        DatabaseFactory.build(context).useDb { db ->
            val rows = db.categoryDao().all()
            assertEquals(16, rows.size)
            assertEquals(Category(id = 20, name = LOANS, iconKey = "coins", sortOrder = 14), rows.single { it.name == LOANS })
            assertEquals(Seed.UNCATEGORIZED, rows.maxBy { it.sortOrder }.name)
        }
    }

    private fun PingedDatabase.txnCategoryIds(): List<Long> =
        openHelper.readableDatabase.column("SELECT category_id FROM txn ORDER BY id").map { it!!.toLong() }

    companion object {
        const val LOANS = "Loans & financing"
        const val INSURANCE = "Insurance & takaful"

        /** The seed every install before #134 was made from, written out rather than derived from [Seed]. */
        val FOURTEEN = listOf(
            "utensils" to "Food & Drinks", "shopping-basket" to "Groceries", "car" to "Transport",
            "fuel" to "Petrol & tolls", "zap" to "Bills & utilities", "wifi" to "Telco & internet",
            "shopping-bag" to "Shopping", "heart-pulse" to "Health", "graduation-cap" to "Education",
            "users" to "Family", "hand-heart" to "Religious & zakat", "landmark" to "Government & fees",
            "ticket" to "Entertainment", "circle-dashed" to "Uncategorized",
        ).mapIndexed { i, (icon, name) ->
            Category(id = i + 1L, name = name, iconKey = icon, sortOrder = i, isProtected = name == "Uncategorized")
        }
    }
}
