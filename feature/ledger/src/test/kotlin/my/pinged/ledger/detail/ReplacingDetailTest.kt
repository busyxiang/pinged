package my.pinged.ledger.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.REMOVE_CONFIRM
import my.pinged.ledger.ledgerTxn
import my.pinged.ledger.settings.Operation
import my.pinged.parse.ExclusionReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/**
 * Detail during a ledger replacement (#331): the line at the top, and the
 * screen's own writes off, the chooser still offered. When it clears they
 * are on again.
 */
@RunWith(AndroidJUnit4::class)
class ReplacingDetailTest {
    @get:Rule val compose = createComposeRule()

    private var busy by mutableStateOf<Operation?>(Operation.RESTORE)
    private val written = mutableListOf<String>()

    @Test fun aCountedRowsWritesAreOffDuringARestoreAndOnAfterIt() {
        draw(reason = null)

        compose.onNodeWithText(RESTORE_LINE).assertIsDisplayed()
        button(NOT_SPENDING).assertIsNotEnabled().performClick()
        button(DELETE).assertIsNotEnabled()
        button(CHANGE_CATEGORY).assertIsEnabled()
        val note = compose.onNodeWithTag(NOTE_FIELD).performScrollTo().onChildren().filterToOne(hasSetTextAction())
        note.performTextReplacement("lunch")
        button(SAVE_NOTE).assertIsNotEnabled().performClick()
        note.performImeAction()
        assertEquals("A write was made during a restore", emptyList<String>(), written)

        busy = null

        compose.onNodeWithText(RESTORE_LINE).assertDoesNotExist()
        button(SAVE_NOTE).assertIsEnabled().performClick()
        assertEquals("The note kept through the restore was not saved after it", listOf("note lunch"), written)
        button(NOT_SPENDING).assertIsEnabled()
        button(DELETE).assertIsEnabled()
    }

    @Test fun countAsSpendingIsOffDuringADelete() {
        busy = Operation.DELETE
        draw(reason = ExclusionReason.USER)

        compose.onNodeWithText("DELETING EVERYTHING · NOTHING CAN BE CHANGED UNTIL IT FINISHES").assertIsDisplayed()
        button(COUNT_AS_SPENDING).assertIsNotEnabled()
    }

    @Test fun aNotAPaymentSheetOpenWhenARestoreStartsConfirmsNothing() {
        busy = null
        draw(reason = null)
        button(DELETE).performClick()

        busy = Operation.RESTORE

        compose.onNode(hasText(REMOVE_CONFIRM) and hasClickAction()).assertIsNotEnabled().performClick()
        assertEquals("A row was removed during a restore", emptyList<String>(), written)
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    private fun draw(reason: ExclusionReason?) {
        val txn = ledgerTxn(amountSen = 5_000L, isExcluded = reason != null, exclusionReason = reason, id = 9L, categoryId = 1L)
        val row = DetailRow(
            row = FeedRow(txn, identityKey = txn.merchantKey, displayName = MERCHANT),
            categoryName = "Groceries",
            captureTitle = "DuitNow Payment",
            captureText = "You have paid RM50.00 to TAN WEI MING",
            captureBigText = null,
            matchedRuleId = "DUITNOW-PAID-V1",
            packVersion = 7,
        )
        val chooser = ChooserRead(categories = listOf(Category(id = 1L, name = "Groceries", iconKey = "cart", sortOrder = 0)))
        compose.setContent {
            // No keyboard, for the reason `ChooserNewCategoryTest` gives.
            InterceptPlatformTextInput({ _, _ -> awaitCancellation() }) {
                DetailScreenContent(
                    DetailRead(row, chooser),
                    storageUnavailable = false,
                    onBack = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    actions = DetailActions(
                        saveNote = { written += "note $it" },
                        notSpending = { written += "not spending" },
                        countAsSpending = { written += "count" },
                        remove = { written += "remove" },
                    ),
                    zone = ZoneOffset.UTC,
                    busy = busy,
                )
            }
        }
    }

    private companion object {
        const val RESTORE_LINE = "RESTORING A BACKUP · NOTHING CAN BE CHANGED UNTIL IT FINISHES"
    }
}
