package my.pinged.ledger.day

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.LocalDates
import my.pinged.data.dao.FeedRow
import my.pinged.data.entity.Category
import my.pinged.ledger.MERCHANT
import my.pinged.ledger.home.CATEGORY_CHIP
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.NAME_HEADING
import my.pinged.ledger.home.PICKER_HEADING
import my.pinged.ledger.home.RecordingOpenings
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * A row on Day (#377): its chip opens the chooser over the screen, its long
 * press the merchant sheet, and its tap goes to the row openings. What the
 * chooser and sheet do once open is `RowHostTest`'s; that the holder's
 * writes land is `DayScreenTest`'s, on a device.
 */
@RunWith(AndroidJUnit4::class)
class DayRowsTest {
    @get:Rule val compose = createComposeRule()

    private val recording = RecordingOpenings()

    private fun draw() {
        val day = LocalDate.of(2026, 9, 25)
        val groceries = Category(id = 2L, name = "Groceries", iconKey = "shopping-basket", sortOrder = 0)
        val uncategorized = Category(id = 16L, name = "Uncategorized", iconKey = "circle-dashed", sortOrder = 15, isProtected = true)
        compose.setContent {
            PingedTheme {
                DayScreenContent(
                    date = day,
                    read = DayRead(
                        rows = listOf(FeedRow(ledgerTxn(1_840L, localDate = LocalDates.of(day), categoryId = uncategorized.id, id = 7L), "WARUNG PAK ALI", MERCHANT)),
                        heading = DayHeading(total = "RM18.40", keptOut = null, notWatching = false, captureLine = "1 PAYMENT"),
                        chooser = ChooserRead(listOf(groceries, uncategorized), uncategorized.id),
                    ),
                    storageUnavailable = false,
                    onBack = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(publishesSheet = true),
                    openings = recording.openings,
                    today = day.plusDays(20),
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
}
