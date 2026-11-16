package my.pinged.ledger.bill

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import my.pinged.data.LocalDates
import my.pinged.data.dao.BreakdownLine
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.dao.RowBill
import my.pinged.data.entity.Category
import my.pinged.ledger.breakdown.BreakdownRead
import my.pinged.ledger.breakdown.BreakdownScreenContent
import my.pinged.ledger.breakdown.LineKey
import my.pinged.ledger.detail.DELETE
import my.pinged.ledger.detail.DetailRead
import my.pinged.ledger.detail.DetailScreenContent
import my.pinged.ledger.entry.CategoryChoice
import my.pinged.ledger.entry.ChosenBy
import my.pinged.ledger.entry.DELETE_PAYMENT
import my.pinged.ledger.entry.ManualDraft
import my.pinged.ledger.entry.ManualEntryScreenContent
import my.pinged.ledger.entry.ManualEntryState
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.LedgerItem
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerScreenContent
import my.pinged.ledger.home.MonthSummary
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import my.pinged.read.LedgerAccess
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The payback display of #392 drawn against its boards: the Spending list
 * with a payback and its bill (`PaybackList`, at 390x844 and at CI's 320x640,
 * `PaybackList320`), and with the bill left out (`PaybackListLeftOut`); a
 * bill's detail (`DetailBill`, `DetailBillExcluded`) and Not a payment's
 * sheet on it (`NotPaymentBill`); Breakdown's Yuen Kee opened
 * (`BreakdownPayback`); and a typed bill's edit screen (`CashBill`) and its
 * Delete (`DeleteBill`). The README's September scenario, and its
 * typed-by-hand August. Constructed data only: a golden is a picture of its
 * input, and the repository is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class PaybackDisplayRenderTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val september = YearMonth.of(2026, 9)

    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
    private val groceries = Category(id = 2L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1)
    private val transport = Category(id = 3L, name = "Transport", iconKey = "car", sortOrder = 2)
    private val uncategorized = Category(id = 16L, name = "Uncategorized", iconKey = "circle-dashed", sortOrder = 15, isProtected = true)

    private fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun row(
        id: Long,
        name: String,
        sen: Long,
        day: LocalDate,
        hour: Int,
        category: Category,
        source: String?,
        direction: Direction = Direction.EXPENSE,
        bill: RowBill? = null,
        paidBackSen: Long = 0L,
        excluded: Boolean = false,
        typed: Boolean = false,
    ): FeedRow {
        val txn = ledgerTxn(
            sen,
            occurredAt = at(day, hour, 0),
            categoryId = category.id,
            isExcluded = excluded,
            exclusionReason = ExclusionReason.USER.takeIf { excluded },
            id = id,
            rawCaptureId = if (typed) null else 7_000L + id,
        ).copy(
            direction = direction,
            merchantRaw = name.uppercase(),
            merchantDisplay = name,
            merchantKey = name.uppercase(),
            sourceLabel = source,
            billTxnId = bill?.id,
        )
        return FeedRow(txn, txn.merchantKey, name, bill, paidBackSen)
    }

    private val sep16 = LocalDate.of(2026, 9, 16)
    private val sep17 = LocalDate.of(2026, 9, 17)
    private val sep19 = LocalDate.of(2026, 9, 19)
    private val sep22 = LocalDate.of(2026, 9, 22)

    private fun yuenKeeBill(counts: Boolean = true) =
        RowBill(id = 5L, createdAt = at(SEP_18, 20, 47), typed = false, name = "Yuen Kee", localDate = LocalDates.of(SEP_18), counts = counts)

    private fun yuenKee(leftOut: Boolean = false) =
        row(5L, "Yuen Kee", 9_600L, SEP_18, 20, food, "Maybank MAE", paidBackSen = if (leftOut) 0L else 6_400L, excluded = leftOut)

    private fun arjun(bill: RowBill) = row(52L, "Arjun Nair", 3_200L, SEP_20, 13, uncategorized, "Touch 'n Go", Direction.REFUND, bill)
    private fun meiLing(bill: RowBill) = row(51L, "Mei Ling Tan", 3_200L, SEP_20, 12, uncategorized, "Maybank MAE", Direction.REFUND, bill)

    private fun list(firstIndex: Int, days: List<Pair<LocalDate, Pair<Long, List<FeedRow>>>>, total: Long) {
        val items = days.flatMap { (day, rows) ->
            listOf<LedgerItem>(LedgerItem.DayHeader(LocalDates.of(day), rows.second.first().txn.id, listOf(CurrencyTotal("MYR", rows.first)))) +
                rows.second.map { LedgerItem.Row.of(it) }
        }
        val feed = flowOf(PagingData.from(items, LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))))
        draw {
            LedgerScreenContent(
                items = feed.collectAsLazyPagingItems(),
                read = LedgerRead(
                    summary = MonthSummary(september, listOf(CurrencyTotal("MYR", total)), emptyList(), trustworthy = true, sources = null),
                    categories = listOf(food, groceries, transport, uncategorized),
                    uncategorizedId = uncategorized.id,
                    awaitingReview = 4,
                ),
                storageUnavailable = false,
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                onOpenSettings = {},
                picked = september,
                heard = "HEARD <5 MIN",
            )
        }
        // Scrolled past the summary to the first day drawn, as the boards are.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(firstIndex)
        compose.waitForIdle()
    }

    private val tealive = row(40L, "Tealive", 890L, SEP_20, 15, food, "GrabPay")
    private val mydin = row(30L, "Mydin USJ", 4_160L, sep19, 11, groceries, "Maybank MAE")
    private val rapid = row(20L, "Rapid KL", 540L, SEP_18, 8, transport, "Touch 'n Go")
    private val grab = row(10L, "Grab", 2_480L, sep17, 9, transport, "GrabPay")
    private val kandar = row(9L, "Restoran Nasi Kandar", 3_700L, sep16, 13, food, "Touch 'n Go")

    // Below the board's last day, so the list has the length to scroll to its first.
    private val below = listOf(
        LocalDate.of(2026, 9, 15) to (980L to listOf(row(8L, "ZUS Coffee", 980L, LocalDate.of(2026, 9, 15), 9, food, "GrabPay"))),
        LocalDate.of(2026, 9, 14) to (1_240L to listOf(row(7L, "Family Mart", 1_240L, LocalDate.of(2026, 9, 14), 18, groceries, "Touch 'n Go"))),
        LocalDate.of(2026, 9, 13) to (2_150L to listOf(row(6L, "Lotus’s Kepong", 2_150L, LocalDate.of(2026, 9, 13), 11, groceries, "Maybank MAE"))),
    )

    @Test fun paybackList() {
        val bill = yuenKeeBill()
        list(
            FIRST_DAY,
            listOf(
                SEP_20 to (890L to listOf(tealive, arjun(bill), meiLing(bill))),
                sep19 to (4_160L to listOf(mydin)),
                SEP_18 to (3_740L to listOf(yuenKee(), rapid)),
                sep17 to (2_480L to listOf(grab)),
                sep16 to (3_700L to listOf(kandar)),
            ) + below,
            total = 284_730L,
        )
        capture("payback_list")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun paybackList320() {
        val bill = yuenKeeBill()
        val sriMelayu = RowBill(8L, at(LocalDate.of(2026, 9, 8), 20, 0), typed = false, "Restoran Sri Melayu Masakan Kampung Baru", LocalDates.of(LocalDate.of(2026, 9, 8)), counts = true)
        list(
            FIRST_DAY,
            listOf(
                sep22 to (1_320L to listOf(row(60L, "Grab", 1_320L, sep22, 18, transport, "GrabPay"), row(59L, "Farid", 3_200L, sep22, 9, uncategorized, "Maybank MAE", Direction.REFUND, sriMelayu))),
                SEP_20 to (890L to listOf(tealive, arjun(bill), meiLing(bill))),
                SEP_18 to (3_740L to listOf(yuenKee(), rapid)),
            ),
            total = 284_730L,
        )
        capture("payback_list_320")
    }

    @Test fun paybackListLeftOut() {
        val bill = yuenKeeBill(counts = false)
        list(
            FIRST_DAY,
            listOf(
                SEP_20 to (-5_510L to listOf(tealive, arjun(bill), meiLing(bill))),
                sep19 to (4_160L to listOf(mydin)),
                SEP_18 to (540L to listOf(yuenKee(leftOut = true), rapid)),
                sep17 to (2_480L to listOf(grab)),
                sep16 to (3_700L to listOf(kandar)),
            ) + below,
            total = 275_130L,
        )
        capture("payback_list_left_out")
    }

    private fun detail(leftOut: Boolean = false, removing: Boolean = false) {
        val bill = yuenKee(leftOut)
        val paybacks = listOf(meiLing(yuenKeeBill(!leftOut)), arjun(yuenKeeBill(!leftOut)))
        val detailRow = DetailRow(
            bill,
            food.name,
            "Maybank2u",
            "You have paid RM96.00 to Restoran Yuen Kee Home Town Cafe",
            null,
            "DUITNOW-PAID-V1",
            7,
        )
        draw {
            DetailScreenContent(
                DetailRead(detailRow, ChooserRead(categories = listOf(food)), paybacks = paybacks),
                ledger = LedgerAccess.Readable,
                onBack = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
                zone = zone,
            )
        }
        if (removing) {
            compose.onNodeWithText(DELETE).performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    @Test fun detailBill() {
        detail()
        capture("detail_bill")
    }

    @Test fun detailBillExcluded() {
        detail(leftOut = true)
        capture("detail_bill_excluded")
    }

    @Test fun notPaymentBill() {
        detail(removing = true)
        capture("not_payment_bill")
    }

    @Test fun breakdownPayback() {
        val yuenKeeKey = LineKey.of("YUEN KEE")
        fun line(key: String?, name: String?, sen: Long, count: Int) = BreakdownLine(key, name, "MYR", sen, count)
        val rows = listOf(
            yuenKee(),
            row(4L, "Yuen Kee", 1_200L, LocalDate.of(2026, 9, 12), 20, food, "Maybank MAE"),
            row(3L, "Yuen Kee", 950L, LocalDate.of(2026, 9, 10), 13, food, "Touch ’n Go"),
        )
        val read = BreakdownRead(
            category = food,
            month = september,
            today = LocalDate.of(2026, 9, 30),
            notCaptured = 0,
            lines = listOf(
                line("FOODPANDA", "foodpanda", 18_690L, 9),
                line("YUEN KEE", "Yuen Kee", 15_200L, 14),
                line("RESTORAN SRI MELAYU", "Restoran Sri Melayu Masakan Kampung Baru", 9_980L, 6),
                line(null, null, 9_600L, 7),
                line("ZUS COFFEE", "ZUS Coffee", 9_240L, 8),
                line("TEALIVE", "Tealive", 7_650L, 5),
                line("RESTORAN NASI KANDAR", "Restoran Nasi Kandar", 7_140L, 2),
                line("NASI LEMAK ANTARABANGSA", "Nasi Lemak Antarabangsa", 5_250L, 1),
                line("MCDONALDS", "McDonald’s", 3_990L, 3),
                line("DOMINOS", "Domino’s", -2_490L, 1),
            ),
            keptOut = listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 4_500L)),
            firstHeld = 3L,
            rows = mapOf(yuenKeeKey to rows),
            chooser = ChooserRead(categories = listOf(food)),
        )
        draw {
            BreakdownScreenContent(
                month = september,
                read = read,
                expanded = setOf(yuenKeeKey),
                storageUnavailable = false,
                onBack = {},
                onGone = {},
                onToggle = {},
                categories = FakeCategoryActions(),
                merchants = FakeMerchantActions(),
            )
        }
        capture("breakdown_payback")
    }

    private val aug15: LocalDate = LocalDate.of(2026, 8, 15)

    private fun typedBill(deleting: Boolean = false) {
        val stored = ManualDraft(amountSen = 6_000L, merchant = "Warung Pak Din", day = aug15, category = null)
        val bill = RowBill(70L, at(aug15, 20, 0), typed = true, "Warung Pak Din", LocalDates.of(aug15), counts = true)
        val paybacks = listOf(
            row(71L, "Daniel Lim", 2_000L, LocalDate.of(2026, 8, 16), 10, uncategorized, "Maybank MAE", Direction.REFUND, bill),
            row(72L, "Siti Hajar", 2_000L, LocalDate.of(2026, 8, 17), 12, uncategorized, null, Direction.REFUND, bill, typed = true),
        )
        draw {
            ManualEntryScreenContent(
                state = ManualEntryState(
                    draft = stored.copy(amountSen = 6_600L, category = CategoryChoice(food.id, ChosenBy.TAPPED)),
                    categories = listOf(food, transport, groceries),
                    today = LocalDate.of(2026, 8, 30),
                    editing = 70L,
                    loaded = stored,
                    paybacks = paybacks,
                    pointers = 2,
                ),
                onAmount = {},
                onMerchant = {},
                onNote = {},
                onSave = {},
                onBack = {},
                onSaved = {},
            )
        }
        if (deleting) {
            compose.onNodeWithText(DELETE_PAYMENT).performScrollTo().performClick()
            compose.waitForIdle()
        }
    }

    @Test fun cashBill() {
        typedBill()
        capture("cash_bill")
    }

    @Test fun deleteBill() {
        typedBill(deleting = true)
        capture("delete_bill")
    }

    private fun draw(content: @Composable () -> Unit) {
        compose.setContent { PingedTheme { content() } }
    }

    /**
     * Every window, so a sheet is drawn over the screen it opens on, into
     * the committed golden for [name], from the module directory as
     * `BreakdownRenderTest`'s `golden` resolves it.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    private companion object {
        /** The feed's first day header: past the month's summary, which is the list's first item. */
        const val FIRST_DAY = 1
    }
}
