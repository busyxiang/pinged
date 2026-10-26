package my.pinged.ledger

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import my.pinged.capture.Corrections
import my.pinged.data.dao.CategoryUse
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.Category
import my.pinged.ledger.categories.CategoriesContent
import my.pinged.ledger.categories.CategoriesState
import my.pinged.ledger.categories.emptyFoldLabel
import my.pinged.ledger.corrections.ACCEPT
import my.pinged.ledger.corrections.CorrectionsContent
import my.pinged.ledger.corrections.CorrectionsState
import my.pinged.ledger.home.DELETE_CATEGORY
import my.pinged.ledger.home.NEW_CATEGORY
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.learned.DELETE
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.LearnedState
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.sources.SourceRow
import my.pinged.ledger.sources.SourcesScreenContent
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.teach.SAVE
import my.pinged.ledger.teach.TeachContent
import my.pinged.ledger.teach.TeachSelection
import my.pinged.ledger.teach.TeachState
import my.pinged.ledger.unread.NEVER_A_TRANSACTION
import my.pinged.ledger.unread.TEACH_PINGED_THIS
import my.pinged.ledger.unread.UnreadContent
import my.pinged.ledger.unread.UnreadState
import my.pinged.parse.TaughtTemplate
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings sub-screens and Teach during a ledger replacement (#331): each
 * says so at its top and its writes are off. What the holders refuse beneath
 * them is `ReplacingRefusesWritesTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class ReplacingSettingsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val tapped = mutableListOf<String>()

    @Test fun categoriesOffersNoNewCategoryAndItsSheetSavesNothing() {
        val groceries = Category(2, "Groceries", "shopping-basket", 1)
        compose.setContent {
            // No keyboard, for the reason `ChooserNewCategoryTest` gives.
            InterceptPlatformTextInput({ _, _ -> awaitCancellation() }) {
                CategoriesContent(
                    state = CategoriesState(loaded = true, items = listOf(CategoryUse(groceries, transactions = 3, rules = 0))),
                    busy = Operation.RESTORE,
                    onBack = {},
                    onAdd = { _, _ -> tapped += "add" },
                    onEdit = { _, _, _ -> tapped += "edit" },
                    onDelete = { tapped += "delete" },
                )
            }
        }

        line()
        compose.onNode(hasText(NEW_CATEGORY) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Groceries").performClick()
        compose.onNode(hasSetTextAction() and hasText("Groceries")).performTextReplacement("Food")
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction()).assertIsNotEnabled().performClick()
        nothingTapped()
    }

    @Test fun anUnusedCategorysDeleteIsOff() {
        val toys = Category(3, "Toys", "tag", 2)
        compose.setContent {
            CategoriesContent(
                state = CategoriesState(loaded = true, items = listOf(CategoryUse(toys, transactions = 0, rules = 0))),
                busy = Operation.DELETE,
                onBack = {},
                onAdd = { _, _ -> },
                onEdit = { _, _, _ -> },
                onDelete = { tapped += "delete" },
            )
        }

        compose.onNodeWithText(emptyFoldLabel(1)).performClick()
        compose.onNodeWithText("Toys").performClick()
        compose.onNode(hasText(DELETE_CATEGORY) and hasClickAction()).assertIsNotEnabled().performClick()
        nothingTapped()
    }

    @Test fun learnedDeletesNothing() {
        compose.setContent {
            LearnedContent(
                LearnedState(loaded = true, items = listOf(LearnedMerchant("MR DIY", "Mr DIY", 1L, "Shopping", 0))),
                onBack = {},
                onDelete = { tapped += "delete" },
                busy = Operation.RESTORE,
            )
        }

        line()
        compose.onNodeWithContentDescription(DELETE).assertIsNotEnabled().performClick()
        nothingTapped()
    }

    @Test fun sourcesSwitchesNothing() {
        compose.setContent {
            SourcesScreenContent(
                state = SourcesState(loaded = true, suggested = listOf(SourceRow("my.bank", "My Bank", seenCount = 3, enabled = false))),
                onToggle = { pkg, _ -> tapped += pkg },
                onBack = {},
                busy = Operation.RESTORE,
            )
        }

        line()
        compose.onNode(isToggleable() and hasText("My Bank", substring = true)).assertIsNotEnabled().performClick()
        nothingTapped()
    }

    @Test fun correctionsAnswersNothing() {
        val txn = ledgerTxn(amountSen = 1_000L)
        compose.setContent {
            CorrectionsContent(
                CorrectionsState(loaded = true, items = listOf(Corrections.Correction(1L, 1, 2, null, "rule", txn, txn, emptySet()))),
                onBack = {},
                onAccept = { tapped += "accept" },
                onDecline = { tapped += "decline" },
                busy = Operation.RESTORE,
            )
        }

        line()
        compose.onNode(hasText(ACCEPT) and hasClickAction()).performScrollTo().assertIsNotEnabled().performClick()
        nothingTapped()
    }

    @Test fun unreadMarksNothingNeverATransactionAndOpensNoTeach() {
        compose.setContent {
            UnreadContent(
                UnreadState(loaded = true, items = listOf(UnreadCapture(1L, "p", 0L, "t", "x", null, null))),
                onBack = {},
                onNeverATransaction = { tapped += "never" },
                onCopy = {},
                onEnd = {},
                onTeach = { tapped += "teach" },
                busy = Operation.RESTORE,
            )
        }

        line()
        compose.onNode(hasText(NEVER_A_TRANSACTION) and hasClickAction()).performScrollTo().assertIsNotEnabled().performClick()
        compose.onNode(hasText(TEACH_PINGED_THIS) and hasClickAction()).performScrollTo().assertIsNotEnabled().performClick()
        nothingTapped()
    }

    @Test fun teachSavesNothingButStillTakesItsTaps() {
        val capture = UnreadCapture(1L, "p", 0L, "Touch 'n Go eWallet", "Payment of RM1.00 to merchant number 1 successful", null, null)
        val words = TaughtTemplate.words(capture.title, capture.text, capture.bigText)
        val ready = TeachSelection(words).tap(words.words.indexOf("RM1.00")).noMerchant()
        check(ready.step == TeachSelection.Step.READY) { "The selection is not ready to save: $ready" }
        compose.setContent {
            TeachContent(
                TeachState(loaded = true, capture = capture, selection = ready),
                onBack = {},
                onTap = { tapped += "tap" },
                onNoMerchant = {},
                onDirection = {},
                onSave = { tapped += "save" },
                busy = Operation.RESTORE,
            )
        }

        line()
        compose.onNode(hasText(SAVE) and hasClickAction()).assertIsNotEnabled().performClick()
        assertEquals("Save was asked for during a restore", emptyList<String>(), tapped)
        compose.onNodeWithText("merchant").performClick()
        assertEquals("A tap on the message, which writes nothing, was refused", listOf("tap"), tapped)
    }

    /** A read the gate turned away says REPLACING YOUR DATA in place of the list (#328), and nothing beside it. */
    @Test fun aScreenWhoseReadWasReplacedDrawsOneLine() {
        var screen by mutableStateOf(0)
        compose.setContent {
            when (screen) {
                0 -> CategoriesContent(CategoriesState(loaded = true, replacing = true), {}, { _, _ -> }, { _, _, _ -> }, {}, busy = Operation.RESTORE)
                1 -> LearnedContent(LearnedState(loaded = true, replacing = true), {}, {}, busy = Operation.RESTORE)
                2 -> SourcesScreenContent(SourcesState(loaded = true, replacing = true), { _, _ -> }, {}, busy = Operation.RESTORE)
                3 -> CorrectionsContent(CorrectionsState(loaded = true, replacing = true), {}, {}, {}, busy = Operation.RESTORE)
                4 -> UnreadContent(UnreadState(loaded = true, replacing = true), {}, {}, {}, {}, busy = Operation.RESTORE)
                else -> TeachContent(TeachState(loaded = true, replacing = true), {}, {}, {}, {}, {}, busy = Operation.RESTORE)
            }
        }
        for (each in 0..5) {
            screen = each
            compose.onAllNodesWithText(REPLACING_YOUR_DATA).onFirst().assertIsDisplayed()
            compose.onNodeWithText(RESTORE_LINE).assertDoesNotExist()
        }
    }

    private fun line() {
        compose.onNodeWithText(RESTORE_LINE).assertIsDisplayed()
    }

    private fun nothingTapped() {
        assertEquals("A write was asked for during a restore", emptyList<String>(), tapped)
    }

    private companion object {
        const val RESTORE_LINE = "RESTORING A BACKUP · NOTHING CAN BE CHANGED UNTIL IT FINISHES"
    }
}
