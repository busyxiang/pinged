package my.pinged.ledger.teach

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import my.pinged.data.dao.UnreadCapture
import my.pinged.ledger.PIXEL_WINDOW
import my.pinged.parse.TaughtTemplate
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #272: Teach's save bar sits under the content, and is pinned to the bottom
 * only once the content is taller than the screen. Bounds, so on a device.
 */
class TeachSaveBarTest {
    @get:Rule val compose = createComposeRule()

    private fun draw(width: Dp, height: Dp, text: String) = compose.setContent {
        PingedTheme {
            Box(Modifier.requiredSize(width, height).testTag(SCREEN)) {
                val capture = UnreadCapture(
                    id = 9L,
                    sourcePackage = "my.com.hongleong.connect",
                    postedAt = 1790730840000L,
                    title = null,
                    text = text,
                    bigText = null,
                    label = "Hong Leong Connect",
                )
                TeachContent(
                    state = TeachState(loaded = true, capture = capture, selection = TeachSelection(TaughtTemplate.words(null, text, null))),
                    onBack = {},
                    onTap = {},
                    onNoMerchant = {},
                    onDirection = {},
                    onSave = {},
                    zone = ZoneId.of("Asia/Kuala_Lumpur"),
                )
            }
        }
    }

    /** A Pixel-sized window and the artboard's one-line capture: the bar directly under the card. */
    @Test fun underShortContentTheBarFollowsIt() {
        draw(PIXEL_WINDOW.width, PIXEL_WINDOW.height, SHORT)
        val card = bounds(TEACH_CARD)
        val bar = bounds(TEACH_SAVE_BAR)
        val gap = with(compose.density) { (bar.top - card.bottom).toDp() }
        assertTrue("The save bar is $gap below the content", gap >= (-0.5).dp && gap <= 0.5.dp)
    }

    /** CI's width and a short window under a long capture: the bar pinned, Save on screen, the content scrolling. */
    @Test fun underTallContentTheBarIsPinnedAndSaveReachable() {
        draw(320.dp, 480.dp, List(12) { SHORT }.joinToString(" "))
        val screen = bounds(SCREEN)
        val bar = bounds(TEACH_SAVE_BAR)
        assertEquals("The save bar's bottom, px, against the screen's", screen.bottom, bar.bottom, 1f)
        compose.onNodeWithText(SAVE).assertIsDisplayed()
        val scroller = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
        assertTrue("The content does not scroll", scroller.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).getBoundsInRoot().let { r ->
        with(compose.density) { Rect(r.left.toPx(), r.top.toPx(), r.right.toPx(), r.bottom.toPx()) }
    }

    private companion object {
        const val SCREEN = "teach-test-screen"
        const val SHORT = "Transaksi berjaya. RM88.00 telah ditolak dari akaun anda 1234."
    }
}
