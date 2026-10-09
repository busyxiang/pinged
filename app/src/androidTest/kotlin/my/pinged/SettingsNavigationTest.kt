package my.pinged

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import org.junit.Rule
import org.junit.Test

/**
 * The SETTINGS tab of the bottom bar opens settings, with the allow-list a row
 * inside it as `Settings.dc.html` draws it, and the bar is drawn on the two tab
 * roots and nowhere else.
 *
 * Asserts on `"SETTINGS"`, the tab's visible text, not a `contentDescription`:
 * the bar labels its tabs the way every other text-labelled control here is
 * labelled -- with the text itself, and no separate accessibility string to
 * drift from it.
 */
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theSettingsTabReachesSettingsAndSettingsReachesSources() {
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("Capture sources").assertIsDisplayed()
        compose.onNodeWithText("Capture sources").performClick()
        // The Sources screen's own header line: "Capture sources" is drawn on
        // both screens, so asserting it passed a click that went nowhere.
        compose.onNodeWithText("TEXT IS STORED ONLY FOR THESE", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Replace everything from a backup").assertDoesNotExist()
    }

    /** #50: the row is wired to the list, whose empty state is its own text. */
    @Test fun settingsReachesTheLearnedMerchantsList() {
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("Learned merchants").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Nothing taught yet", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("PAYMENTS", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Replace everything from a backup").assertDoesNotExist()
    }

    @Test fun theBarIsDrawnOnTheLedgerAndOnSettingsWithTheShownTabSelected() {
        compose.onNodeWithText("SPENDING").assertIsSelected()

        compose.onNodeWithText("SETTINGS").performClick()

        compose.onNodeWithText("SETTINGS").assertIsSelected()
        compose.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test fun theBarIsGoneOverAPushedScreen() {
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("Capture sources").performClick()
        compose.onNodeWithText("TEXT IS STORED ONLY FOR THESE", substring = true).assertIsDisplayed()

        compose.onNodeWithText("SPENDING").assertDoesNotExist()
        compose.onNodeWithText("SETTINGS").assertDoesNotExist()
    }

    @Test fun theSpendingTabReturnsToTheLedger() {
        compose.onNodeWithText("SETTINGS").performClick()
        compose.onNodeWithText("Capture sources").assertIsDisplayed()

        compose.onNodeWithText("SPENDING").performClick()

        compose.onNodeWithText("SPENDING").assertIsSelected()
        compose.onNodeWithText("Capture sources").assertDoesNotExist()
    }
}
