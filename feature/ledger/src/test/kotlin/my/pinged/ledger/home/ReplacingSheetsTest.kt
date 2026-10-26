package my.pinged.ledger.home

import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantMember
import my.pinged.data.entity.Category
import my.pinged.data.LocalDates
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The category chooser and the merchant sheet during a ledger replacement
 * (#331): both still open, both say why nothing can be changed, and their
 * writes are off -- including a sheet that was already open when the
 * replacement started, which disables itself in place.
 */
@RunWith(AndroidJUnit4::class)
class ReplacingSheetsTest {
    @get:Rule val compose = createComposeRule()

    private val groceries = Category(2, "Groceries", "shopping-basket", 1)
    private val categories = FakeCategoryActions()
    private val merchants = FakeMerchantActions()

    @Test fun aChooserOpenedDuringARestoreSaysSoAndSavesNothing() {
        categories.busy.value = Operation.RESTORE
        chooser()

        compose.onNodeWithText(RESTORE_LINE).assertIsDisplayed()
        compose.onNode(hasText(NEW_CATEGORY) and hasClickAction()).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun aChooserAlreadyOpenWhenADeleteStartsDisablesItsSaveInPlace() {
        chooser()
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction()).assertIsEnabled()

        categories.busy.value = Operation.DELETE

        compose.onNodeWithText(DELETE_LINE).assertIsDisplayed()
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction()).assertIsNotEnabled().performClick()
        assertEquals("A save was made during a delete-everything", emptyList<Any>(), categories.assigned)

        categories.busy.value = null
        compose.onNodeWithText(DELETE_LINE).assertDoesNotExist()
        compose.onNode(hasText(SAVE_LABEL) and hasClickAction()).assertIsEnabled()
    }

    @Test fun aMerchantSheetOpenedDuringARestoreSaysSoAndChangesNothing() {
        merchants.busy.value = Operation.RESTORE
        merchantSheet(withMembers)

        compose.onNodeWithText(RESTORE_LINE).assertIsDisplayed()
        mergeSeparateAndRenameAreOff()
    }

    @Test fun aMerchantSheetAlreadyOpenWhenARestoreStartsDisablesItselfInPlace() {
        merchantSheet(withMembers)
        separate().assertIsEnabled()

        merchants.busy.value = Operation.RESTORE

        compose.onNodeWithText(RESTORE_LINE).assertIsDisplayed()
        mergeSeparateAndRenameAreOff()
    }

    @Test fun aMergedMerchantsSeparateIsOffDuringARestore() {
        merchants.busy.value = Operation.RESTORE
        merchantSheet(withMembers.copy(identityKey = "TNG*ALI", mergedInto = "Ali", members = emptyList()))

        compose.onNode(hasText("$SEPARATE from Ali") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }

    private fun mergeSeparateAndRenameAreOff() {
        compose.onNode(hasSetTextAction() and hasText("99 Speedmart")).performTextReplacement("Speedmart")
        compose.onNode(hasText(SAVE_NAME) and hasClickAction()).assertIsNotEnabled()

        separate().assertIsNotEnabled()

        compose.onNodeWithTag(MERCHANT_LIST_TAG).performScrollToNode(hasText("Kedai Ali"))
        compose.onNodeWithText("Kedai Ali").performClick()
        val commit = hasText(countItAs(withMembers.others.single())) and hasClickAction()
        compose.onNodeWithTag(MERCHANT_LIST_TAG).performScrollToNode(commit)
        compose.onNode(commit).assertIsNotEnabled().performClick()

        assertEquals("A merge was made during a replacement", emptyList<String>(), merchants.merged)
        assertEquals("A rename was made during a replacement", emptyList<String>(), merchants.renamed)
    }

    /** The member's Separate, scrolled into the lazy list. */
    private fun separate(): SemanticsNodeInteraction {
        val separate = hasText(SEPARATE) and hasClickAction()
        compose.onNodeWithTag(MERCHANT_LIST_TAG).performScrollToNode(separate)
        return compose.onNode(separate)
    }

    private fun chooser() = compose.setContent {
        PingedTheme {
            RowCategoryChooser(
                row = FeedRow(ledgerTxn(1_840L, categoryId = groceries.id).copy(merchantKey = null), null, "Ali"),
                chooser = ChooserRead(categories = listOf(groceries)),
                actions = categories,
                onClose = {},
            )
        }
    }

    private fun merchantSheet(state: MerchantSheetState) {
        merchants.merchantSheet.value = OpenMerchantSheet(MerchantOpening(state.ownKey, state.name, LocalDates.of(LocalDate.of(2026, 9, 14))), state)
        compose.setContent {
            // No keyboard, for the reason `ChooserNewCategoryTest` gives.
            InterceptPlatformTextInput({ _, _ -> awaitCancellation() }) {
                PingedTheme { MerchantSheet(merchants) }
            }
        }
    }

    private companion object {
        const val RESTORE_LINE = "RESTORING A BACKUP · NOTHING CAN BE CHANGED UNTIL IT FINISHES"
        const val DELETE_LINE = "DELETING EVERYTHING · NOTHING CAN BE CHANGED UNTIL IT FINISHES"

        val withMembers = MerchantSheetState(
            ownKey = "TNG*99SPEEDMART",
            identityKey = "TNG*99SPEEDMART",
            name = "99 Speedmart",
            derivedName = "99 Speedmart",
            txnCount = 11,
            mergedInto = null,
            members = listOf(MerchantMember("99 SPEEDMART", "99 SPEEDMART", txnCount = 2)),
            others = listOf(MerchantChoice("TNG*ALI", "Kedai Ali", "Kedai Ali", 2)),
            suggested = emptySet(),
        )
    }
}
