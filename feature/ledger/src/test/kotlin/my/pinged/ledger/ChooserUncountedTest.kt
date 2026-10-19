package my.pinged.ledger

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.TeachOffer
import my.pinged.ledger.home.UNCOUNTED_LINE
import my.pinged.ledger.home.fixLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #237: a ledger that could not count the merchant's past payments is not
 * one with none of them. The chooser says it could not count, rather than
 * "AND FIX THE 0 PAST ONES".
 */
@RunWith(AndroidJUnit4::class)
class ChooserUncountedTest {
    @get:Rule val compose = createComposeRule()

    private val groceries = Category(2, "Groceries", "shopping-basket", 1, seedName = "Groceries")

    private fun chooser(preview: RetroPreview?) = compose.setContent {
        CategoryPicker(
            categories = listOf(groceries),
            subject = ChooserSubject("TNG*99SPEEDMART", "32.00"),
            teach = TeachOffer(merchant = "99 Speedmart", startsOn = true),
            initial = groceries.id,
            preview = { preview },
            onSave = { _, _ -> },
            onDismiss = {},
        )
    }

    @Test fun aPreviewThatCouldNotBeCountedSaysSo() {
        chooser(preview = null)
        compose.waitForIdle()
        compose.onNodeWithText(fixLine(0)).assertDoesNotExist()
        compose.onNodeWithText(UNCOUNTED_LINE).assertExists()
    }

    @Test fun aCountedPreviewIsDrawn() {
        chooser(preview = RetroPreview(3, emptyList()))
        compose.waitForIdle()
        compose.onNodeWithText(fixLine(3)).assertExists()
    }
}
