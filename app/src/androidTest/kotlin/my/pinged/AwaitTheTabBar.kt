package my.pinged

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import my.pinged.ui.test.boundedWaitUntil

/**
 * Waits for the tab bar, with every tab drawn once. A test calls it after
 * launching `MainActivity` and before its first tap or read of the app.
 *
 * On an install without notification access, which CI's emulator and the
 * shared one are, `AppStatus` starts not knowing whether first run shows
 * onboarding, and the window is bare Paper until its first sample has run
 * the probe (a database open), the backup count and `onboards`. That runs on
 * its own clock: `waitForIdle()` and a node lookup can both come back before
 * it, and a tap on "SETTINGS" then finds no node. On CI it found none 0.49s
 * into `AlertSettingsChannelTest`.
 *
 * [timeoutMillis] is a cold SQLCipher open on a loaded emulator; `OpenTest`
 * measures it. `TabBarWaitTest` is the falsifying test.
 */
internal fun ComposeTestRule.awaitTheTabBar(timeoutMillis: Long = 30_000L) {
    boundedWaitUntil(timeoutMillis, "the tab bar") {
        TABS.all { onAllNodesWithText(it).fetchSemanticsNodes().size == 1 }
    }
}

/** The labels the bar draws, as the tests that tap them spell them. */
private val TABS = listOf("SPENDING", "CHARTS", "SETTINGS")
