package my.pinged.charts

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.NamedSource
import my.pinged.data.SourcesLine
import my.pinged.data.entity.CaptureSource
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a tap on the sources line lands (#365, #368): each run that opens
 * something is its own target, and the words between them open nothing. A
 * line with no app read and an unreadable one holds both kinds, so a tap on
 * the warning's words must not open Capture sources, nor compete with
 * `GRABPAY`'s link.
 *
 * On a device because each tap is a position in the text's own layout.
 */
@RunWith(AndroidJUnit4::class)
class SourcesLineTapTest {

    @get:Rule
    val compose = createComposeRule()

    private var sources = 0
    private val unread = mutableListOf<NamedSource>()

    /**
     * `CANNOT` two lines below either link: Compose widens a link's touch
     * area to 48dp, which at the line's 16dp reaches the line under it, so a
     * word there is the link's by design and says nothing about this.
     */
    @Test fun onlyNoAppIsReadYetOpensCaptureSources() {
        draw()
        val layout = layout()
        val text = layout.layoutInput.text.text
        val cannot = layout.getLineForOffset(text.indexOf("CANNOT"))
        val links = maxOf(layout.getLineForOffset(text.indexOf(" GRABPAY")), layout.getLineForOffset(text.indexOf("YET.")))
        assertTrue("CANNOT is within a link's touch area, so its tap cannot tell", cannot >= links + 2)

        tapOn("CANNOT")
        assertEquals("A tap on the warning's words opened Capture sources", 0, sources)
        assertEquals("A tap on the warning's words opened Could not read", emptyList<NamedSource>(), unread)

        tapOn("NO APP IS READ YET")
        assertEquals("NO APP IS READ YET did not open Capture sources", 1, sources)
        assertEquals("NO APP IS READ YET opened Could not read", emptyList<NamedSource>(), unread)
    }

    @Test fun grabPayOpensOnlyCouldNotRead() {
        draw()

        tapOn("GRABPAY")

        assertEquals("GRABPAY did not open Could not read for GrabPay", listOf(NamedSource(GRAB, "GRABPAY")), unread)
        assertEquals("GRABPAY opened Capture sources as well", 0, sources)
    }

    private fun draw() {
        val line = SourcesLine.of(
            sources = listOf(CaptureSource(GRAB, "GrabPay", enabled = true, firstSeenAt = 0L)),
            rulePackages = emptySet(),
            paidFrom = emptySet(),
            byHand = false,
            packLabels = emptyMap(),
        )
        compose.setContent {
            PingedTheme {
                // Narrow, so the warning wraps to lines no link's touch area reaches.
                SourcesLineText(line, SourcesLineActions(openSources = { sources++ }, openUnread = { unread += it }), Modifier.width(120.dp))
            }
        }
        compose.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(LINE).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    /** A tap on the middle of [words], where the line lays them out. */
    private fun tapOn(words: String) {
        val node = compose.onNodeWithText(LINE)
        val layout = layout()
        val start = layout.layoutInput.text.text.indexOf(words)
        val box = layout.getBoundingBox(start + words.length / 2)
        node.performTouchInput { click(box.center) }
        compose.waitForIdle()
    }

    private companion object {
        const val GRAB = "com.grabtaxi.passenger"
        const val LINE = "NO APP IS READ YET. GRABPAY IS ON BUT PINGED CANNOT READ IT YET"
    }
}
