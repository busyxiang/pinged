package my.pinged.ledger

import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import my.pinged.ledger.unread.NEVER_A_TRANSACTION
import my.pinged.ledger.unread.UnreadContent
import my.pinged.ledger.unread.UnreadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings sub-screens during a ledger replacement (#331): each says so at
 * its top and its writes are off. What the holders refuse beneath them is
 * `ReplacingRefusesWritesTest`'s.
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

    @Test fun unreadMarksNothingNeverATransaction() {
        compose.setContent {
            UnreadContent(
                UnreadState(loaded = true, items = listOf(UnreadCapture(1L, "p", 0L, "t", "x", null, null))),
                onBack = {},
                onNeverATransaction = { tapped += "never" },
                onCopy = {},
                onEnd = {},
                busy = Operation.RESTORE,
            )
        }

        line()
        compose.onNode(hasText(NEVER_A_TRANSACTION) and hasClickAction()).performScrollTo().assertIsNotEnabled().performClick()
        nothingTapped()
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
