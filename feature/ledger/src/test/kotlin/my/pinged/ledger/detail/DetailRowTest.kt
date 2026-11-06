package my.pinged.ledger.detail

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.home.EDIT_MERCHANT
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.NAME_HEADING
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.ledgerTxn
import my.pinged.read.LedgerAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/**
 * Detail's one row (#377): Change category opens the chooser over the screen
 * on that row, with no New category tile (#307), and the merchant's pencil
 * opens its sheet. What the chooser and sheet do once open is `RowHostTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class DetailRowTest {
    @get:Rule val compose = createComposeRule()

    private val categories = FakeCategoryActions()

    private fun draw() {
        val groceries = Category(id = 2L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 0)
        val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 1)
        val txn = ledgerTxn(amountSen = 5_000L, categoryId = groceries.id, id = 9L)
        val row = DetailRow(
            row = FeedRow(txn, identityKey = txn.merchantKey, displayName = MERCHANT),
            categoryName = groceries.name,
            captureTitle = "DuitNow Payment",
            captureText = "You have paid RM50.00 to TAN WEI MING",
            captureBigText = null,
            matchedRuleId = "DUITNOW-PAID-V1",
            packVersion = 7,
        )
        compose.setContent {
            DetailScreenContent(
                DetailRead(row, ChooserRead(categories = listOf(groceries, food))),
                ledger = LedgerAccess.Readable,
                onBack = {},
                categories = categories,
                merchants = FakeMerchantActions(publishesSheet = true),
                zone = ZoneOffset.UTC,
            )
        }
    }

    @Test fun changeCategoryOpensTheChooserOnTheRowWithNoNewCategoryTile() {
        draw()

        compose.onNodeWithText(CHANGE_CATEGORY).performScrollTo().performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()
        assertEquals(
            "Detail offered a New category tile",
            0,
            compose.onAllNodes(hasText(NEW_CATEGORY)).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Food & Drinks").performClick()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        assertEquals("The chooser saved another row", listOf(9L to 1L), categories.assigned.map { (txn, category) -> txn to category })
    }

    @Test fun thePencilOpensTheMerchantSheetOverTheScreen() {
        draw()

        compose.onNodeWithContentDescription(EDIT_MERCHANT).performScrollTo().performClick()

        compose.onNodeWithText(NAME_HEADING).assertExists()
    }
}
