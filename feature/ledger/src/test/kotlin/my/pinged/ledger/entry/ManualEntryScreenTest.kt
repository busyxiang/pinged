package my.pinged.ledger.entry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Seed
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen's amount and Save (#99), drawn from [ManualEntryScreenContent]
 * with the state held here the way `ManualEntryViewModel` holds it: the amount
 * the field reports is the amount drawn. A keyboard's decimal separator is
 * `KeypadTest`'s: the field puts its own text back after every key, so no
 * sequence of keys here can tell a separator ignored from one misread.
 */
@RunWith(AndroidJUnit4::class)
class ManualEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(ManualEntryState())
    private var saves = 0

    private fun draw() {
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = { sen -> state = state.copy(draft = state.draft.copy(amountSen = sen)) },
                    onCategory = { id -> state = state.copy(draft = state.draft.tapped(id)) },
                    onMerchant = { text -> state = state.copy(draft = state.draft.copy(merchant = text)) },
                    onNote = { text -> state = state.copy(draft = state.draft.copy(note = text)) },
                    onSave = { saves++ },
                    onBack = {},
                    onSaved = {},
                )
            }
        }
    }

    private fun type(keys: String) = keys.forEach { amountField().performTextInput(it.toString()) }

    private fun amountField() = compose.onNodeWithTag(AMOUNT_FIELD)

    /**
     * #237: a correction whose payment is not read yet has only the empty
     * default draft, and drawing it is drawing a payment of nothing, today,
     * filed nowhere. It says it is reading, and offers no field to type in.
     */
    @Test fun aCorrectionNotReadYetDrawsNoEmptyPayment() {
        state = ManualEntryState(editing = 7, reading = true)
        draw()
        amountField().assertDoesNotExist()
        compose.onNodeWithText("READING").assertExists()
        compose.onNodeWithText(SAVE_CHANGES).assertIsNotEnabled()
    }

    @Test fun theAmountIsFocusedOnArrival() {
        draw()
        amountField().assertIsFocused()
    }

    @Test fun digitsFillInFromTheRightAsSen() {
        draw()
        type("1250")
        amountField().assertTextEquals("RM12.50")
        assertEquals("The screen's amount is not 1,250 sen", 1_250L, state.draft.amountSen)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun backspaceRemovesTheLastDigit() {
        draw()
        type("1250")
        amountField().performKeyInput { pressKey(Key.Backspace) }
        amountField().assertTextEquals("RM1.25")
    }

    @Test fun saveIsDisabledAtZeroAndEnabledAboveIt() {
        draw()
        amountField().assertTextEquals("RM0.00")
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled()
        type("5")
        compose.onNodeWithText(SAVE).performScrollTo().assertIsEnabled().performClick()
        assertEquals("Save did not reach the holder", 1, saves)
    }

    @Test fun aDigitPastOneMillionRinggitIsIgnored() {
        draw()
        type("100000000")
        amountField().assertTextEquals("RM1,000,000.00")
        type("5")
        amountField().assertTextEquals("RM1,000,000.00")
    }

    @Test fun theMerchantAndTheNoteAreOptional() {
        draw()
        type("800")
        compose.onNodeWithTag(MERCHANT_FIELD).performScrollTo()
        compose.onNodeWithTag(NOTE_FIELD).performScrollTo()
        compose.onNodeWithText(SAVE).performScrollTo().assertIsEnabled().performClick()
        assertEquals("Save with both fields blank did not reach the holder", 1, saves)
        assertEquals("", state.draft.merchant)
        assertEquals("", state.draft.note)
    }

    @Test fun theMerchantAndTheNoteReachTheDraftAsTyped() {
        draw()
        editable(MERCHANT_FIELD).performScrollTo().performTextInput("Kopitiam")
        editable(NOTE_FIELD).performScrollTo().performTextInput("teh tarik ")
        assertEquals("Kopitiam", state.draft.merchant)
        assertEquals("teh tarik ", state.draft.note)
    }

    /** The tag is on the wrapper drawing the underline; the editable node is its child. */
    private fun editable(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren()
            .filterToOne(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    @Test fun saveIsDisabledWhileTheLedgerIsBeingReplaced() {
        state = ManualEntryState(draft = ManualDraft(amountSen = 1_250L), busy = my.pinged.ledger.settings.Operation.RESTORE)
        draw()
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled()
        amountField().assertTextEquals("RM12.50")
    }

    @Test fun aTappedChipIsSelectedAndATapOnItDeselectsIt() {
        state = ManualEntryState(categories = CHIPS)
        draw()
        val groceries = compose.onNodeWithText("Groceries")
        groceries.performScrollTo().assertIsNotSelected().performClick()
        groceries.assertIsSelected()
        assertEquals("The draft does not hold the tapped chip", CategoryChoice(GROCERIES, ChosenBy.TAPPED), state.draft.category)

        groceries.performScrollTo().performClick()
        groceries.assertIsNotSelected()
        assertNull("The draft still holds a chip tapped off", state.draft.category)
    }

    @Test fun aTapOnAnotherChipMovesTheSelection() {
        state = ManualEntryState(categories = CHIPS)
        draw()
        compose.onNodeWithText("Groceries").performScrollTo().performClick()
        compose.onNodeWithText("Transport").performScrollTo().performClick()
        compose.onNodeWithText("Groceries").assertIsNotSelected()
        compose.onNodeWithText("Transport").assertIsSelected()
    }

    @Test fun aFarChipIsReachedByScrollingTheRow() {
        state = ManualEntryState(categories = CHIPS)
        draw()
        val last = compose.onNodeWithText(CHIPS.last().name)
        last.assertIsNotDisplayed()
        last.performScrollTo().assertIsDisplayed().performClick()
        last.assertIsSelected()
        assertEquals("The far chip's tap did not reach the draft", CHIPS.last().id, state.draft.category?.categoryId)
    }

    private companion object {
        /** The seed's categories as `rankedByUse` hands them on a fresh install, with ids. */
        val CHIPS = Seed.categories()
            .mapIndexed { i, c -> c.copy(id = i + 1L) }
            .filter { it.name != Seed.UNCATEGORIZED }
        val GROCERIES = CHIPS.first { it.name == "Groceries" }.id
    }
}
