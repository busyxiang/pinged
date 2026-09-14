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
 * Getting to the allow-list and back, through the real `NavDisplay`.
 *
 * [LaunchTest] drives the lifecycle and cannot touch a Compose control, so it
 * can tell a working route from a decorative one no better than it can tell a
 * restored back stack from a fresh one. This does both halves of the route.
 *
 * `SourcesScreen`'s chevron is the subject rather than the system gesture,
 * because the gesture was never in doubt: `NavDisplay` wires it itself. What is
 * in doubt is that the screen carries a way out a user can see.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theChevronPopsTheAllowListAndComesBackToTheLedger() {
        // A cold process is what the Activity opens against; `Databases`
        // memoizes for the life of the process and other classes in this APK
        // run first.
        Databases.reset()

        compose.onNodeWithText("SOURCES").performClick()
        val arrived = runCatching {
            compose.waitUntil(TIMEOUT) { compose.countOf("Capture sources") == 1 }
        }.isSuccess
        assertTrue(
            "The ledger's SOURCES control did not reach the allow-list, so " +
                "nothing below this line is about the way back",
            arrived,
        )

        compose.onNodeWithContentDescription(BACK_DESCRIPTION).performClick()

        // `runCatching`, so the failure is this assertion and its message
        // rather than a bare timeout out of `waitUntil`.
        val left = runCatching {
            compose.waitUntil(TIMEOUT) { compose.countOf("Capture sources") == 0 }
        }.isSuccess
        assertTrue(
            "The chevron did not pop the allow-list. It is the only way off " +
                "this screen a user can see -- the system gesture is not drawn " +
                "anywhere -- so a chevron that takes the tap and does nothing " +
                "is the control this screen's own note records removing",
            left,
        )
        // The ledger's own control, so this is the ledger and not an empty
        // NavDisplay: an entry that failed to render would also show no
        // allow-list.
        compose.onNodeWithText("SOURCES").assertExists(
            "The allow-list went and the ledger did not arrive",
        )
    }

    /** Nodes with exactly [text], settled first. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countOf(
        text: String,
    ): Int {
        waitForIdle()
        return onAllNodesWithText(text).fetchSemanticsNodes().size
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
