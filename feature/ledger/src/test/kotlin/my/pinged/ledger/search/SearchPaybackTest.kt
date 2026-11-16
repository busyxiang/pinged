package my.pinged.ledger.search

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
import java.time.YearMonth
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.SEP_20
import my.pinged.ledger.bill.listRow
import my.pinged.ledger.bill.rowBill
import my.pinged.ledger.home.BILL_NOT_COUNTED_TAG
import my.pinged.ledger.home.CATEGORY_CHIP as ROW_CATEGORY_CHIP
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.PAID_BACK_LINE
import my.pinged.ledger.home.PAYS_BACK_TAG
import my.pinged.ledger.home.RecordingOpenings
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search's rows draw a payback and its bill as the list does (spec 9.1,
 * `design/PaybackList.dc.html`, `PaybackListLeftOut`): the tag that names
 * the bill and opens it, the label TalkBack reads, no chip on a payback, and
 * the bill's paid-back line.
 */
@RunWith(AndroidJUnit4::class)
class SearchPaybackTest {
    @get:Rule val compose = createComposeRule()

    private val food = Category(1L, "Food & Drinks", "utensils", 0, seedName = "Food & Drinks")
    private val uncategorized = Category(16L, "Uncategorized", "circle-dashed", 15, isProtected = true, seedName = "Uncategorized")
    private val recording = RecordingOpenings()

    private fun arjun(counts: Boolean = true) = listRow(
        52L, "Arjun Nair", 3_200L, SEP_20, Direction.REFUND,
        bill = rowBill(5L, "Yuen Kee", SEP_18, counts = counts),
        categoryId = uncategorized.id,
    )

    private fun draw(vararg rows: FeedRow) = compose.setContent {
        PingedTheme {
            SearchScreenContent(
                query = "",
                onQuery = {},
                choice = SearchChoice(month = YearMonth.of(2026, 9)),
                read = SearchRead(
                    text = "",
                    choice = SearchChoice(month = YearMonth.of(2026, 9)),
                    rows = rows.toList(),
                    totals = listOf(SearchTotal("MYR", rowCount = rows.size, countedCount = 0, paymentCount = 0, netSen = 0L)),
                    chooser = ChooserRead(listOf(food, uncategorized), uncategorized.id),
                ),
                storageUnavailable = false,
                onBack = {},
                onOpenSheet = {},
                openings = recording.openings,
                categories = FakeCategoryActions(),
            )
        }
    }

    @Test fun aPaybacksTagNamesItsBillAndOpensIt() {
        draw(arjun())

        listOf("PAYS BACK", "Yuen Kee", "· 18 SEP").forEach { part ->
            compose.onNode(hasText(part) and hasAnyAncestor(hasTestTag(PAYS_BACK_TAG)), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag(PAYS_BACK_TAG).performClick()

        assertEquals("The tag opened something other than the bill", listOf(5L), recording.opened)
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
        draw(arjun(), listRow(53L, "Mei Ling Tan", 3_200L, SEP_20, Direction.REFUND, bill = rowBill(6L, "Tealive", SEP_18, counts = false), categoryId = uncategorized.id))

        compose.onAllNodesWithText(ROW_CATEGORY_CHIP, substring = true).assertCountEquals(0)
    }

    @Test fun aPaybackWhoseBillCountsNowhereSaysItCountsHere() {
        draw(arjun(counts = false))

        compose.onNodeWithText(BILL_NOT_COUNTED_TAG).assertExists()
        compose.onNodeWithTag(PAYS_BACK_TAG).performClick()
        assertEquals("The tag still opens the bill, where Count as spending puts it back", listOf(5L), recording.opened)
    }

    @Test fun aBillPaidBackHasItsLineUnderTheRow() {
        draw(listRow(5L, "Yuen Kee", 9_600L, SEP_18, categoryId = food.id, paidBackSen = 6_400L))

        // Unmerged: the row is one clickable, and reads the line with the rest of it.
        compose.onNodeWithTag(PAID_BACK_LINE, useUnmergedTree = true).assertTextEquals("64.00 PAID BACK · YOUR SHARE 32.00")
    }
}
