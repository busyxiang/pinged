package my.pinged.ledger.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import my.pinged.data.SourcesLine
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.CaptureSource
import my.pinged.ledger.FreshUiDispatcher
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * The sources line on Spending (#367; spec 9.1): the summary's line under
 * its hero, `NO APP IS READ YET` opening Capture sources, and no line, not a
 * guessed one, while there is no summary to name the apps of. Where it sits
 * against the TopBar's threshold is `SummaryScrollTest`'s, on a device;
 * which apps it names is `SourcesLineTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class SpendingSourcesLineTest {
    @get:Rule val freshUiDispatcher = FreshUiDispatcher()

    @get:Rule val compose = createComposeRule()

    private val october = YearMonth.of(2026, 10)

    private fun on(pkg: String, label: String) = CaptureSource(pkg, label, enabled = true, firstSeenAt = 0L)

    private val tngAndGrab = SourcesLine.of(listOf(on(TNG, "Touch 'n Go"), on(GRAB, "GrabPay")), setOf(TNG), emptySet(), byHand = true)

    private val nothingRead = SourcesLine.of(emptyList(), emptySet(), emptySet(), byHand = false)

    private fun summary(sources: SourcesLine) = MonthSummary(
        month = october,
        totals = listOf(CurrencyTotal("MYR", 119_273L)),
        top = emptyList(),
        trustworthy = true,
        sources = sources,
    )

    private fun draw(summary: MonthSummary?, onOpenSources: () -> Unit = {}) = compose.setContent {
        PingedTheme {
            MonthSummaryBlock(
                summary = summary,
                categoriesById = emptyMap(),
                onHeroBottom = {},
                heroNotReadYetShown = true,
                onOpenSources = onOpenSources,
            )
        }
    }

    /** Every wording the line can open with: `FROM`, `ONLY BY HAND`, `NO APP IS READ YET`. */
    private fun anyLine(): Int = listOf("FROM ", "ONLY BY HAND", "NO APP IS READ YET")
        .sumOf { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size }

    @Test fun theSummaryDrawsItsMonthsLine() {
        draw(summary(tngAndGrab))

        compose.onNodeWithText("FROM TOUCH 'N GO, AND BY HAND. GRABPAY IS ON BUT PINGED CANNOT READ IT YET").assertExists()
    }

    @Test fun noAppIsReadYetOpensCaptureSources() {
        var opened = 0
        draw(summary(nothingRead), onOpenSources = { opened++ })

        compose.onNodeWithText("NO APP IS READ YET").performClick()

        assertEquals("NO APP IS READ YET on Spending did not open Capture sources", 1, opened)
    }

    /** No apps are known before the read lands: no line rather than a guess at one. */
    @Test fun aSummaryNotReadYetDrawsNoLine() {
        draw(summary = null)

        compose.onNodeWithText("SPENT SO FAR").assertExists()
        assertEquals("A summary not read yet drew a sources line", 0, anyLine())
    }

    /** The last read's line is not drawn under the notice that the ledger cannot be read. */
    @Test fun anUnreadableLedgerDrawsNoLine() {
        val feed = flowOf(PagingData.empty<LedgerItem>())
        compose.setContent {
            PingedTheme {
                LedgerScreenContent(
                    items = feed.collectAsLazyPagingItems(),
                    read = LedgerRead(summary = summary(tngAndGrab)),
                    storageUnavailable = true,
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText(CANNOT_READ_YOUR_DATA, substring = true).assertExists()
        assertEquals("An unreadable ledger drew the last read's sources line", 0, anyLine())
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val GRAB = "com.grabtaxi.passenger"
    }
}
