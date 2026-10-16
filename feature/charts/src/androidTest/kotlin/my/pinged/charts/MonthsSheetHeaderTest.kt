package my.pinged.charts

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import my.pinged.ui.theme.CLOSE
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #164: `design/Months.dc.html`'s header, the serif "Jump to" and a close X
 * that dismisses. The sheet's edge is `SheetChromeTest`'s, in the module that
 * can draw every sheet; this is only what `MonthsSheet` passes it.
 */
class MonthsSheetHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theMonthsSheetHasItsTitleAndClosesFromItsX() {
        var dismissed = false
        compose.setContent {
            PingedTheme { MonthsSheet(items = null, onPick = {}, onDismiss = { dismissed = true }) }
        }
        compose.onNodeWithText("Jump to").assertExists()
        compose.onNodeWithContentDescription(CLOSE).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("The months sheet's X did not dismiss it", dismissed)
    }
}
