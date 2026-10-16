package my.pinged.ledger.entry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import my.pinged.data.entity.Category
import my.pinged.ledger.settings.Operation
import my.pinged.ui.dayMonth
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry screen while nothing can be saved (#108): during a restore, a
 * delete-everything or §11.1's unavailable ledger it shows one line saying
 * why, Save is off, and every field keeps what was typed; when the gate
 * lifts, Save is on again with nothing retyped.
 */
@RunWith(AndroidJUnit4::class)
class BusyEntryScreenTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(ManualEntryState())
    private var saves = 0

    @Test fun aRestoreNamesItselfDisablesSaveAndKeepsTheDraft() {
        draw(busy = Operation.RESTORE)

        line("RESTORING A BACKUP · NOTHING CAN BE SAVED UNTIL IT FINISHES")
        saveIsOffAndTheDraftIsKept()
    }

    @Test fun aDeleteNamesItselfDisablesSaveAndKeepsTheDraft() {
        draw(busy = Operation.DELETE)

        line("DELETING EVERYTHING · NOTHING CAN BE SAVED UNTIL IT FINISHES")
        saveIsOffAndTheDraftIsKept()
    }

    @Test fun aLedgerThatWillNotOpenSaysSoDisablesSaveAndKeepsTheDraft() {
        draw(storageUnavailable = true)

        line(CANNOT_READ_YOUR_DATA)
        saveIsOffAndTheDraftIsKept()
    }

    @Test fun whenTheGateLiftsSaveIsOnWithNothingRetyped() {
        draw(busy = Operation.RESTORE)
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled()

        state = state.copy(busy = null)

        compose.onNodeWithText(SAVE).performScrollTo().assertIsEnabled().performClick()
        assertEquals("Save, enabled again, did not save", 1, saves)
        assertEquals("The line stayed after the restore ended", 0, count("RESTORING A BACKUP", substring = true))
        theDraftIsKept()
    }

    private fun draw(busy: Operation? = null, storageUnavailable: Boolean = false) {
        state = ManualEntryState(
            draft = DRAFT,
            categories = CHIPS,
            busy = busy,
            storageUnavailable = storageUnavailable,
            today = TODAY_,
        )
        compose.setContent {
            PingedTheme {
                ManualEntryScreenContent(
                    state = state,
                    onAmount = {},
                    onMerchant = {},
                    onNote = {},
                    onSave = { saves++ },
                    onBack = {},
                    onSaved = {},
                )
            }
        }
    }

    /** [text] is drawn, once: the one line saying why Save is off. */
    private fun line(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        assertEquals("Not exactly one line saying why Save is off", 1, count(" · NOTHING CAN BE ", substring = true) + count(CANNOT_READ_YOUR_DATA))
    }

    private fun saveIsOffAndTheDraftIsKept() {
        compose.onNodeWithText(SAVE).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals("A tap on the disabled Save reached the holder", 0, saves)
        theDraftIsKept()
    }

    private fun theDraftIsKept() {
        compose.onNodeWithTag(AMOUNT_FIELD).performScrollTo().assertTextEquals("RM12.50")
        compose.onNodeWithTag(DATE_CHIP).performScrollTo().assertTextContains(dayMonth(DAY_))
        compose.onNodeWithText("Groceries").performScrollTo().assertIsSelected()
        editable(MERCHANT_FIELD).performScrollTo().assertTextEquals("Kopitiam")
        editable(NOTE_FIELD).performScrollTo().assertTextEquals("teh tarik")
    }

    private fun count(text: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

    /** The tag is on the wrapper drawing the underline; the editable node is its child. */
    private fun editable(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).onChildren()
            .filterToOne(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    private companion object {
        val TODAY_: LocalDate = LocalDate.of(2026, 8, 15)
        val DAY_: LocalDate = TODAY_.minusDays(3)
        const val GROCERIES = 2L
        val CHIPS = listOf(
            Category(id = 1L, name = "Food & Drinks", iconKey = "food", sortOrder = 0, isProtected = false),
            Category(id = GROCERIES, name = "Groceries", iconKey = "groceries", sortOrder = 1, isProtected = false),
        )
        val DRAFT = ManualDraft(
            amountSen = 1_250L,
            day = DAY_,
            merchant = "Kopitiam",
            note = "teh tarik",
            category = CategoryChoice(GROCERIES, ChosenBy.TAPPED),
        )
    }
}
