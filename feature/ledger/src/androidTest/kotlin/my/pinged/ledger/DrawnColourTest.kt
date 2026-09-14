package my.pinged.ledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import my.pinged.ledger.sources.DETAIL_TAG
import my.pinged.ledger.sources.PILL_TAG
import my.pinged.ledger.sources.SourceRow
import my.pinged.ledger.sources.SourcesScreenContent
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.theme.Card
import my.pinged.ledger.theme.Contrast
import my.pinged.ledger.theme.PingedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the screen is actually painted in, read back off the screen.
 *
 * `ContrastTest` guards the palette. It cannot guard **which token an element
 * is drawn in**, which lives in `SourcesScreen.kt`, a file it never opens.
 *
 * That gap was measured, not supposed: with the fix commit's own two
 * substitutions reverted -- the claim line back to `Faint` at 3.12:1, the
 * toggle outline back to `Border` at 1.35:1 -- the whole JVM suite still
 * reported BUILD SUCCESSFUL.
 *
 * So these read pixels. Antialiasing spreads a glyph across every shade between
 * the ink and the ground, and the **darkest** pixel in that spread is the ink
 * itself.
 */
class DrawnColourTest {
    @get:Rule val compose = createComposeRule()

    /**
     * A disabled, discovered source in the lower list: the row that carries
     * spec 9.6's claim, on the ground with the least contrast to spare.
     */
    private fun content() {
        compose.setContent {
            PingedTheme {
                SourcesScreenContent(
                    state = SourcesState(
                        seenNotCaptured = listOf(
                            SourceRow("com.example.probe", "Probe Bank", 3204, false),
                        ),
                        loaded = true,
                    ),
                    onToggle = { _, _ -> },
                    onBack = {},
                )
            }
        }
    }

    @Test fun theClaimAboutStoredTextIsPaintedInAReadableColour() {
        content()
        val worst = inkContrastAgainst(Card, DETAIL_TAG)
        assertTrue(
            ("The line that says what happened to your messages is painted at " +
                "%.2f:1 against the ground behind it. WCAG 1.4.3 asks %.1f:1.")
                .format(worst, Contrast.TEXT_MINIMUM),
            worst >= Contrast.TEXT_MINIMUM,
        )
    }

    /**
     * The **outline**, sampled at the node's outer columns, where nothing else can
     * be.
     *
     * Reading the whole pill would not do it: the knob sits inside the same node,
     * 18dp of a 24dp box, painted in the same colour -- so with the outline
     * reverted to the artboard's 1.35:1 `Border` the knob still supplies a 3.12:1
     * pixel and the assertion passes over a control whose boundary has vanished.
     * Measured: that is what the first version of this test did.
     */
    @Test fun theOffStateOfTheToggleIsPaintedVisibly() {
        content()
        val worst = outlineInkContrastAgainst(Card, PILL_TAG)
        assertTrue(
            ("The off state of the capture toggle is painted at %.2f:1 against " +
                "the ground behind it. WCAG 1.4.11 asks %.1f:1, and this is the " +
                "control that answers the only question this screen asks.")
                .format(worst, Contrast.COMPONENT_MINIMUM),
            worst >= Contrast.COMPONENT_MINIMUM,
        )
    }

    /**
     * The contrast of the ink the tagged node painted.
     *
     * **The maximum ratio, not the minimum.** The first version took the minimum
     * and reported 1.01:1 for a line genuinely at 4.76:1: antialiasing means the
     * *least* contrasting painted pixel is always the one a hair from the ground,
     * a number about the edge of a letter rather than the colour anyone reads. The
     * ink is the pixel furthest from the ground.
     *
     * A node that painted nothing but its background fails explicitly rather than
     * passing vacuously -- otherwise deleting the element under test would look
     * exactly like fixing it.
     */
    private fun inkContrastAgainst(ground: Color, tag: String): Double =
        inkContrast(ground, tag) { _, _ -> true }

    /** As [inkContrastAgainst], over the two columns at each edge only. */
    private fun outlineInkContrastAgainst(ground: Color, tag: String): Double =
        inkContrast(ground, tag) { x, width -> x < 2 || x >= width - 2 }

    private fun inkContrast(ground: Color, tag: String, keep: (Int, Int) -> Boolean): Double {
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        var ink = 0.0
        var found = false
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (!keep(x, pixels.width)) continue
                val pixel = pixels[x, y]
                if (pixel == ground) continue
                found = true
                ink = maxOf(ink, Contrast.ratio(pixel, ground))
            }
        }
        assertTrue("'$tag' painted nothing but its background", found)
        return ink
    }
}
