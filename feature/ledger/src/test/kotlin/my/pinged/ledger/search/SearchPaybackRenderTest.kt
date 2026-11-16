package my.pinged.ledger.search

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import my.pinged.data.LocalDates
import my.pinged.data.SearchTotal
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.RowBill
import my.pinged.data.entity.Category
import my.pinged.ledger.bill.SEP_18
import my.pinged.ledger.bill.SEP_20
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Search's rows with paybacks and their bill, as `design/PaybackList.dc.html`
 * draws them in the list: September searched, with Arjun's and Mei Ling's
 * paybacks against Yuen Kee, its paid-back line, and a payback whose bill
 * counts nowhere (`PaybackListLeftOut`); and at CI's 320x640, where a long
 * bill's name gives way in its tag and the paid-back line stays one line
 * (`PaybackList320`). The README's September scenario; constructed data
 * only, since a golden is a picture of its input and the repository is
 * public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class SearchPaybackRenderTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val september = YearMonth.of(2026, 9)
    private val sep19 = LocalDate.of(2026, 9, 19)
    private val sep22 = LocalDate.of(2026, 9, 22)

    private val food = Category(1L, "Food & Drinks", "utensils", 0, seedName = "Food & Drinks")
    private val groceries = Category(2L, "Groceries", "shopping-basket", 1, seedName = "Groceries")
    private val uncategorized = Category(16L, "Uncategorized", "circle-dashed", 15, isProtected = true, seedName = "Uncategorized")

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun row(
        id: Long,
        name: String,
        sen: Long,
        day: LocalDate,
        hour: Int,
        category: Category,
        source: String,
        bill: RowBill? = null,
        paidBackSen: Long = 0L,
    ): FeedRow {
        val txn = ledgerTxn(sen, occurredAt = at(day, hour), categoryId = category.id, id = id, rawCaptureId = 7_000L + id).copy(
            direction = if (bill != null) Direction.REFUND else Direction.EXPENSE,
            merchantRaw = name.uppercase(),
            merchantDisplay = name,
            merchantKey = name.uppercase(),
            sourceLabel = source,
            billTxnId = bill?.id,
        )
        return FeedRow(txn, txn.merchantKey, name, bill, paidBackSen)
    }

    private fun bill(id: Long, name: String, day: LocalDate, counts: Boolean = true) =
        RowBill(id, at(day, 20), typed = false, name = name, localDate = LocalDates.of(day), counts = counts)

    private val yuenKee = bill(5L, "Yuen Kee", SEP_18)

    private fun search(rows: List<FeedRow>, total: SearchTotal) {
        compose.setContent {
            PingedTheme {
                SearchScreenContent(
                    query = "",
                    onQuery = {},
                    choice = SearchChoice(month = september),
                    read = SearchRead(
                        text = "",
                        choice = SearchChoice(month = september),
                        rows = rows,
                        totals = listOf(total),
                        chooser = ChooserRead(listOf(food, groceries, uncategorized), uncategorized.id),
                    ),
                    storageUnavailable = false,
                    onBack = {},
                    onOpenSheet = {},
                    categories = FakeCategoryActions(),
                    onExport = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun searchPayback() {
        search(
            listOf(
                row(53L, "Farid", 2_500L, sep22, 9, uncategorized, "Maybank MAE", bill(9L, "Tealive", sep19, counts = false)),
                row(52L, "Arjun Nair", 3_200L, SEP_20, 13, uncategorized, "Touch 'n Go", yuenKee),
                row(51L, "Mei Ling Tan", 3_200L, SEP_20, 12, uncategorized, "Maybank MAE", yuenKee),
                row(30L, "Mydin USJ", 4_160L, sep19, 11, groceries, "Maybank MAE"),
                row(5L, "Yuen Kee", 9_600L, SEP_18, 20, food, "Maybank MAE", paidBackSen = 6_400L),
            ),
            // Farid's counts as money received on its own day: counted, a
            // refund. Arjun's and Mei Ling's count with the bill the search found.
            SearchTotal("MYR", rowCount = 5, countedCount = 3, paymentCount = 2, netSen = 4_860L, paybackCount = 2),
        )
        capture("search_payback")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun searchPayback320() {
        val sriMelayu = bill(8L, "Restoran Sri Melayu Masakan Kampung Baru", LocalDate.of(2026, 9, 8))
        search(
            listOf(
                row(59L, "Farid", 3_200L, sep22, 9, uncategorized, "Maybank MAE", sriMelayu),
                row(52L, "Arjun Nair", 3_200L, SEP_20, 13, uncategorized, "Touch 'n Go", yuenKee),
                row(5L, "Yuen Kee", 9_600L, SEP_18, 20, food, "Maybank MAE", paidBackSen = 6_400L),
            ),
            // Mei Ling's 32.00 is in the net with its bill, though not found.
            SearchTotal("MYR", rowCount = 3, countedCount = 1, paymentCount = 1, netSen = 3_200L, paybackCount = 2),
        )
        capture("search_payback_320")
    }

    /** Into the committed golden for [name], from the module directory. */
    private fun capture(name: String) {
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }
}
