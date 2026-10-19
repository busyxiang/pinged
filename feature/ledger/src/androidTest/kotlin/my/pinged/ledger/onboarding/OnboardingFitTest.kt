package my.pinged.ledger.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import my.pinged.ledger.PIXEL_WINDOW
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #272: Onboarding's step 1 on a Pixel and on CI's screen. On a device, not
 * under Robolectric, because both read the device's type.
 */
class OnboardingFitTest {
    @get:Rule val compose = createComposeRule()

    private var size by mutableStateOf(PIXEL_WINDOW)

    private fun draw() = compose.setContent {
        PingedTheme { Box(Modifier.requiredSize(size)) { OnboardingScreen(onAllowAccess = {}) } }
    }

    /** One line, or two split after the `·`: never a word alone on a line. */
    @Test fun theFooterLineBreaksOnlyAtTheSeparator() {
        draw()
        for (width in listOf(320.dp, 411.dp)) {
            size = PIXEL_WINDOW.copy(width = width)
            compose.waitForIdle()
            val layout = footerLayout()
            val text = layout.layoutInput.text.text
            val lines = (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
            assertTrue("At $width the footer is ${layout.lineCount} lines: $lines", layout.lineCount <= 2)
            if (lines.size == 2) {
                assertTrue("At $width the footer breaks inside a clause: $lines", lines[0].endsWith("·"))
            }
        }
    }

    /** The whole page in [PIXEL_WINDOW], with no scroll range. */
    @Test fun stepOneFitsAPixelWithoutScrolling() {
        draw()
        compose.waitForIdle()
        val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
        assertEquals("Step 1's scroll range on a Pixel, px", 0f, scroller.config[SemanticsProperties.VerticalScrollAxisRange].maxValue())
    }

    private fun footerLayout(): TextLayoutResult {
        val node = compose.onNodeWithText(ASKS_NEXT).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single()
    }
}
