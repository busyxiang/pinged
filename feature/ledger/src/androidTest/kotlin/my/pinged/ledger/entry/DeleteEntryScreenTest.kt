package my.pinged.ledger.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The edit screen's Delete (#106, spec 9.4), drawn from
 * [ManualEntryScreenContent]: it asks first, naming the payment's amount, and
 * Cancel keeps the entry and the screen. What the holder does on a confirmed
 * delete is `ManualDeleteTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class DeleteEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    private var deletes = 0
    private var closes = 0

    private fun draw(state: ManualEntryState) {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = {},
                    onMerchant = {},
                    onNote = {},
                    onSave = {},
                    onBack = {},
                    onSaved = { closes++ },
                    onDelete = { deletes++ },
                )
            }
        }
    }

    @Test fun deleteAsksFirstAndCancelKeepsTheEntryAndTheScreen() {
        draw(EDITING)

        compose.onNodeWithText(DELETE_PAYMENT).performScrollTo().performClick()
        compose.onNodeWithText(QUESTION).assertIsDisplayed()
        assertEquals("Delete deleted without asking", 0, deletes)

        compose.onNodeWithText(DELETE_CANCEL).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(QUESTION).assertDoesNotExist()
        assertEquals("Cancel deleted the entry", 0, deletes)
        assertEquals("Cancel closed the screen", 0, closes)
        compose.onNodeWithTag(AMOUNT_FIELD).assertTextEquals("RM12.50")
        compose.onNodeWithText(SAVE_CHANGES).performScrollTo().assertIsDisplayed()
    }

    @Test fun confirmingDeletesOnce() {
        draw(EDITING)

        compose.onNodeWithText(DELETE_PAYMENT).performScrollTo().performClick()
        compose.onNodeWithText(CONFIRM_DELETE).performScrollTo().performClick()

        assertEquals("The confirmation did not delete the entry", 1, deletes)
    }

    @Test fun theQuestionNamesTheStoredAmountNotATypedOne() {
        draw(EDITING.copy(draft = EDITING.draft.copy(amountSen = 9_900L)))

        compose.onNodeWithText(DELETE_PAYMENT).performScrollTo().performClick()

        compose.onNodeWithText(QUESTION).assertIsDisplayed()
    }

    @Test fun aNewEntryHasNoDelete() {
        draw(ManualEntryState(draft = ManualDraft(amountSen = 1_250L)))
        compose.onNodeWithText(DELETE_PAYMENT).assertDoesNotExist()
    }

    private companion object {
        val STORED = ManualDraft(amountSen = 1_250L)
        val EDITING = ManualEntryState(draft = STORED, editing = 7L, loaded = STORED)
        const val QUESTION = "Delete this RM12.50 payment? This can't be undone."
    }
}
