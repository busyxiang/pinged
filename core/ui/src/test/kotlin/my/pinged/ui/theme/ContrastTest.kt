package my.pinged.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette, against the two grounds -- and **only** the palette.
 *
 * **What this cannot see.** It reads `Color.kt` and nothing else. The bug it
 * was written for was not a wrong hex value, it was the wrong *token* on a
 * `Text` -- and which token an element is drawn in lives in `SourcesScreen.kt`.
 * Reverting the fix there leaves this suite green while both original bugs are
 * back on screen; that was measured, after the fix commit claimed otherwise.
 * `DrawnColourTest` guards the other half by sampling what is painted, and it
 * is the one that matters.
 *
 * The palette came from artboards, which are a picture of a paper ledger and
 * were not drawn against a contrast standard. Three colours failed, and the two
 * that mattered carried this screen's meaning: "NOT ONE WORD STORED" at 3.12:1,
 * and the **off** state of the capture toggle at 1.35:1.
 *
 * A comment beside a hex value cannot hold that -- read a triple and you cannot
 * tell 3.12 from 4.60 -- so the numbers are computed from the colours.
 *
 * WCAG 2.2, two rules, chosen by what the colour is used for:
 *
 *  - **1.4.3**, 4.5:1, text against its background. Every string here is at or
 *    below 15sp, so none qualifies as large text.
 *  - **1.4.11**, 3:1, a component or its states against what is behind it.
 *
 * Decoration is exempt from both, and the perforation rules are the only
 * decoration here.
 *
 * **One thing deliberately not asserted:** on against off measures 1.77:1,
 * which looks alarming and is not. WCAG 1.4.1 asks that colour is never the
 * *only* way a state is conveyed, and here it is not the way at all -- on is a
 * filled pill with the knob right, off an outline with the knob left. That
 * property is structural, it lives in `Pill`, and a palette test asserting a
 * ratio between the two states would fail the day someone chose a better
 * accent.
 */
class ContrastTest {

    /** The two colours anything on this screen is drawn on. */
    private val grounds = mapOf("Paper" to Paper, "Card" to Card)

    private val text = mapOf(
        "Ink, every label and title" to Ink,
        "Muted, the section labels, the seen counts and spec 9.6's claim" to Muted,
        "Stamp, the alert and the authoritative chip" to Stamp,
    )

    private val components = mapOf(
        "Faint, the off state of the capture toggle" to Faint,
        "Stamp, the on state of the capture toggle" to Stamp,
    )

    @Test fun everyTextColourIsReadableOnEveryGround() {
        assertAtLeast(Contrast.TEXT_MINIMUM, text, "WCAG 1.4.3: text against its background")
    }

    @Test fun everyToggleStateIsVisibleOnEveryGround() {
        assertAtLeast(Contrast.COMPONENT_MINIMUM, components, "WCAG 1.4.11: a component and its states")
    }

    /**
     * The knob inside the toggle, which is drawn on the toggle rather than on
     * a ground -- so the pair that matters is knob against pill, not knob
     * against the screen.
     *
     * The on state is the one to watch: a light knob on the accent is the
     * whole of what says "this source is being captured".
     */
    @Test fun theKnobIsVisibleAgainstTheToggleItSitsIn() {
        assertTrue(
            "The on knob is ${"%.2f".format(ratio(Paper, Stamp))}:1 against the pill it sits in",
            ratio(Paper, Stamp) >= Contrast.COMPONENT_MINIMUM,
        )
    }

    /**
     * A floor under the type scale, guarded here because nothing else was.
     *
     * The contrast fix made spec 9.6's claim a legible colour at an illegible size:
     * `MonoLabel` was 10sp, and it is the style behind both headings, the seen
     * counts, the footer and the claim. A guarded palette beside an unguarded type
     * scale is half a design system, and the unguarded half is the one that drifts.
     *
     * 12sp is the practical floor for body-adjacent text on Android. The one style
     * deliberately below it is `AuthoritativeChip`, which cannot draw on a device
     * in this milestone.
     */
    @Test fun theMachineVoiceIsLargeEnoughToRead() {
        assertTrue(
            "MonoLabel is ${MonoLabel.fontSize}, and it sets both section " +
                "headings, the seen counts, the footer and spec 9.6's claim " +
                "about what is stored",
            MonoLabel.fontSize.value >= 12f,
        )
    }

    /**
     * And its tracking is the artboard's, converted.
     *
     * The board specifies 0.1em at 10px, which is 1.0px. This shipped as
     * `0.1.sp` -- the em value unconverted, a factor of ten -- while every
     * other value in `Type.kt` converted correctly. Tight tracking on small
     * all-caps mono is exactly what costs legibility, so it compounded the
     * size.
     */
    @Test fun theMachineVoiceIsTrackedLikeTheArtboard() {
        assertTrue(
            "MonoLabel tracking is ${MonoLabel.letterSpacing}; the artboard's " +
                "0.1em at this size is about 1.2sp, and 0.1sp is the em value " +
                "shipped unconverted",
            MonoLabel.letterSpacing.value >= 0.9f,
        )
    }

    /**
     * The grid's four steps are the artboard's, not a slice of [ChartRamp]:
     * the palest is a colour `ChartRamp` does not hold (#68), and the darkest
     * is `ChartRamp`'s, so a grid cell and a bar at the top of their scales
     * are one colour. Expected values are the artboard's hex, read off the
     * prototype's `GRID4`.
     */
    @Test fun theGridRampRunsFromTheArtboardsPalestStepToTheChartRampsDarkest() {
        assertEquals(4, GridRamp.size)
        assertEquals(Color(0xFFC3BBA8), GridRamp.first())
        assertEquals(ChartRamp.first(), GridRamp.last())
    }

    /**
     * A ramp means magnitude and nothing else (spec 8), so each step must sit
     * further from the ground than the one before it, on both grounds, or a
     * larger amount draws paler than a smaller one. Checked for both ramps the
     * same way. `ChartRamp` is darkest first and `GridRamp` palest first, so
     * the bars' ramp is reversed here to compare like with like.
     *
     * Deliberately not a WCAG floor: the pale steps are below 3:1 on both
     * grounds (`GridRamp`'s first is 1.77:1 on Paper) and were drawn that way.
     * What keeps colour from being the only reading is the amount printed
     * beside every bar and the grid's legend range (spec 8), not the fill.
     */
    @Test fun eachRampDarkensStepByStepOnEveryGround() {
        val ramps = mapOf("GridRamp" to GridRamp, "ChartRamp, reversed" to ChartRamp.reversed())
        val failures = buildList {
            for ((name, ramp) in ramps) {
                for ((where, ground) in grounds) {
                    val ratios = ramp.map { ratio(it, ground) }
                    if (ratios.zipWithNext().any { (paler, darker) -> darker <= paler }) {
                        add("  $name on $where: " + ratios.joinToString(" -> ") { "%.2f".format(it) })
                    }
                }
            }
        }
        assertTrue(
            "Each step must contrast more with the ground than the step before it.\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun assertAtLeast(minimum: Double, colours: Map<String, Color>, rule: String) {
        val failures = buildList {
            for ((what, colour) in colours) {
                for ((where, ground) in grounds) {
                    val r = ratio(colour, ground)
                    if (r < minimum) add("  $what on $where: ${"%.2f".format(r)}:1")
                }
            }
        }
        assertTrue(
            "$rule asks for $minimum:1.\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun ratio(a: Color, b: Color): Double = Contrast.ratio(a, b)
}
