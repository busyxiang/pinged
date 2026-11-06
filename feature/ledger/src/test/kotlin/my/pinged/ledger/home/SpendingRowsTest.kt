package my.pinged.ledger.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * A row in Spending's feed (#377): its chip opens the chooser over the
 * screen, its long press the merchant sheet, and its tap and the pinned
 * `N AWAITING REVIEW` row go to the row openings. The chooser's row is found
 * among the rows Paging loaded. What the chooser and sheet do once open is
 * `RowHostTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class SpendingRowsTest {
    @get:Rule val compose = createComposeRule()

    private val recording = RecordingOpenings()

    private fun draw() {
        val september = YearMonth.of(2026, 9)
        val groceries = Category(id = 2L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 0)
        val uncategorized = Category(id = 16L, name = "Uncategorized", iconKey = "circle-dashed", sortOrder = 15, isProtected = true)
        val row = LedgerItem.Row(ledgerTxn(1_840L, categoryId = uncategorized.id, id = 7L), "WARUNG PAK ALI", MERCHANT)
        val feed = flowOf(
            PagingData.from(
                listOf<LedgerItem>(row),
                LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true)),
            ),
        )
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = feed.collectAsLazyPagingItems(),
                    read = LedgerRead(
                        summary = MonthSummary(september, listOf(CurrencyTotal("MYR", 1_840L)), emptyList(), trustworthy = true, sources = null),
                        categories = listOf(groceries, uncategorized),
                        uncategorizedId = uncategorized.id,
                        awaitingReview = 1,
                    ),
                    storageUnavailable = false,
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(publishesSheet = true),
                    onOpenSettings = {},
                    openings = recording.openings,
                    picked = september,
                )
            }
        }
    }

    @Test fun aChipOpensTheChooserOverTheScreen() {
        draw()

        compose.onNodeWithText(CATEGORY_CHIP).performScrollTo().performClick()

        compose.onNodeWithText(PICKER_HEADING).assertExists()
    }

    @Test fun aLongPressOnARowOpensItsMerchantSheetOverTheScreen() {
        draw()

        compose.onNodeWithText(MERCHANT).performScrollTo().performTouchInput { longClick() }

        compose.onNodeWithText(NAME_HEADING).assertExists()
    }

    @Test fun aTapOnARowOpensItThroughTheOpenings() {
        draw()

        compose.onNodeWithText(MERCHANT).performScrollTo().performClick()

        assertEquals("The row's tap did not reach the openings", listOf(7L), recording.opened)
    }

    @Test fun thePinnedRowOpensTheReviewInboxWithNoFocus() {
        draw()

        compose.onNodeWithText("1 AWAITING REVIEW", substring = true).performClick()

        assertEquals(listOf<Long?>(null), recording.reviewed)
    }
}
