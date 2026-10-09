package my.pinged

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

/**
 * The top-bar control opens settings, with the allow-list a row inside it as
 * `Settings.dc.html` draws it. One control moves; no new navigation.
 *
 * Asserts on `"SETTINGS"`, the control's visible text, not a
 * `contentDescription`: `LedgerScreen`'s `TopBar` labels this control the
 * same way every other text-labelled control on that screen is labelled --
 * with the text itself, and no separate accessibility string to drift from
 * it. `onNodeWithContentDescription` would find nothing here and fail for a
 * reason unrelated to navigation.
 */
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theTopBarControlReachesSettingsAndSettingsReachesSources() {
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
}
