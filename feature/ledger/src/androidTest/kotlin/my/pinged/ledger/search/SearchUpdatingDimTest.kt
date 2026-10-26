package my.pinged.ledger.search

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.test.boundedWaitUntil
import my.pinged.ui.test.ink
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.UPDATING_ALPHA
import my.pinged.ui.theme.updatingFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * The pixel half of `SearchUpdatingTest` (#285): while the words typed have
 * no read, the last answer's hero draws at the board's 45%. Its ink during
 * the re-read over its ink before it is the alpha, whatever its colour.
 */
@RunWith(AndroidJUnit4::class)
class SearchUpdatingDimTest {
    @get:Rule val compose = createComposeRule()

    private val month = YearMonth.of(2026, 9)
    private val query = MutableStateFlow("grab")

    private val read = SearchRead(
        text = "grab",
        choice = SearchChoice(month = month),
        rows = listOf(ledgerTxn(1_840L, id = 1L)).map { FeedRow(it, it.merchantKey, "Grab") },
        totals = listOf(SearchTotal("MYR", rowCount = 18, countedCount = 18, paymentCount = 18, netSen = 31_240L)),
        chooser = ChooserRead(listOf(Category(2, "Groceries", "shopping-basket", 1, seedName = "Groceries")), null),
    )

    @Test fun theLastAnswersHeroDrawsAt45PercentWhileTheWordsTypedAreRead() {
        compose.setContent {
            PingedTheme {
                val words = query.collectAsState().value
                SearchScreenContent(
                    query = words,
                    onQuery = {},
                    choice = SearchChoice(month = month),
                    read = read,
                    // What `SearchScreen` is told by its read: words not yet answered.
                    rereading = read.text != words,
                    storageUnavailable = false,
                    onBack = {},
                    onOpenSheet = {},
                )
            }
        }
        val settled = compose.onNodeWithTag(SEARCH_TOTAL).captureToImage().ink()

        query.value = "grab f"
        compose.boundedWaitUntil(TIMEOUT) { compose.onAllNodesWithText(updatingFor("grab f")).fetchSemanticsNodes().isNotEmpty() }
        val dimmed = compose.onNodeWithTag(SEARCH_TOTAL).captureToImage().ink()

        assertEquals("The hero drew at ${dimmed / settled} of its ink ($dimmed of $settled)", UPDATING_ALPHA, dimmed / settled, 0.08f)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
