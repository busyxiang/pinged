package my.pinged

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Databases
import my.pinged.ledger.sources.BACK_DESCRIPTION
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Getting to the allow-list and back, through the real `NavDisplay` -- by way
 * of settings (spec 9.5), which is where task 9 moved the only route in.
 *
 * [LaunchTest] drives the lifecycle and cannot touch a Compose control, so it
 * can tell a working route from a decorative one no better than it can tell a
 * restored back stack from a fresh one. This walks the whole route: ledger to
 * settings, settings to the allow-list, and back out through both.
 *
 * `SourcesScreen`'s chevron is one of two subjects rather than the system
 * gesture, because the gesture was never in doubt: `NavDisplay` wires it
 * itself. What is in doubt is that each screen carries a way out a user can
 * see -- the allow-list's chevron, and settings' own "Back" text.
 *
 * Text, not `onNodeWithContentDescription`, distinguishes settings from the
 * allow-list: both screens draw a "Capture sources" node -- settings as a row,
 * the allow-list as its header -- so this asserts on a phrase each screen
 * alone draws, `"Delete everything"` for settings and `"TEXT IS STORED ONLY
 * FOR THESE"` for the allow-list, rather than counting an ambiguous one.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theRouteThroughSettingsReachesTheAllowListAndBothPopCleanly() {
        // A cold process is what the Activity opens against; `Databases`
        // memoizes for the life of the process and other classes in this APK
        // run first.
        Databases.reset()

        compose.onNodeWithText("SETTINGS").performClick()
        val reachedSettings = runCatching {
            compose.waitUntil(TIMEOUT) { compose.countOf("Delete everything") == 1 }
        }.isSuccess
        assertTrue(
            "The ledger's SETTINGS control did not reach settings, so nothing " +
                "below this line is about the way to or from the allow-list",
            reachedSettings,
        )

        compose.onNodeWithText("Capture sources").performClick()
        val arrived = runCatching {
            compose.waitUntil(TIMEOUT) {
                compose.countOf("TEXT IS STORED ONLY FOR THESE", substring = true) == 1
            }
        }.isSuccess
        assertTrue(
            "Settings' \"Capture sources\" row did not reach the allow-list",
            arrived,
        )

        compose.onNodeWithContentDescription(BACK_DESCRIPTION).performClick()

        // `runCatching`, so the failure is this assertion and its message
        // rather than a bare timeout out of `waitUntil`.
        val backAtSettings = runCatching {
            compose.waitUntil(TIMEOUT) { compose.countOf("Delete everything") == 1 }
        }.isSuccess
        assertTrue(
            "The chevron did not pop the allow-list back to settings. It is " +
                "the only way off that screen a user can see -- the system " +
                "gesture is not drawn anywhere -- so a chevron that takes the " +
                "tap and does nothing is the control this screen's own note " +
                "records removing",
            backAtSettings,
        )

        compose.onNodeWithText("Back").performClick()
        val backAtLedger = runCatching {
            compose.waitUntil(TIMEOUT) { compose.countOf("SETTINGS") == 1 }
        }.isSuccess
        assertTrue(
            "Settings' own \"Back\" did not return to the ledger",
            backAtLedger,
        )
    }

    /**
     * Nodes matching [text], settled first.
     *
     * @param substring false for a node whose whole text is [text] (the
     *   ledger's and settings' single-word or single-row labels); true for
     *   `"TEXT IS STORED ONLY FOR THESE"`, which sits inside a longer line
     *   the allow-list joins with an enabled count -- an exact match there
     *   would never see it.
     */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countOf(
        text: String,
        substring: Boolean = false,
    ): Int {
        waitForIdle()
        return onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
