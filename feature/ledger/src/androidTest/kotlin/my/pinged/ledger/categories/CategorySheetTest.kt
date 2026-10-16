package my.pinged.ledger.categories

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import my.pinged.data.entity.Category
import my.pinged.ledger.home.CATEGORY_NAME_TAG
import my.pinged.ledger.home.CategorySheet
import my.pinged.ledger.home.DELETE_CATEGORY
import my.pinged.ledger.home.NAME_TAKEN
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ledger.home.REPURPOSE_NOTE
import my.pinged.ledger.home.iconTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Spec 4's category sheet (#155), drawn as `design/Category.dc.html`: the
 * reach before the save, the clash before the tap, and Delete only for a
 * category nothing uses.
 *
 * Save and Delete are pinned below the scrolling icons, as the board keeps
 * Save on screen, so they are tapped without a scroll: there is no scroller
 * above them to scroll.
 */
class CategorySheetTest {
    @get:Rule val compose = createComposeRule()

    private val food = Category(id = 1, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0, seedName = "Food & Drinks")
    private val save get() = compose.onNode(hasText("Save") and hasClickAction())

    private fun sheet(
        existing: Category? = food,
        transactions: Int = 412,
        rules: Int = 0,
        onSave: (String, String) -> Unit = { _, _ -> },
        onDelete: () -> Unit = {},
    ) = compose.setContent {
        CategorySheet(
            existing = existing,
            transactions = transactions,
            rules = rules,
            otherNames = listOf("Groceries", "Uncategorized"),
            onSave = onSave,
            onDelete = onDelete,
            onDismiss = {},
        )
    }

    /** The board's "412 TRANSACTIONS WILL SHOW THE NEW NAME", once there is one. */
    @Test fun aRenameStatesItsReachBeforeTheSave() {
        var saved: Pair<String, String>? = null
        sheet(onSave = { n, i -> saved = n to i })
        compose.onNodeWithText("WILL SHOW THE NEW", substring = true).assertDoesNotExist()
        save.assertIsNotEnabled()

        replaceName("  Makan ")
        compose.onNodeWithText("412 TRANSACTIONS WILL SHOW THE NEW NAME").assertExists()
        // Spec 4: "with a suggestion to create a new category instead".
        compose.onNodeWithText(REPURPOSE_NOTE).assertExists()
        save.assertIsEnabled().performClick()
        assertEquals("Makan" to "utensils", saved)
    }

    /** A new icon is a change with the same reach: the row draws it in every month. */
    @Test fun aNewIconStatesItsReachToo() {
        var saved: Pair<String, String>? = null
        sheet(onSave = { n, i -> saved = n to i })
        compose.onNodeWithTag(iconTag("soup")).performScrollTo().performClick()
        compose.onNodeWithTag(iconTag("soup")).assertIsSelected()
        compose.onNodeWithText("412 TRANSACTIONS WILL SHOW THE NEW ICON").assertExists()
        save.performClick()
        assertEquals("Food & Drinks" to "soup", saved)
    }

    /** Another category's name, in any case, is said and cannot be saved. */
    @Test fun aTakenNameIsSaidAndCannotBeSaved() {
        sheet()
        replaceName("groceries")
        compose.onNodeWithText(NAME_TAKEN).assertExists()
        save.assertIsNotEnabled()
    }

    /** The issue's "USED BY N TRANSACTIONS, SO IT CANNOT BE DELETED", and no Delete. */
    @Test fun aCategoryInUseOffersNoDeleteAndSaysWhy() {
        sheet(transactions = 412, rules = 2)
        compose.onNodeWithText("USED BY 412 TRANSACTIONS AND 2 LEARNED MERCHANTS, SO IT CANNOT BE DELETED").assertExists()
        compose.onNodeWithText(DELETE_CATEGORY).assertDoesNotExist()
    }

    @Test fun anUnusedCategoryOffersDelete() {
        var deleted = 0
        sheet(transactions = 0, rules = 0, onDelete = { deleted++ })
        compose.onNodeWithText(DELETE_CATEGORY).performClick()
        assertEquals(1, deleted)
    }

    /** A new category starts on the generic icon, and saves once it has a name. */
    @Test fun aNewCategorySavesItsNameAndIcon() {
        var saved: Pair<String, String>? = null
        sheet(existing = null, transactions = 0, onSave = { n, i -> saved = n to i })
        compose.onNodeWithText(NEW_CATEGORY).assertExists()
        compose.onNodeWithText(DELETE_CATEGORY).assertDoesNotExist()
        save.assertIsNotEnabled()
        compose.onNodeWithTag(iconTag("tag")).assertIsSelected()
        // The icon before the name: typing raises the soft keyboard on CI's
        // emulator, which leaves the icons' scroller little or no height under
        // the pinned name and Save, and a tile there takes no tap.
        compose.onNodeWithTag(iconTag("dog")).performScrollTo().performClick()
        compose.onNodeWithTag(iconTag("dog")).assertIsSelected()
        replaceName("Kucing")
        save.performClick()
        assertEquals("Kucing" to "dog", saved)
    }

    /** The tag is on the field's wrapper; the editable node is its child. */
    private fun replaceName(text: String) {
        compose.onNode(
            androidx.compose.ui.test.hasSetTextAction() and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(CATEGORY_NAME_TAG)),
        ).performTextReplacement(text)
    }
}
