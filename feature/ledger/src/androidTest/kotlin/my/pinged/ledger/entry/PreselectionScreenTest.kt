package my.pinged.ledger.entry

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import my.pinged.data.Databases
import my.pinged.ledger.TIMEOUT
import my.pinged.ledger.discardTheDatabase
import my.pinged.ledger.freshLedger
import my.pinged.ui.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.4's "a chip the user tapped is never replaced by a pre-selection"
 * (#102), on the entry screen drawn from a real [ManualEntryViewModel]: the
 * view model asks for a pre-selection as the merchant is typed, and the test
 * answers it from [answers], so a lookup's answer has certainly landed before
 * a chip is checked. That the lookup itself answers rightly is
 * `MerchantPreselectionTest`'s.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PreselectionScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ViewModelStore()

    /** Every merchant the view model asked about, in order. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var answers: Map<String, Long?>
    private lateinit var viewModel: ManualEntryViewModel

    @Before fun draw() {
        context.freshLedger()
        val groceries = categoryId("Groceries")
        answers = mapOf("99 Speedmart" to groceries, "KK Mart" to groceries)
        val app = context.applicationContext as Application
        viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ManualEntryViewModel(app, SavedStateHandle(), target = null) { merchant ->
                        asked += merchant
                        CompletableDeferred(answers[merchant])
                    } as T
            },
        )[ManualEntryViewModel::class.java]
        compose.setContent {
            PingedTheme { ManualEntryScreen(viewModel, onBack = {}, onSaved = {}) }
        }
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Transport").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun leaveNothingBehind() {
        store.clear()
        context.discardTheDatabase()
    }

    @Test fun aChipTappedBeforeTheMerchantIsKept() {
        chip("Transport").performScrollTo().performClick()

        typeMerchant("99 Speedmart")

        chip("Transport").assertIsSelected()
        chip("Groceries").assertIsNotSelected()
        assertEquals(ChosenBy.TAPPED, viewModel.state.value.draft.category?.by)
    }

    @Test fun aChipTappedAfterAPreselectionIsKeptWhenTheMerchantChanges() {
        typeMerchant("99 Speedmart")
        compose.waitUntil(TIMEOUT) { isSelected("Groceries") }

        chip("Transport").performScrollTo().performClick()
        chip("Transport").assertIsSelected()

        clearMerchant()
        typeMerchant("KK Mart")
        chip("Transport").assertIsSelected()
        chip("Groceries").assertIsNotSelected()
    }

    @Test fun clearingTheMerchantRemovesAPreselection() {
        typeMerchant("99 Speedmart")
        compose.waitUntil(TIMEOUT) { isSelected("Groceries") }

        clearMerchant()

        compose.waitUntil(TIMEOUT) { !isSelected("Groceries") }
    }

    @Test fun clearingTheMerchantKeepsATappedChip() {
        chip("Transport").performScrollTo().performClick()
        typeMerchant("99 Speedmart")

        clearMerchant()

        chip("Transport").assertIsSelected()
    }

    /** Types [text] into the empty merchant field and waits until its lookup has been answered. */
    private fun typeMerchant(text: String) {
        merchant().performScrollTo().performTextInput(text)
        answered(text)
    }

    private fun clearMerchant() {
        merchant().performScrollTo().performTextClearance()
        answered("")
    }

    /** The view model asked about [text], and the main thread has applied the answer. */
    private fun answered(text: String) {
        compose.waitUntil(TIMEOUT) { asked.lastOrNull() == text }
        compose.waitForIdle()
    }

    private fun chip(name: String) = compose.onNodeWithText(name)

    private fun isSelected(name: String): Boolean =
        compose.onNodeWithText(name).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    /** The tag is on the wrapper drawing the underline; the editable node is its child. */
    private fun merchant() =
        compose.onNodeWithTag(MERCHANT_FIELD, useUnmergedTree = true).onChildren()
            .filterToOne(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    private fun categoryId(name: String): Long = Databases.categoryDao(context).all().first { it.name == name }.id
}
