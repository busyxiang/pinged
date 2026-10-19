package my.pinged.ledger.search

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.home.CATEGORY_CHIP as ROW_CATEGORY_CHIP
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * Search's rows sort in place (#273): an Uncategorized row carries the
 * ledger's "+ CATEGORY", which opens the ledger's chooser for that row and
 * saves through it; the rest of the row still opens Detail.
 */
@RunWith(AndroidJUnit4::class)
class SearchSortTest {
    @get:Rule val compose = createComposeRule()

    private val groceries = Category(2, "Groceries", "shopping-basket", 1, seedName = "Groceries")
    private val uncategorized = Category(16, "Uncategorized", "circle-dashed", 15, isProtected = true, seedName = "Uncategorized")

    private val toSort = ledgerTxn(1_840L, categoryId = uncategorized.id, id = 1L)
    private val excluded = ledgerTxn(900L, categoryId = uncategorized.id, isExcluded = true, id = 2L)
    private val filed = ledgerTxn(650L, categoryId = groceries.id, id = 3L)

    private val assigned = mutableListOf<Pair<Long, Long>>()
    private val opened = mutableListOf<Long>()

    private fun draw() = compose.setContent {
        PingedTheme {
            SearchScreenContent(
                query = "",
                onQuery = {},
                choice = SearchChoice(month = YearMonth.of(2026, 10)),
                read = SearchRead(
                    text = "",
                    choice = SearchChoice(month = YearMonth.of(2026, 10)),
                    rows = listOf(toSort, excluded, filed).map { FeedRow(it, it.merchantKey, "Row ${it.id}") },
                    totals = listOf(SearchTotal("MYR", rowCount = 3, countedCount = 2, paymentCount = 2, netSen = 2_490L)),
                    chooser = ChooserRead(listOf(groceries, uncategorized), uncategorized.id, emptyMap(), emptySet()) { null },
                ),
                storageUnavailable = false,
                onBack = {},
                onOpenSheet = {},
                onOpenDetail = { opened += it.id },
                onAssign = { txnId, categoryId, _ -> assigned += txnId to categoryId },
            )
        }
    }

    @Test fun onlyTheCountedUncategorizedRowCarriesTheChip() {
        draw()

        assertEquals(
            "Not exactly the one Uncategorized, not excluded row carries + CATEGORY",
            1,
            compose.onAllNodesWithText(ROW_CATEGORY_CHIP, substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test fun theChipOpensTheChooserForItsRowAndSavesThroughIt() {
        draw()

        compose.onNodeWithText(ROW_CATEGORY_CHIP, substring = true).performScrollTo().performClick()
        compose.onNodeWithText(PICKER_HEADING).assertExists()
        compose.onNode(isSelectable() and hasText(groceries.name)).performClick()
        compose.onNode(hasText("Save") and hasClickAction()).performClick()

        assertEquals("The chooser did not save the row the chip is on", listOf(toSort.id to groceries.id), assigned)
        assertEquals("The chip opened Detail as well", emptyList<Long>(), opened)
    }

    @Test fun theRestOfTheRowStillOpensDetail() {
        draw()

        compose.onNodeWithText("Row 1", useUnmergedTree = true).performScrollTo().performClick()

        assertEquals("The row's name no longer opens Detail", listOf(toSort.id), opened)
    }
}
