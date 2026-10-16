package my.pinged.ledger.categories

import android.app.Application
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.Seed
import my.pinged.data.dao.CategoryUse
import my.pinged.data.entity.Category
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.home.EDIT_CATEGORY
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.ledgerTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.5's Categories list (#155): what it draws, and that its writes reach
 * the database and the rest of the app through the holder.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before fun startFromTheSeed() = app.discardTheDatabase()

    @After fun leaveNothingBehind() = app.discardTheDatabase()

    private val food = CategoryUse(Category(1, "Food & Drinks", "utensils", 0, seedName = "Food & Drinks"), 412, 0)
    private val uncategorized =
        CategoryUse(Category(16, Seed.UNCATEGORIZED, "circle-dashed", 15, isProtected = true, seedName = Seed.UNCATEGORIZED), 3, 0)

    private fun drawn(vararg items: CategoryUse) = compose.setContent {
        CategoriesContent(
            state = CategoriesState(loaded = true, items = items.toList()),
            onBack = {}, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onDelete = {},
        )
    }

    // ---- the drawing -------------------------------------------------------

    @Test fun aRowShowsItsNameAndHowManyTransactionsAreFiledThere() {
        drawn(food, uncategorized)
        compose.onNodeWithText("Food & Drinks").assertExists()
        compose.onNodeWithText("412 TRANSACTIONS").assertExists()
    }

    /** Spec 4: Uncategorized opens nothing, and says what it is for instead. */
    @Test fun uncategorizedOpensNoSheet() {
        drawn(food, uncategorized)
        compose.onNodeWithText(PROTECTED_NOTE).assertExists()
        compose.onNodeWithText(Seed.UNCATEGORIZED).performScrollTo().assertHasNoClickAction()
    }

    @Test fun aTapOpensTheEditSheet() {
        drawn(food, uncategorized)
        compose.onNodeWithText("Food & Drinks").performScrollTo().performClick()
        compose.onNodeWithText(EDIT_CATEGORY).assertExists()
    }

    @Test fun theFootOffersANewCategory() {
        drawn(food, uncategorized)
        compose.onNodeWithText(NEW_CATEGORY).performScrollTo().performClick()
        compose.onNodeWithText("What you spend on").assertExists()
    }

    // ---- the holder, against the real database -------------------------------

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
