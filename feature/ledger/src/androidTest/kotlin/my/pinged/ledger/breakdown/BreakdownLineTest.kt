package my.pinged.ledger.breakdown

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.BreakdownLine
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth

/**
 * A Breakdown line whose name is far longer than the screen keeps its count
 * and amount whole and in place (#342), measured on the device's own text,
 * which Robolectric's is not: at CI's 320dp the name's ellipsis ends before
 * the count, and the amount ends before the chevron's column.
 */
@RunWith(AndroidJUnit4::class)
class BreakdownLineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aLongNameEndsBeforeTheCountAndTheAmountKeepsItsPlace() {
        val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
        compose.setContent {
            PingedTheme {
                BreakdownScreenContent(
                    month = YearMonth.of(2026, 9),
                    read = BreakdownRead(
                        category = food,
                        month = YearMonth.of(2026, 9),
                        today = LocalDate.of(2026, 10, 30),
                        notCaptured = 0,
                        lines = listOf(BreakdownLine("LONG", "Restoran Nasi Kandar Pelita Jalan Ampang Kuala Lumpur Cawangan Kedua", "MYR", 123_450L, 123)),
                        keptOut = emptyList(),
                        firstHeld = null,
                        rows = emptyMap(),
                        chooser = ChooserRead(categories = listOf(food)),
                    ),
                    expanded = emptySet(),
                    storageUnavailable = false,
                    onBack = {},
                    onGone = {},
                    onToggle = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                )
            }
        }

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val name = compose.onNodeWithText("Restoran", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val count = compose.onNodeWithText("123×", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val amount = compose.onNodeWithText("1,234.50", useUnmergedTree = true).getUnclippedBoundsInRoot()
        // The screen's 20dp gutter, then the chevron's 14dp and the 6dp before it.
        val chevronStart = root.right - 20.dp - 14.dp - 6.dp

        assertTrue("The name runs into the count: $name, $count", name.right <= count.left)
        assertTrue("The count runs into the amount: $count, $amount", count.right <= amount.left)
        assertTrue("The amount is pushed past the chevron: $amount, chevron from $chevronStart", amount.right <= chevronStart + 0.5.dp)
    }
}
