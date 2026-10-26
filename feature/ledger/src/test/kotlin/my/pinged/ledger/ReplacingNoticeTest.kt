package my.pinged.ledger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import my.pinged.capture.Corrections
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.UnreadCapture
import my.pinged.data.entity.Category
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.ledger.categories.CategoriesContent
import my.pinged.data.dao.CategoryUse
import my.pinged.ledger.categories.toState
import my.pinged.ledger.corrections.CorrectionsContent
import my.pinged.ledger.corrections.Pending
import my.pinged.ledger.corrections.toState
import my.pinged.ledger.day.DayHeading
import my.pinged.ledger.day.DayRead
import my.pinged.ledger.day.DayScreenContent
import my.pinged.ledger.detail.DetailRead
import my.pinged.data.dao.DetailRow
import my.pinged.ledger.detail.DetailScreenContent
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.data.dao.FeedRow
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.Taught
import my.pinged.ledger.learned.toState
import my.pinged.ledger.review.ReviewCard
import my.pinged.ledger.review.ReviewInboxScreenContent
import my.pinged.ledger.search.SearchChoice
import my.pinged.ledger.search.SearchRead
import my.pinged.ledger.search.SearchScreenContent
import my.pinged.data.SearchTotal
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.sources.SourceRow
import my.pinged.ledger.sources.SourcesScreenContent
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.sources.toState
import my.pinged.ledger.unread.UnreadContent
import my.pinged.ledger.unread.UnreadPage
import my.pinged.ledger.unread.toState
import my.pinged.read.Answered
import my.pinged.read.ReadState
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Every `:feature:ledger` screen that reads through `GuardedRunner`, while a
 * ledger replacement turns its read away (#328, decision 2): it says so with
 * [REPLACING_YOUR_DATA] where [CANNOT_READ_YOUR_DATA] would go, and draws
 * nothing it held from before, which describes the ledger going away.
 *
 * Each test draws the screen holding one row, checks the row is drawn, then
 * raises `replacing` over the same answer, as `ScreenRead` does when a read
 * is refused: the answer is kept, so only the screen can hide it. Charts'
 * is `ChartsReplacingTest`, in its own module.
 */
@RunWith(AndroidJUnit4::class)
class ReplacingNoticeTest {
    @get:Rule val freshUiDispatcher = FreshUiDispatcher()

    @get:Rule val compose = createComposeRule()

    /** What `ReadState.replacing` says; raised by [replace]. */
    private val replacing = MutableStateFlow(false)

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    /**
     * Draws [content], checks [held] is on screen, raises replacing, and checks
     * the notice took its place, [notices] times: once for each place the screen
     * draws [CANNOT_READ_YOUR_DATA].
     */
    private fun assertReplaces(held: String, notices: Int = 1, content: @Composable (replacing: Boolean) -> Unit) {
        compose.setContent { PingedTheme { content(replacing.collectAsState().value) } }
        assertEquals("precondition: the held row was not drawn before the replacement", true, count(held) > 0)

        replacing.value = true
        compose.waitForIdle()

        assertEquals("A screen refused by a replacement did not say so", notices, count(REPLACING_YOUR_DATA))
        assertEquals("A screen refused by a replacement said its data cannot be read", 0, count(CANNOT_READ_YOUR_DATA))
        assertEquals("A screen refused by a replacement still drew what it held", 0, count(held))
    }

    /** [answer], landed, and raised to replacing when [replacing] is. */
    private fun <Q, A> held(question: Q, answer: A, replacing: Boolean) =
        ReadState(Answered(question, answer), rereading = false, unavailable = false, replacing = replacing)

    @Test fun spending() {
        val feed = flowOf(
            PagingData.from(
                listOf<LedgerItem>(LedgerItem.Row(ledgerTxn(1_250L, id = 7L))),
                LoadStates(
                    refresh = LoadState.NotLoading(endOfPaginationReached = true),
                    prepend = LoadState.NotLoading(true),
                    append = LoadState.NotLoading(true),
                ),
            ),
        )
        assertReplaces(MERCHANT) { replacing ->
            LedgerScreenContent(
                items = feed.collectAsLazyPagingItems(),
                read = LedgerRead(summary = null, categories = emptyList(), uncategorizedId = null),
                storageUnavailable = false,
                replacing = replacing,
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                onOpenSettings = {},
            )
        }
    }

    @Test fun day() {
        val read = DayRead(
            rows = listOf(feedRow()),
            heading = DayHeading(total = "RM12.50", keptOut = null, notWatching = false),
            chooser = ChooserRead(),
        )
        assertReplaces(MERCHANT) { replacing ->
            DayScreenContent(
                date = LocalDate.of(2026, 9, 25),
                read = read,
                storageUnavailable = false,
                replacing = replacing,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
            )
        }
    }

    @Test fun detail() {
        val row = DetailRow(
            row = feedRow(),
            categoryName = "Groceries",
            captureTitle = "DuitNow Payment",
            captureText = "You have paid RM12.50",
            captureBigText = null,
            matchedRuleId = "DUITNOW-PAID-V1",
            packVersion = 7,
        )
        assertReplaces(MERCHANT) { replacing ->
            DetailScreenContent(
                read = DetailRead(row, ChooserRead()),
                storageUnavailable = false,
                replacing = replacing,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                zone = ZoneOffset.UTC,
            )
        }
    }

    @Test fun search() {
        val month = YearMonth.of(2026, 9)
        val read = SearchRead(
            text = "warung",
            choice = SearchChoice(month = month),
            rows = listOf(feedRow()),
            totals = listOf(SearchTotal("MYR", rowCount = 1, countedCount = 1, paymentCount = 1, netSen = 1_250L)),
            chooser = ChooserRead(),
        )
        assertReplaces(MERCHANT) { replacing ->
            SearchScreenContent(
                query = "warung",
                onQuery = {},
                choice = SearchChoice(month = month),
                read = read,
                storageUnavailable = false,
                replacing = replacing,
                onBack = {},
                onOpenSheet = {},
                categories = FakeCategoryActions(),
            )
        }
    }

    /** Decision 3: one line at a time, so the busy line goes with the cards it withheld answers from. */
    @Test fun review() {
        val card = ReviewCard(ledgerTxn(1_250L, state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD, id = 7L))
        assertReplaces(MERCHANT) { replacing ->
            ReviewInboxScreenContent(
                cards = listOf(card),
                storageUnavailable = false,
                replacing = replacing,
                busy = Operation.RESTORE,
                onBack = {},
                onAnswer = { _, _ -> },
            )
        }
        assertEquals("The inbox drew its busy line beside the replacement's", 0, count("ANSWERED"))
    }

    @Test fun categories() {
        val uses = listOf(CategoryUse(Category(1, "Food & Drinks", "utensils", 0, seedName = null), 3, 0))
        assertReplaces("Food & Drinks") { replacing ->
            CategoriesContent(
                state = held(Unit, uses, replacing).toState(),
                onBack = {},
                onAdd = { _, _ -> },
                onEdit = { _, _, _ -> },
                onDelete = {},
            )
        }
    }

    @Test fun learned() {
        val taught = Taught(
            items = listOf(LearnedMerchant("MR DIY", "MR DIY", 4L, "Shopping", payments = 3)),
            messages = emptyList(),
            ignored = emptyList(),
        )
        assertReplaces("MR DIY") { replacing ->
            LearnedContent(state = held(Unit, taught, replacing).toState(), onBack = {}, onDelete = {})
        }
    }

    @Test fun sources() {
        val sources = SourcesState(suggested = listOf(SourceRow("my.boost", "Boost", 1, true)), loaded = true)
        // Once for each list, as it says CANNOT READ YOUR DATA.
        assertReplaces("Boost", notices = 2) { replacing ->
            SourcesScreenContent(state = held(Unit, sources, replacing).toState(), onToggle = { _, _ -> }, onBack = {})
        }
    }

    @Test fun corrections() {
        val before = ledgerTxn(1_250L, state = TxnState.PENDING, pendingReason = PendingReason.OVER_THRESHOLD, id = 7L)
        val correction = Corrections.Correction(
            captureId = 70L,
            fromPackVersion = 11,
            toPackVersion = 12,
            previousRuleId = "a-v1",
            ruleId = "b-v1",
            before = before,
            after = before.copy(pendingReason = PendingReason.TRANSFER_SUSPECT),
            changed = setOf(Corrections.Field.STATUS),
        )
        assertReplaces(MERCHANT) { replacing ->
            CorrectionsContent(
                state = held(Unit, Pending(listOf(correction), packVersion = 12), replacing).toState(),
                onBack = {},
                onAccept = {},
                onDecline = {},
            )
        }
    }

    @Test fun unread() {
        val capture = UnreadCapture(
            id = 1L,
            sourcePackage = "my.boost",
            postedAt = 1_790_730_840_000L,
            title = null,
            text = "You spent RM23.50.",
            bigText = null,
            label = "Boost",
        )
        assertReplaces("You spent RM23.50.") { replacing ->
            UnreadContent(
                state = held(0, UnreadPage(listOf(capture), more = false), replacing).toState(),
                onBack = {},
                onNeverATransaction = {},
                onCopy = {},
                onEnd = {},
                zone = ZoneOffset.UTC,
            )
        }
    }

    private fun feedRow(): FeedRow = ledgerTxn(1_250L, id = 7L).let { FeedRow(it, it.merchantKey, MERCHANT) }
}
