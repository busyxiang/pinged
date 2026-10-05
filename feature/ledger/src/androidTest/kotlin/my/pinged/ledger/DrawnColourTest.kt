package my.pinged.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import my.pinged.ledger.settings.CONFIRMATION_FIELD_TAG
import my.pinged.ledger.settings.EXPORT_FIRST_TAG
import my.pinged.ledger.settings.WipeSheetBody
import my.pinged.ledger.sources.DETAIL_TAG
import my.pinged.ledger.sources.PILL_TAG
import my.pinged.ledger.sources.SourceRow
import my.pinged.ledger.sources.SourcesScreenContent
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.theme.Card
import my.pinged.ledger.theme.Contrast
import my.pinged.ledger.theme.Paper
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
     * **The boundary of the app's only text input**, sampled at the node's
     * bottom rows, where only the underline can be.
     *
     * Reading the whole node would not do it: the ghosted `DELETE` inside the
     * field is painted in the same `Faint`, so with the underline reverted to
     * the artboard's 1.60:1 `Rule` the placeholder still supplies a 3.55:1
     * pixel and the assertion passes over a field with no visible edge. The
     * same trap `theOffStateOfTheToggleIsPaintedVisibly` records for the knob.
     *
     * WCAG 1.4.11 rather than 1.4.3: this is a component boundary, and it is
     * the only thing on the sheet that says a field is there at all.
     */
    @Test fun theConfirmationFieldHasAVisibleBoundary() {
        wipeSheet()
        val worst = bottomInkContrastAgainst(Paper, CONFIRMATION_FIELD_TAG)
        assertTrue(
            ("The underline under the word the user has to type is painted at " +
                "%.2f:1 against the sheet behind it. WCAG 1.4.11 asks %.1f:1, " +
                "and it is the only thing saying there is a field here.")
                .format(worst, Contrast.COMPONENT_MINIMUM),
            worst >= Contrast.COMPONENT_MINIMUM,
        )
    }

    /**
     * **The boundary of the sheet's one offer**, sampled at the node's outer
     * columns, where only `dashedOutline`'s stroke can be: the row centres its
     * mark and its label, so nothing else reaches an edge.
     *
     * The offer is a 48dp tap target with no background and no border -- those
     * dashes are the whole of it, and WCAG 1.4.11 asks 3:1 of a component
     * boundary. What fails here is the modifier dropped from the row, or its
     * colour moved to a lighter token at either end: `dashedOutline` takes the
     * colour from its call site, and `ContrastTest` cannot see which token a
     * call site passes.
     *
     * The dash *length* stays unpinned deliberately: it is a house style
     * (`Perforation.kt` records the choice), not a legibility floor, and an
     * assertion on it would fail for a change that harms nobody.
     */
    @Test fun theExportFirstOfferHasAVisibleBoundary() {
        wipeSheet()
        val worst = outlineInkContrastAgainst(Paper, EXPORT_FIRST_TAG)
        assertTrue(
            ("The dashed box offering an export before the delete is painted at " +
                "%.2f:1 against the sheet behind it. WCAG 1.4.11 asks %.1f:1, " +
                "and it is the only thing saying the offer is a control.")
                .format(worst, Contrast.COMPONENT_MINIMUM),
            worst >= Contrast.COMPONENT_MINIMUM,
        )
    }

    /**
     * The delete sheet's body on [Paper], with the 20dp its chrome supplies.
     *
     * `counts = null`: the sheet then itemises nothing, which shortens it
     * without touching either boundary read above.
     */
    private fun wipeSheet() = compose.setContent {
        PingedTheme {
            Box(Modifier.background(Paper).padding(20.dp)) {
                Column {
                    WipeSheetBody(
                        counts = null,
                        onConfirm = {},
                        onExportFirst = {},
                        onDismiss = {},
                    )
                }
            }
        }
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

    /**
     * As [inkContrastAgainst], over the bottom rows only.
     *
     * Three rows, not two: the underline is 1.5dp, which is four pixels at
     * this emulator's density, so the last three rows are all inside it --
     * measured, and the node is 103px tall. Sampling by row needs the `keep`
     * predicate to see `y`, which [inkContrast] passes as its first argument
     * when [byRow] is set.
     */
    private fun bottomInkContrastAgainst(ground: Color, tag: String): Double =
        inkContrast(ground, tag, byRow = true) { y, height -> y >= height - 3 }

    private fun inkContrast(
        ground: Color,
        tag: String,
        byRow: Boolean = false,
        keep: (Int, Int) -> Boolean,
    ): Double {
        val pixels = captured(tag).toPixelMap()
        var ink = 0.0
        var found = false
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (!keep(if (byRow) y else x, if (byRow) pixels.height else pixels.width)) continue
                val pixel = pixels[x, y]
                if (pixel == ground) continue
                found = true
                ink = maxOf(ink, Contrast.ratio(pixel, ground))
            }
        }
        assertTrue("'$tag' painted nothing but its background", found)
        return ink
    }

    /**
     * The tagged node's pixels, waiting out a frame the emulator is slow to draw.
     *
     * `captureToImage` forces a redraw and gives it 2000ms, fixed inside
     * Compose's `WindowCapture`. A CI runner's emulator renders on the host CPU
     * (SwiftShader) while Gradle builds beside it, and a full screen of
     * `SourcesScreenContent` can take longer than that: 3 of #17's CI runs
     * failed here, and with the emulator held to one host core, 7 and 9 of 12
     * runs of the two tests that draw it, against 0 of 12 for the delete
     * sheet's smaller frame (issue #16). The assertion is about colour, not
     * frame time, so the timeout alone is retried; a node that never draws
     * still fails every attempt, and one that draws only its ground fails
     * [inkContrast]'s own check.
     */
    private fun captured(tag: String): ImageBitmap {
        var last: ComposeTimeoutException? = null
        repeat(CAPTURE_ATTEMPTS) {
            try {
                return compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage()
            } catch (slow: ComposeTimeoutException) {
                last = slow
            }
        }
        throw checkNotNull(last)
    }

    private companion object {
        /** Ten seconds of redraw in all, five of `captureToImage`'s own 2s. */
        const val CAPTURE_ATTEMPTS = 5
    }
}
