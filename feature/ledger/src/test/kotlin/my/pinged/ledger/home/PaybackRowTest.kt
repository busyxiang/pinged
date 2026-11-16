package my.pinged.ledger.home

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.SEP_20
import my.pinged.ledger.bill.listRow
import my.pinged.ledger.bill.rowBill
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A payback's row and its bill's in the list (spec 9.1,
 * `design/PaybackList.dc.html`, `PaybackListLeftOut`): the tag that names
 * the bill and opens it, the label TalkBack reads, the chip a payback is
 * never offered, and the bill's paid-back line.
 */
@RunWith(AndroidJUnit4::class)
class PaybackRowTest {
    @get:Rule val compose = createComposeRule()

    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
    private val uncategorized = Category(id = 16L, name = "Uncategorized", iconKey = "circle-dashed", sortOrder = 15, isProtected = true)
    private val recording = RecordingOpenings()

    private fun draw(vararg rows: FeedRow) {
        compose.setContent {
            PingedTheme {
                Column {
                    rows.forEach { row ->
                        LedgerRow(
                            row = row,
                            categoriesById = listOf(food, uncategorized).associateBy { it.id },
                            uncategorizedId = uncategorized.id,
                            canCategorize = true,
                            onCategorize = {},
                            onOpenMerchant = {},
                            openings = recording.openings,
                        )
                    }
                }
            }
        }
    }

    private fun arjun(counts: Boolean = true, typed: Boolean = false) = listRow(
        52L, "Arjun Nair", 3_200L, SEP_20, Direction.REFUND,
        bill = rowBill(5L, "Yuen Kee", SEP_18, counts = counts, typed = typed),
        categoryId = uncategorized.id,
    )

    @Test fun aPaybacksTagNamesItsBillAndOpensIt() {
        draw(arjun())

        listOf("PAYS BACK", "Yuen Kee", "· 18 SEP").forEach { part ->
            compose.onNode(hasText(part) and hasAnyAncestor(hasTestTag(PAYS_BACK_TAG)), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag(PAYS_BACK_TAG).performClick()

        assertEquals("The tag opened something other than the bill", listOf(5L), recording.opened)
    }

    @Test fun aTypedBillOpensOnItsEditScreen() {
        val entries = mutableListOf<Long>()
        val details = mutableListOf<Long>()
        val openings = RowOpenings(openEntry = { entries += it.id }, openDetail = { details += it.id }, openReview = {})

        openings.openBill(rowBill(5L, "Warung Pak Din", SEP_18, typed = true))
        openings.openBill(rowBill(6L, "Yuen Kee", SEP_18))

        assertEquals("A typed bill has no detail screen (spec 9.4)", listOf(5L), entries)
        assertEquals(listOf(6L), details)
    }

    @Test fun aPaybacksRowIsReadAsWhereItsMoneyCounts() {
        draw(arjun())

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("32 ringgit received from Arjun Nair, counted on 18 September with Yuen Kee"),
            ) and hasClickAction(),
        ).assertExists()
        // Not read twice: the name and amount say it in pieces.
        compose.onAllNodesWithText("Arjun Nair").assertCountEquals(0)
    }

    @Test fun anUncategorizedPaybackIsOfferedNoCategoryChip() {
        draw(arjun(), arjun(counts = false))

        compose.onAllNodesWithText(CATEGORY_CHIP).assertCountEquals(0)
    }

    @Test fun aPaybackWhoseBillCountsNowhereSaysItCountsHere() {
        draw(arjun(counts = false))

        compose.onNodeWithText(BILL_NOT_COUNTED_TAG).assertExists()
        compose.onNodeWithTag(PAYS_BACK_TAG).performClick()
        assertEquals("The tag still opens the bill, where Count as spending puts it back", listOf(5L), recording.opened)
    }

    @Test fun aBillPaidBackHasItsLineUnderTheRow() {
        draw(listRow(5L, "Yuen Kee", 9_600L, SEP_18, paidBackSen = 6_400L))

        compose.onNodeWithTag(PAID_BACK_LINE).assertTextEquals("64.00 PAID BACK · YOUR SHARE 32.00")
    }
}
