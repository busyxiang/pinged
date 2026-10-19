package my.pinged.ledger.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ui.theme.NOT_READ_YET
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #237: the delete sheet opened before settings' first read lands. Loading is
 * not unreadable, and telling a user their data cannot be read while it is
 * simply still being counted is the riskiest of the defaults #237 lists.
 */
@RunWith(AndroidJUnit4::class)
class WipeSheetLoadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun countsNotReadYetAreDashesNotUnreadable() {
        compose.setContent {
            Column { WipeSheetBody(counts = null, loaded = false, onConfirm = {}, onExportFirst = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("cannot read", substring = true).assertDoesNotExist()
        for (label in listOf("Transactions", "Original notifications", "Merchants you taught", "Months of history")) {
            compose.onNode(hasText(label) and hasText(NOT_READ_YET)).assertExists()
        }
    }

    /** The other side: a read that landed with no counts still says why. */
    @Test fun countsThatCouldNotBeReadSaySo() {
        compose.setContent {
            Column { WipeSheetBody(counts = null, loaded = true, onConfirm = {}, onExportFirst = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("Pinged cannot count what it cannot read.").assertExists()
    }
}
