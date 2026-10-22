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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
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
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
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
                    onChooseCategory = { id -> state = state.copy(draft = state.draft.chosen(id)) },
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
        compose.mainClock.advanceTimeBy(READING_DELAY_MILLIS + 40)
        compose.onNodeWithText(READING).assertExists()
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

    /** #272, `design/Cash.dc.html`: the four most used, then More; the rest are behind it. */
    @Test fun aNewPaymentOffersTheFourMostUsedAndMore() {
        state = ManualEntryState(categories = CHIPS)
        draw()
        CHIPS.take(4).forEach { chip(it.name).assertExists() }
        CHIPS.drop(4).forEach { chip(it.name).assertDoesNotExist() }
        compose.onNodeWithText(MORE).assertExists()
    }

    @Test fun aCategoryPickedThroughMoreIsSelectedInTheRowAndSaved() {
        state = ManualEntryState(categories = CHIPS)
        draw()
        type("500")
        compose.onNodeWithText(MORE).performScrollTo().performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()
        compose.onNode(hasText(HEALTH.name) and inChooser).performScrollTo().performClick()
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction() and inChooser).performClick()

        compose.onNodeWithText(PICKER_HEADING).assertDoesNotExist()
        chip(HEALTH.name).assertIsSelected()
        compose.onNodeWithText(SAVE).performScrollTo().performClick()
        assertEquals("Save did not reach the holder", 1, saves)
        assertEquals("The draft saved is not the category picked", CategoryChoice(HEALTH.id, ChosenBy.TAPPED), state.draft.category)
    }

    /** A pick through More selects, whatever was selected: the chooser's Save is not a toggle. */
    @Test fun pickingTheSelectedCategoryThroughMoreKeepsItSelected() {
        state = ManualEntryState(categories = CHIPS, draft = ManualDraft(category = CategoryChoice(HEALTH.id, ChosenBy.TAPPED)))
        draw()
        compose.onNodeWithText(MORE).performScrollTo().performClick()
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction() and inChooser).performClick()
        chip(HEALTH.name).assertIsSelected()
        assertEquals(HEALTH.id, state.draft.category?.categoryId)
    }

    @Test fun aCorrectionFiledOutsideTheFourShowsItsCategorySelected() {
        val draft = ManualDraft(amountSen = 900L, category = CategoryChoice(HEALTH.id, ChosenBy.PRESELECTED))
        state = ManualEntryState(categories = CHIPS, editing = 7, loaded = draft, draft = draft)
        draw()
        chip(HEALTH.name).assertIsSelected()
    }

    /** #272: a correction opens with no keyboard over Delete; a tap on the amount still brings it. */
    @Test fun aCorrectionOpensWithTheAmountUnfocused() {
        val draft = ManualDraft(amountSen = 900L)
        state = ManualEntryState(categories = CHIPS, editing = 7, loaded = draft, draft = draft)
        draw()
        amountField().assertIsNotFocused()
        amountField().performClick()
        amountField().assertIsFocused()
    }

    private fun chip(name: String) = compose.onNode(hasText(name) and isSelectable() and !inChooser)

    /** The chooser's own nodes, told apart from the row's beneath it. */
    private val inChooser = hasAnyAncestor(hasAnyDescendant(hasText(PICKER_HEADING)))

    private companion object {
        val CHIPS = SEED_CHIPS
        val GROCERIES = CHIPS.first { it.name == "Groceries" }.id
        val HEALTH = CHIPS.first { it.name == "Health" }
    }
}
