package my.pinged.ledger.merchantmonth

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import my.pinged.data.LocalDates
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ledger.home.dayLabel
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * Merchant month drawn from a [MerchantMonthRead] with no database (#344):
 * one flat list of the merchant's rows across categories in the order read,
 * with no day headings, the kept-out line's *awaiting review* opening the
 * inbox on the first held row, an untrusted month greying every figure and
 * no name, and a read in flight drawing no figure.
 */
@RunWith(AndroidJUnit4::class)
class MerchantMonthScreenContentTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
    private val groceries = Category(id = 2L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1)

    private fun row(id: Long, sen: Long, day: Int, category: Long) =
        FeedRow(ledgerTxn(sen, id = id, categoryId = category, localDate = LocalDates.of(september.atDay(day))), "WARUNG PAK ALI", MERCHANT)

    private fun read(notCaptured: Int = 0) = MerchantMonthRead(
        identity = "WARUNG PAK ALI",
        name = MERCHANT,
        month = september,
        today = LocalDate.of(2026, 10, 30),
        notCaptured = notCaptured,
        totals = listOf(MerchantTotal("WARUNG PAK ALI", MERCHANT, "MYR", 7_140L, 2)),
        keptOut = listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 4_500L)),
        firstHeld = 77L,
        rows = listOf(row(12L, 3_700L, day = 16, category = food.id), row(11L, 3_440L, day = 4, category = groceries.id)),
        chooser = ChooserRead(categories = listOf(food, groceries)),
    )

    private val reviewed = mutableListOf<Long?>()

    private fun draw(read: MerchantMonthRead?) {
        compose.setContent {
            PingedTheme {
                MerchantMonthScreenContent(
                    month = september,
                    read = read,
                    storageUnavailable = false,
                    onBack = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onOpenReview = { reviewed += it },
                )
            }
        }
    }

    @Test fun theRowsAreOneFlatListAcrossCategoriesInTheOrderRead() {
        draw(read())

        assertEquals("The header is not the line's figure", 1, count("RM71.40"))
        assertEquals(1, count("2 TRANSACTIONS"))
        assertEquals("A row is missing, or drawn twice", listOf(1, 1), listOf(count("37.00"), count("34.40")))
        // The semantics tree's order, which is the list's: no bounds are measured here.
        assertEquals("Not in the order read", listOf("37.00", "34.40"), inTreeOrder("37.00", "34.40"))
        assertEquals("A day heading was drawn", 0, count(dayLabel(LocalDates.of(september.atDay(16)))))
        assertEquals("A day heading was drawn", 0, count(dayLabel(LocalDates.of(september.atDay(4)))))
    }

    @Test fun awaitingReviewOpensTheInboxOnTheFirstHeldRow() {
        draw(read())

        compose.onNodeWithText("RM45.00 kept out: 0 excluded, 1 awaiting review").performScrollTo().performFirstLinkClick()

        assertEquals(listOf<Long?>(77L), reviewed)
    }

    @Test fun anUntrustedMonthGreysEveryFigureAndNoName() {
        draw(read(notCaptured = 1))

        compose.onNodeWithText("MAY BE INCOMPLETE · 1 DAY NOT CAPTURED").assertExists()
        assertEquals("The count is not grey", Muted, colour("2 TRANSACTIONS"))
        assertEquals("A row's amount is not grey", Muted, colour("37.00"))
        assertEquals("The merchant's name greyed", Ink, colour(MERCHANT, header = true))
    }

    @Test fun aTrustedMonthDrawsItsFiguresInInk() {
        draw(read())

        assertEquals(Ink, colour("2 TRANSACTIONS"))
        assertEquals(Ink, colour("37.00"))
    }

    @Test fun aReadInFlightDrawsTheDashAndNoFigure() {
        val clock = HeldClock(compose)
        val held = MutableStateFlow<MerchantMonthRead?>(null)
        clock.draw {
            MerchantMonthScreenContent(
                month = september,
                read = held.collectAsState().value,
                storageUnavailable = false,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                today = LocalDate.of(2026, 10, 30),
            )
        }

        clock.at(READING_DELAY_MILLIS + 40)

        assertEquals(1, clock.count(READING))
        assertEquals(1, clock.count(HERO_NOT_READ_YET))
        assertEquals("SPENT IN SEPTEMBER 2026 was not drawn from the key", 1, clock.count("SPENT IN SEPTEMBER 2026"))
        assertEquals("A read in flight drew a figure", emptyList<String>(), clock.figures("SPENT IN SEPTEMBER 2026"))
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Which of [texts] the screen holds, in the semantics tree's order. */
    private fun inTreeOrder(vararg texts: String): List<String> =
        compose.onAllNodes(SemanticsMatcher("one of ${texts.toList()}") { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text in texts } == true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text }.filter { it in texts } }

    /** [text]'s colour; [header] picks the first of several in the tree, the header's, which comes before the rows. */
    private fun colour(text: String, header: Boolean = false): Color {
        val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
        val node = if (header) nodes.first() else nodes.single()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single().layoutInput.style.color
    }
}
