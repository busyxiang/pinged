package my.pinged

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.Databases
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * The Charts tab (#81, #86): it sits between Spending and Settings, opens on
 * the current month, and is still there after the Activity is rebuilt.
 *
 * The bar is fixed below the `NavDisplay`, not in a scroll, so its tabs are
 * tapped without `performScrollTo`, as `NavigationTest` taps SETTINGS.
 *
 * The month name alone does not say Charts is showing -- the Ledger's top bar
 * prints the same `OCTOBER 2026` -- so each wait also asks for a line only
 * Charts draws: its hero label, or its empty state's serif line.
 */
@RunWith(AndroidJUnit4::class)
class ChartsTabTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Built from the enum's own name, not from the screen's formatter, so a
     * formatter that drew `M10 2026` would not agree with itself here.
     */
    private val thisMonth: String = YearMonth.now().let { "${it.month.name} ${it.year}" }

    @Test fun theChartsTabSitsBetweenTheOthersAndOpensOnTheCurrentMonth() {
        Databases.reset()

        val left = { label: String -> compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.left }
        assertTrue(
            "The tabs are not Spending, Charts, Settings from left to right",
            left("SPENDING") < left("CHARTS") && left("CHARTS") < left("SETTINGS"),
        )

        compose.onNodeWithText("CHARTS").performClick()

        assertTrue(
            "The CHARTS tab did not open Charts on $thisMonth",
            runCatching { compose.waitUntil(TIMEOUT) { compose.showsCharts(thisMonth) } }.isSuccess,
        )
        compose.onNodeWithText("CHARTS").assertIsSelected()
    }

    @Test fun chartsIsStillShowingAfterTheActivityIsRebuilt() {
        Databases.reset()
        compose.onNodeWithText("CHARTS").performClick()
        compose.waitUntil(TIMEOUT) { compose.showsCharts(thisMonth) }

        compose.activityRule.scenario.recreate()

        assertTrue(
            "Charts did not come back after the Activity was rebuilt",
            runCatching { compose.waitUntil(TIMEOUT) { compose.showsCharts(thisMonth) } }.isSuccess,
        )
        compose.onNodeWithText("CHARTS").assertIsSelected()
    }

    /** The month is named, and a line only Charts draws is on screen. */
    private fun ComposeContentTestRule.showsCharts(month: String): Boolean {
        waitForIdle()
        fun count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size
        val chartsOnly = count("TOTAL SPENT") + count("NET SPENT") +
            count("Charts begin on the first day Pinged sees a notification.")
        return count(month) == 1 && chartsOnly == 1
    }

    private companion object {
        /** A cold SQLCipher open on a loaded emulator; `OpenTest` measures it. */
        const val TIMEOUT = 30_000L
    }
}
