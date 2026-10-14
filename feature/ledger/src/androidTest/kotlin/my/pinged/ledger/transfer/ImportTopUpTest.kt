package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import my.pinged.data.PingedDatabase
import my.pinged.data.Seed
import my.pinged.data.entity.Category
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.Txn
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A backup written before Loans & financing and Insurance & takaful were
 * seeded (#134), restored by a build that seeds them.
 *
 * The restore replaces the `category` table with the file's, so whatever
 * `DatabaseFactory.build` topped up is gone by the time the import ends: the
 * import has to top up again, or the restored ledger never has the two.
 */
@RunWith(AndroidJUnit4::class)
class ImportTopUpTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    private fun fresh(): PingedDatabase {
        db?.close()
        db = null
        return freshDatabase(context).also { db = it }
    }

    /** A backup of a ledger whose categories are [categories], with one payment filed under Education. */
    private fun backupOf(categories: List<Category>): ByteArray {
        val original = fresh()
        original.categoryDao().deleteAllForImport()
        original.categoryDao().insertAll(categories)
        original.txnDao().insert(payment(categoryId = categories.single { it.name == "Education" }.id))
        return exportBytes(original)
    }

    @Test fun aFourteenCategoryBackupRestoresWithSixteen() {
        val bytes = backupOf(FOURTEEN)

        val restored = fresh()
        ImportJson.read(restored, ByteArrayInputStream(bytes))

        val rows = restored.categoryDao().all()
        assertEquals(16, rows.size)
        assertEquals(
            "A category from the backup lost its id",
            FOURTEEN.map { it.id to it.name },
            rows.filter { it.id <= 14 }.sortedBy { it.id }.map { it.id to it.name },
        )
        assertEquals(listOf(LOANS, INSURANCE, Seed.UNCATEGORIZED), rows.takeLast(3).map { it.name })
        assertEquals(listOf(13, 14, 15), rows.takeLast(3).map { it.sortOrder })
        assertEquals(9L, restored.txnDao().pageFrom(0L, 10).single().categoryId)
    }

    /**
     * A backup made after the top-up, by a user who then deleted one of the
     * new categories, restores without it.
     */
    @Test fun aBackupThatDeletedANewCategoryRestoresWithoutIt() {
        val sixteen = fresh().categoryDao().all()
        val bytes = backupOf(sixteen.filter { it.name != INSURANCE })

        val restored = fresh()
        ImportJson.read(restored, ByteArrayInputStream(bytes))

        val names = restored.categoryDao().all().map { it.name }
        assertTrue("A deleted Insurance & takaful came back from the backup: $names", INSURANCE !in names)
        assertEquals(15, names.size)
    }

    private fun payment(categoryId: Long) = Txn(
        rawCaptureId = null,
        amountSen = 30_000L,
        direction = Direction.EXPENSE,
        occurredAt = 1_000L,
        localDate = my.pinged.data.LocalDates.of(1_000L),
        merchantRaw = "PTPTN APPS",
        merchantDisplay = "PTPTN APPS",
        merchantKey = "PTPTN APPS",
        categoryId = categoryId,
        sourcePackage = null,
        sourceLabel = null,
        confidence = Confidence.HIGH,
        state = TxnState.COMMITTED,
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    private companion object {
        const val LOANS = "Loans & financing"
        const val INSURANCE = "Insurance & takaful"

        /**
         * The seed every install before #134 was made from, written out rather
         * than derived from [Seed]. `SeedTopUpTest` in `:core:data` holds the
         * same list; an `androidTest` source set is not shared across modules.
         */
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
