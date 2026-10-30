package my.pinged.ledger.breakdown

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.BreakdownLine
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * Breakdown drawn from a [BreakdownRead] with no database (#342): a line
 * opens and closes the rows behind it, the kept-out line's *awaiting review*
 * opens the inbox on the first held row, a gone category leaves, and an
 * untrusted month greys every figure and no name.
 */
@RunWith(AndroidJUnit4::class)
class BreakdownScreenContentTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
    private val warung = LineKey.of("WARUNG PAK ALI")

    private fun row(id: Long, sen: Long) = FeedRow(ledgerTxn(sen, id = id, categoryId = 1L), "WARUNG PAK ALI", MERCHANT)

    private fun read(notCaptured: Int = 0, category: Category? = food, rows: Map<LineKey, List<FeedRow>> = emptyMap()) = BreakdownRead(
        category = category,
        month = september,
        today = LocalDate.of(2026, 10, 30),
        notCaptured = notCaptured,
        lines = listOf(
            BreakdownLine("WARUNG PAK ALI", MERCHANT, "MYR", 7_140L, 2),
            BreakdownLine(null, null, "MYR", 960L, 1),
        ),
        keptOut = listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 4_500L)),
        firstHeld = 77L,
        rows = rows,
        chooser = ChooserRead(categories = listOf(food)),
    )

    private val toggled = mutableListOf<LineKey>()
    private val reviewed = mutableListOf<Long?>()
    private var gone = 0

    private fun draw(read: BreakdownRead, expanded: Set<LineKey> = emptySet()) {
        compose.setContent {
            PingedTheme {
                BreakdownScreenContent(
                    month = september,
                    read = read,
                    expanded = expanded,
                    storageUnavailable = false,
                    onBack = {},
                    onGone = { gone++ },
                    onToggle = { toggled += it },
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onOpenReview = { reviewed += it },
                )
            }
        }
    }

    @Test fun aLineOpensTheRowsBehindItAndClosesThemAgain() {
        var expanded by mutableStateOf(emptySet<LineKey>())
        val rows = mapOf(warung to listOf(row(11L, 3_700L), row(12L, 3_440L)))
        compose.setContent {
            PingedTheme {
                BreakdownScreenContent(
                    month = september,
                    read = read(rows = rows),
                    expanded = expanded,
                    storageUnavailable = false,
                    onBack = {},
                    onGone = {},
                    onToggle = { key -> expanded = if (key in expanded) expanded - key else expanded + key },
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                )
            }
        }
        val inExpansion = hasAnyAncestor(hasTestTag(EXPANSION))
        assertEquals(0, compose.onAllNodes(hasText("37.00") and inExpansion).fetchSemanticsNodes().size)

        line(MERCHANT).assertLabel(SHOW_ROWS).performClick()

        compose.onNode(hasText("37.00") and inExpansion).assertExists()
        compose.onNode(hasText("34.40") and inExpansion).assertExists()
        line(MERCHANT).assertLabel(HIDE_ROWS).performClick()
        assertEquals(0, compose.onAllNodes(hasText("37.00") and inExpansion).fetchSemanticsNodes().size)
    }

    @Test fun eachLineTogglesItsOwnKeyNoMerchantIncluded() {
        draw(read())

        line(NO_MERCHANT).performClick()
        line(MERCHANT).performClick()

        assertEquals(listOf(LineKey.of(null), warung), toggled)
    }

    @Test fun awaitingReviewOpensTheInboxOnTheFirstHeldRow() {
        draw(read())

        compose.onNodeWithText("RM45.00 kept out: 0 excluded, 1 awaiting review").performScrollTo().performFirstLinkClick()

        assertEquals(listOf<Long?>(77L), reviewed)
    }

    @Test fun aCategoryThatIsGoneLeaves() {
        draw(read(category = null))
        compose.waitForIdle()

        assertEquals(1, gone)
    }

    @Test fun anUntrustedMonthGreysEveryFigureAndNoName() {
        draw(read(notCaptured = 1, rows = mapOf(warung to listOf(row(11L, 3_700L)))), expanded = setOf(warung))

        compose.onNodeWithText("MAY BE INCOMPLETE · 1 DAY NOT CAPTURED").assertExists()
        assertEquals("The count is not grey", Muted, colour(compose.onNodeWithText("3 TRANSACTIONS")))
        assertEquals("A line's net is not grey", Muted, colour(compose.onNodeWithText("71.40", useUnmergedTree = true)))
        assertEquals("A line's count is not grey", Muted, colour(compose.onNodeWithText("2×", useUnmergedTree = true)))
        assertEquals("An expanded row's amount is not grey", Muted, colour(compose.onNode(hasText("37.00") and hasAnyAncestor(hasTestTag(EXPANSION)), useUnmergedTree = true)))
        assertEquals("A merchant's name greyed", Ink, colour(compose.onNode(hasText(MERCHANT) and !hasAnyAncestor(hasTestTag(EXPANSION)), useUnmergedTree = true)))
        assertEquals("The category's name greyed", Ink, colour(compose.onNodeWithText("Food & Drinks")))
    }

    @Test fun aTrustedMonthDrawsItsFiguresInInk() {
        draw(read())

        assertEquals(Ink, colour(compose.onNodeWithText("3 TRANSACTIONS")))
        assertEquals(Ink, colour(compose.onNodeWithText("71.40", useUnmergedTree = true)))
    }

    private fun line(name: String) = compose.onNode(hasText(name) and hasClickAction()).performScrollTo()

    private fun SemanticsNodeInteraction.assertLabel(label: String): SemanticsNodeInteraction = apply {
        assertEquals(label, fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    private fun colour(node: SemanticsNodeInteraction): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single().layoutInput.style.color
    }
}
