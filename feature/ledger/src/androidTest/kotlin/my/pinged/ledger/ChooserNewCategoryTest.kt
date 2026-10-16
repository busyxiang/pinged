package my.pinged.ledger

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import kotlinx.coroutines.awaitCancellation
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.home.CATEGORY_NAME_TAG
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.NAME_TAKEN
import my.pinged.ledger.home.NEW_CATEGORY
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * `design/Chooser.dc.html`'s "New category" tile (#155): it opens the category
 * sheet over the chooser, and the category it makes is picked for this
 * payment, so making one is not a second trip.
 */
class ChooserNewCategoryTest {
    @get:Rule val compose = createComposeRule()

    private val groceries = Category(2, "Groceries", "shopping-basket", 1, seedName = "Groceries")

    /** The sheet's own controls, told apart from the chooser's beneath it. */
    private val inSheet = hasAnyAncestor(hasAnyDescendant(hasTestTag(CATEGORY_NAME_TAG)))

    private fun chooser(onAdd: (suspend (String, String) -> Long?)?, saved: (Long) -> Unit = {}) = compose.setContent {
        val categories = remember { mutableStateListOf(groceries) }
        // No keyboard, for the reason `CategorySheetTest` gives: one landing
        // during the sheet's Save tap lifts Save from under it, and on CI's
        // ledger-2 (run 38917718855) nothing was made or picked.
        InterceptPlatformTextInput({ _, _ -> awaitCancellation() }) {
            CategoryPicker(
                categories = categories.toList(),
                subject = ChooserSubject("TNG*99SPEEDMART", "32.00"),
                teach = null,
                initial = null,
                preview = { RetroPreview(0, emptyList()) },
                onSave = { id, _ -> saved(id) },
                onDismiss = {},
                allNames = categories.map { it.name } + "Uncategorized",
                // The holder's re-read, played by the test: the row arrives
                // before the id is answered, as `RowActions.addCategory`'s `then`
                // runs before it returns.
                onAdd = onAdd?.let { add ->
                    { name, icon -> add(name, icon)?.also { categories += Category(it, name, icon, 99) } }
                },
            )
        }
    }

    @Test fun aCategoryMadeFromTheChooserIsPickedForThisPayment() {
        var saved: Long? = null
        chooser(onAdd = { _, _ -> 99L }, saved = { saved = it })

        compose.onNodeWithText(NEW_CATEGORY).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and inSheet).performTextReplacement("Kucing")
        compose.onNode(hasText("Save") and hasClickAction() and inSheet).performClick()

        compose.onNode(isSelectable() and hasText("Kucing")).assertIsSelected()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals(99L, saved)
    }

    /** Uncategorized is not a tile here, and its name is still taken. */
    @Test fun aNameOnlyUncategorizedHoldsIsStillTaken() {
        chooser(onAdd = { _, _ -> 99L })
        compose.onNodeWithText(NEW_CATEGORY).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and inSheet).performTextReplacement("uncategorized")
        compose.onNodeWithText(NAME_TAKEN).assertExists()
    }

    @Test fun withNoWayToAddThereIsNoTile() {
        chooser(onAdd = null)
        compose.onNodeWithText(NEW_CATEGORY).assertDoesNotExist()
    }
}
