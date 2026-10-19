package my.pinged.ledger.categories

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Seed
import my.pinged.data.dao.CategoryUse
import my.pinged.data.entity.Category
import my.pinged.ledger.home.EDIT_CATEGORY
import my.pinged.ledger.home.NEW_CATEGORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `design/Categories.dc.html` (#233): most used first, the empty categories
 * folded into one row, Uncategorized listed but closed, under a chevron
 * header that counts them all.
 *
 * The state is handed over in `CategoryDao.uses()`'s order, by `sort_order`,
 * which the board's order disagrees with: a list drawn as it arrives fails.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesListTest {
    @get:Rule val compose = createComposeRule()

    private fun use(id: Long, name: String, sortOrder: Int, transactions: Int) =
        CategoryUse(Category(id, name, "utensils", sortOrder, seedName = name), transactions, 0)

    private val food = use(1, "Food & Drinks", 0, 412)
    private val education = use(2, "Education", 1, 0)
    private val transport = use(3, "Transport", 2, 96)
    private val groceries = use(4, "Groceries", 3, 118)
    private val insurance = use(5, "Insurance & takaful", 4, 0)
    private val uncategorized =
        CategoryUse(Category(16, Seed.UNCATEGORIZED, "circle-dashed", 15, isProtected = true, seedName = Seed.UNCATEGORIZED), 4, 0)

    private var backs = 0

    private fun drawn(vararg items: CategoryUse = arrayOf(food, education, transport, groceries, insurance, uncategorized)) =
        compose.setContent {
            CategoriesContent(
                state = CategoriesState(loaded = true, items = items.toList()),
                onBack = { backs++ }, onAdd = { _, _ -> }, onEdit = { _, _, _ -> }, onDelete = {},
            )
        }

    /** Every text in the unmerged tree, in the order the tree holds it. */
    private fun texts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @Test fun theUsedCategoriesAreListedMostUsedFirstThenUncategorizedThenTheFold() {
        drawn()
        val listed = texts().filter {
            it in setOf("Food & Drinks", "Groceries", "Transport", Seed.UNCATEGORIZED, "2 WITH NOTHING IN THEM YET")
        }
        assertEquals(listOf("Food & Drinks", "Groceries", "Transport", Seed.UNCATEGORIZED, "2 WITH NOTHING IN THEM YET"), listed)
    }

    @Test fun theHeaderCountsEveryCategoryAndSaysTheOrder() {
        drawn()
        compose.onNodeWithText("6 · MOST USED FIRST").assertExists()
    }

    @Test fun theBackChevronGoesBack() {
        drawn()
        compose.onNodeWithContentDescription(CATEGORIES_BACK).performClick()
        assertEquals(1, backs)
        assertTrue(texts().none { it == "Back" })
    }

    /** Folded, the empty ones are named in a line and none is a row of its own. */
    @Test fun theEmptyCategoriesFoldIntoOneRowNamingThem() {
        drawn()
        compose.onNodeWithText("EDUCATION · INSURANCE & TAKAFUL").assertExists()
        compose.onNodeWithText("Education").assertDoesNotExist()
    }

    @Test fun theFoldOpensToRowsThatOpenTheirSheet() {
        drawn()
        compose.onNodeWithText("2 WITH NOTHING IN THEM YET").performScrollTo().performClick()
        compose.onNodeWithText("Education").performScrollTo().performClick()
        compose.onNodeWithText(EDIT_CATEGORY).assertExists()
    }

    @Test fun uncategorizedCountsItsTransactionsAndSaysItCannotBeChanged() {
        drawn()
        compose.onNodeWithText("4 TRANSACTIONS · CANNOT BE RENAMED OR DELETED").assertExists()
        compose.onNodeWithText(Seed.UNCATEGORIZED).performScrollTo().assertHasNoClickAction()
    }

    @Test fun aRowShowsItsNameAndHowManyTransactionsAreFiledThere() {
        drawn()
        compose.onNodeWithText("Food & Drinks").assertExists()
        compose.onNodeWithText("412 TRANSACTIONS").assertExists()
    }

    @Test fun aTapOpensTheEditSheet() {
        drawn()
        compose.onNodeWithText("Groceries").performScrollTo().performClick()
        compose.onNodeWithText(EDIT_CATEGORY).assertExists()
    }

    /**
     * Pinned below the list, as the board draws it: on screen at CI's 320x640
     * under the board's sixteen with no scroll, which a last item of the list
     * is not.
     */
    @Test fun theFootOffersANewCategoryWithoutAScroll() {
        drawn(*(Array(15) { use(100L + it, "Used $it", it, 15 - it) } + uncategorized))
        compose.onNodeWithText(NEW_CATEGORY).assertIsDisplayed().performClick()
        compose.onNodeWithText("What you spend on").assertExists()
    }

    /** Nothing empty, nothing to fold: no row saying "0 WITH NOTHING". */
    @Test fun noFoldWhenEveryCategoryIsUsed() {
        drawn(food, groceries, uncategorized)
        assertTrue(texts().none { "WITH NOTHING IN THEM YET" in it })
    }
}
