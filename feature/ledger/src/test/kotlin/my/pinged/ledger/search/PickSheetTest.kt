package my.pinged.ledger.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import my.pinged.ui.theme.WAITING_FOR_AN_EXPORT
import org.junit.Assert.assertEquals
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

    /**
     * Options not read yet (#284): Any, which needs no read, at once; then,
     * after `ReadingLayout`'s delay, the status line and the rows' dotted
     * places, `design/LoadingList.dc.html`'s idiom in the sheet.
     */
    @Test fun optionsNotReadYetDrawTheStatusLineAndPlacesAfterTheDelay() {
        notReadYet(heldBy = null)

        at(READING_DELAY_MILLIS - 40)
        compose.onNodeWithTag(PICK + ANY_CATEGORY).assertExists()
        compose.onNodeWithText(READING).assertDoesNotExist()
        assertEquals("A row's place was drawn before 200ms", 0, places())

        at(READING_DELAY_MILLIS + 40)
        compose.onNodeWithText(READING).assertExists()
        assertEquals("The rows not read yet have no places", PICK_PLACES, places())
    }

    @Test fun optionsWaitingForAnExportSayWhy() {
        notReadYet(heldBy = HeldBy.Export)
        at(READING_DELAY_MILLIS + 40)
        compose.onNodeWithText(WAITING_FOR_AN_EXPORT).assertExists()
        compose.onNodeWithText(READING).assertDoesNotExist()
    }

    /** No options at all is the read's answer, drawn at once rather than held as a read in flight. */
    @Test fun noOptionsIsNotDrawnAsNotReadYet() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme { PickSheet("Category", ANY_CATEGORY, emptyList<Pair<Long, String>>(), null, {}, {}) }
        }
        at(READING_DELAY_MILLIS + 40)
        compose.onNodeWithText(READING).assertDoesNotExist()
        assertEquals(0, places())
    }

    private fun notReadYet(heldBy: HeldBy?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PingedTheme {
                CompositionLocalProvider(LocalHeldBy provides heldBy) {
                    PickSheet<Long>("Category", ANY_CATEGORY, options = null, selected = null, onPick = {}, onDismiss = {})
                }
            }
        }
    }

    private fun places() = compose.onAllNodesWithTag(PICK_NOT_READ_YET, useUnmergedTree = true).fetchSemanticsNodes().size

    private val clock = HeldClock(compose)

    private fun at(millis: Long) {
        clock.at(millis)
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
        const val PICK_PLACES = 3
    }
}
