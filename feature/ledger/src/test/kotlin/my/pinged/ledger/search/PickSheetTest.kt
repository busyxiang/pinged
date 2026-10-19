package my.pinged.ledger.search

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's category and app sheets (#274): the chosen row carries the
 * board's check, in place of the word CHOSEN, and says it is selected.
 */
@RunWith(AndroidJUnit4::class)
class PickSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theChosenRowIsSelectedAndChecked() {
        sheet(selected = FOOD_ID)
        compose.onNodeWithText("CHOSEN", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag(PICK + FOOD).assertIsSelected()
        compose.onNodeWithTag(PICK + RENT).assertIsNotSelected()
        compose.onNodeWithTag(PICK + ANY_CATEGORY).assertIsNotSelected()
        checkOn(FOOD).assertExists()
        checkOn(RENT).assertDoesNotExist()
        checkOn(ANY_CATEGORY).assertDoesNotExist()
    }

    @Test fun nothingSetChecksAny() {
        sheet(selected = null)
        compose.onNodeWithTag(PICK + ANY_CATEGORY).assertIsSelected()
        compose.onNodeWithTag(PICK + FOOD).assertIsNotSelected()
        checkOn(ANY_CATEGORY).assertExists()
        checkOn(FOOD).assertDoesNotExist()
    }

    private fun checkOn(row: String) =
        compose.onNode(hasTestTag(CHOSEN_MARK) and hasAnyAncestor(hasTestTag(PICK + row)), useUnmergedTree = true)

    private fun sheet(selected: Long?) = compose.setContent {
        PingedTheme {
            PickSheet(
                title = "Category",
                any = ANY_CATEGORY,
                options = listOf(FOOD_ID to FOOD, RENT_ID to RENT),
                selected = selected,
                onPick = {},
                onDismiss = {},
            )
        }
    }

    private companion object {
        const val FOOD_ID = 1L
        const val RENT_ID = 2L
        const val FOOD = "Food & Drinks"
        const val RENT = "Rent"
    }
}
