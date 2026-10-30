package my.pinged.ledger.merchantmonth

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.data.LocalDates
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.ledgerTxn
import my.pinged.parse.Direction
import my.pinged.ui.test.HeldClock
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Merchant month (#344) at CI's 320x640, this module's Robolectric screen:
 * foodpanda's September across Food & Drinks and Groceries, a refund among
 * its rows, a payment awaiting review kept out; the same month untrusted;
 * and the read in flight. Constructed data only: a golden is a picture of
 * its input, and the repository is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MerchantMonthRenderTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
    private val groceries = Category(id = 2L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 1)

    private fun row(id: Long, sen: Long, day: Int, category: Category, direction: Direction = Direction.EXPENSE) = FeedRow(
        ledgerTxn(sen, localDate = LocalDates.of(september.atDay(day)), categoryId = category.id, id = id).copy(
            merchantRaw = "FOODPANDA",
            merchantDisplay = "foodpanda",
            merchantKey = "FOODPANDA",
            direction = direction,
        ),
        "FOODPANDA",
        "foodpanda",
    )

    // 42.50 + 38.90 + 61.20 + 27.80 - 15.00 + 32.40 = 187.80 over six rows.
    private fun read(notCaptured: Int = 0) = MerchantMonthRead(
        identity = "FOODPANDA",
        name = "foodpanda",
        month = september,
        today = LocalDate.of(2026, 10, 30),
        notCaptured = notCaptured,
        totals = listOf(MerchantTotal("FOODPANDA", "foodpanda", "MYR", 18_780L, 6)),
        keptOut = listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 4_500L)),
        firstHeld = 9L,
        rows = listOf(
            row(6L, 3_240L, day = 28, category = food),
            row(5L, 1_500L, day = 22, category = food, direction = Direction.REFUND),
            row(4L, 2_780L, day = 19, category = groceries),
            row(3L, 6_120L, day = 12, category = groceries),
            row(2L, 3_890L, day = 7, category = food),
            row(1L, 4_250L, day = 2, category = food),
        ),
        chooser = ChooserRead(categories = listOf(food, groceries)),
    )

    @Test fun theMonth() = capture("merchant_month", read())

    @Test fun anUntrustedMonth() = capture("merchant_month_untrusted", read(notCaptured = 1))

    @Test fun theReadInFlight() {
        HeldClock(compose).apply {
            draw { Screen(null) }
            at(READING_DELAY_MILLIS + 40)
        }
        compose.onRoot().captureRoboImage(golden("merchant_month_not_read_yet"))
    }

    private fun capture(name: String, read: MerchantMonthRead) {
        compose.setContent { PingedTheme { Screen(read) } }
        compose.onRoot().captureRoboImage(golden(name))
    }

    @Composable
    private fun Screen(read: MerchantMonthRead?) {
        MerchantMonthScreenContent(
            month = september,
            read = read,
            storageUnavailable = false,
            onBack = {},
            categories = FakeCategoryActions(),
            merchants = FakeMerchantActions(),
            today = LocalDate.of(2026, 10, 30),
        )
    }

    /**
     * The committed golden for [name], from the module directory: it must agree
     * with `roborazzi.outputDir` in this module's build file, as
     * `BreakdownRenderTest`'s `golden` does.
     */
    private fun golden(name: String): String = "src/test/screenshots/$name.png"
}
