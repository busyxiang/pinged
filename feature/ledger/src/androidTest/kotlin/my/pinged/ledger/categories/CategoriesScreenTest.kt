package my.pinged.ledger.categories

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.5's Categories list (#155): that its writes reach the database and
 * the rest of the app through the holder. What it draws is
 * `CategoriesListTest`'s, on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesScreenTest {
    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before fun startFromTheSeed() = app.discardTheDatabase()

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    /**
     * A rename made here is what the chooser draws on its next read, on a
     * holder that read the table before it, since Settings writes the table
     * the chooser reads.
     */
    @Test fun aRenameReachesTheChooserOfAHolderThatAlreadyReadTheTable() = runBlocking {
        val rows = RowActions({ app }, CoroutineScope(Dispatchers.IO), {})
        val shopping = Databases.categoryDao(app).all().first { it.name == "Shopping" }.id
        assertTrue(rows.readChooser().categories.any { it.name == "Shopping" })

        val model = CategoriesViewModel(app)
        model.edit(shopping, "Beli-belah", "shopping-cart").join()

        val after = rows.readChooser().categories.single { it.id == shopping }
        assertEquals("Beli-belah" to "shopping-cart", after.name to after.iconKey)
        assertEquals("Beli-belah", model.state.value.items.single { it.category.id == shopping }.category.name)
    }

    @Test fun anAddedCategoryIsListedAndAnUnusedOneDeletes() = runBlocking {
        val model = CategoriesViewModel(app)
        model.add("Kucing", "dog").join()
        val added = model.state.value.items.single { it.category.name == "Kucing" }
        assertEquals(0, added.transactions)

        model.delete(added.category.id).join()
        assertTrue(model.state.value.items.none { it.category.name == "Kucing" })
    }

    /**
     * A delete that something now uses is refused by the DAO and drawn as the
     * list it left, rather than escaping a `viewModelScope` with no handler.
     */
    @Test fun aDeleteOfACategoryInUseLeavesItListed() = runBlocking {
        val dao = Databases.categoryDao(app)
        val health = dao.all().first { it.name == "Health" }.id
        Databases.txnDao(app).insert(ledgerTxn(amountSen = 1_000L, categoryId = health).copy(id = 0))

        val model = CategoriesViewModel(app)
        model.delete(health).join()

        assertEquals(1, model.state.value.items.single { it.category.id == health }.transactions)
    }
}
