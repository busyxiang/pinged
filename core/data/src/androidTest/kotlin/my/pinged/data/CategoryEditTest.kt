package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.CategoryNameTakenException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 4's category editing (#155): add, rename and re-icon. Delete is
 * [CategoryDeleteTest]'s.
 */
@RunWith(AndroidJUnit4::class)
class CategoryEditTest {
    private lateinit var db: PingedDatabase
    private val dao get() = db.categoryDao()

    @Before fun setUp() {
        db = freshDatabase()
    }

    @After fun tearDown() {
        db.close()
    }

    /**
     * A rename and a new icon land on the one row, in place: same id, same
     * place, same `seed_name`, so the transaction filed there shows the new
     * name and the dictionary still finds the row.
     */
    @Test fun anEditRenamesAndReIconsTheRowInPlace() {
        val food = db.categoryId("Food & Drinks")
        db.txnDao().insert(sampleTxn(categoryId = food))
        val before = dao.all().single { it.id == food }

        dao.edit(food, "  Makan  ", "soup")

        val after = dao.all().single { it.id == food }
        assertEquals(before.copy(name = "Makan", iconKey = "soup"), after)
        assertEquals("Food & Drinks", after.seedName)
        assertEquals(food, dao.seededIds()["Food & Drinks"])
        assertEquals(1, dao.countTransactions(food))
    }

    /** Uncategorized is found by its name (spec 4), so it cannot take another. */
    @Test fun uncategorizedCannotBeRenamedOrReIconed() {
        val uncategorized = dao.requireUncategorizedId()
        assertThrows(IllegalArgumentException::class.java) { dao.edit(uncategorized, "Unknown", "circle-dashed") }
        assertThrows(IllegalArgumentException::class.java) { dao.edit(uncategorized, Seed.UNCATEGORIZED, "tag") }
        assertEquals("circle-dashed", dao.all().single { it.id == uncategorized }.iconKey)
    }

    /**
     * Another category's name, in any case, is refused as itself, not as
     * SQLite's 2067; changing the case of a category's own name is not a clash.
     */
    @Test fun aNameAnotherCategoryHoldsIsRefusedIgnoringCase() {
        val food = db.categoryId("Food & Drinks")
        val thrown = assertThrows(CategoryNameTakenException::class.java) { dao.edit(food, "groceries", "utensils") }
        assertEquals("groceries", thrown.name)
        assertThrows(CategoryNameTakenException::class.java) { dao.add("UNCATEGORIZED", "tag") }

        dao.edit(food, "food & drinks", "utensils")
        assertEquals("food & drinks", dao.all().single { it.id == food }.name)
    }

    @Test fun aBlankOrOverlongNameIsRefused() {
        val food = db.categoryId("Food & Drinks")
        assertThrows(IllegalArgumentException::class.java) { dao.edit(food, "   ", "utensils") }
        assertThrows(IllegalArgumentException::class.java) { dao.add("", "tag") }
        assertThrows(IllegalArgumentException::class.java) {
            dao.add("x".repeat(my.pinged.data.dao.CategoryDao.MAX_NAME_LENGTH + 1), "tag")
        }
    }

    /**
     * A new category goes after every other, carries no `seed_name` -- so no
     * dictionary entry files under it -- and is not protected, so it can be
     * edited and deleted like any.
     */
    @Test fun anAddedCategoryIsTheUsersOwn() {
        val highest = dao.all().maxOf { it.sortOrder }
        val id = dao.add("Kucing", "dog")

        val row = dao.all().single { it.id == id }
        assertEquals("Kucing", row.name)
        assertEquals("dog", row.iconKey)
        assertEquals(highest + 1, row.sortOrder)
        assertNull(row.seedName)
        assertEquals(false, row.isProtected)
        assertEquals(Seed.NAMES.size, dao.seededIds().size)

        dao.edit(id, "Pets", "dog")
        assertEquals("Pets", dao.all().single { it.id == id }.name)
        dao.deleteIfUnused(id)
        assertTrue(dao.all().none { it.id == id })
    }

    /** The list's counts: what points at each category, in both tables. */
    @Test fun usesCountTransactionsAndRulesAndPutUncategorizedLast() {
        val food = db.categoryId("Food & Drinks")
        db.txnDao().insert(sampleTxn(categoryId = food))
        db.txnDao().insert(sampleTxn(occurredAt = 2_000L, categoryId = food))
        db.insertMerchantRule("MCD", categoryId = food)

        val uses = dao.uses()
        val row = uses.single { it.category.id == food }
        assertEquals(2, row.transactions)
        assertEquals(1, row.rules)
        assertEquals(Seed.UNCATEGORIZED, uses.last().category.name)
        assertEquals(Seed.NAMES.size, uses.size)
    }
}
