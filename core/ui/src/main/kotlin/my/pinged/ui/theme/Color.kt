package my.pinged.ui.theme

import androidx.compose.ui.graphics.Color

// The artboard palette, with the two contrast rules it has to satisfy stated
// here and enforced by `ContrastTest`.
//
// WCAG 2.2 asks 4.5:1 between text and its background (1.4.3) and 3:1 between
// a component or its state and what is behind it (1.4.11). The artboards are a
// static picture of a paper ledger and were not drawn against either number.
//
// The measured ratios are written beside each colour because a hex value on its
// own cannot be reviewed. They are against Paper and Card, the only two grounds
// anything is drawn on.

/** The upper ground: the half of the allow-list whose text is stored. */
val Paper = Color(0xFFFBF6EA)

/** The lower ground, below the perforation. */
val Card = Color(0xFFEFE7D8)

/** Body and headings. 16.55:1 on Paper, 14.53:1 on Card. */
val Ink = Color(0xFF1A1714)

/**
 * Every piece of small print on this screen: section labels, seen counts, and
 * spec 9.6's claim about what is stored. 5.42:1 on Paper, 4.76:1 on Card.
 *
 * The claim used to be drawn in [Faint], at 3.12:1 on Card -- the sentence the
 * app's whole promise rests on, printed below the contrast at which text is
 * considered readable. It is the last line anyone should have to squint at.
 */
val Muted = Color(0xFF6B6459)

/**
 * The **off** state of the capture toggle: its outline and its knob. 3.55:1 on
 * Paper, 3.12:1 on Card.
 *
 * Lighter than [Muted] because an off switch should be quiet, dark enough to
 * clear 3:1 because a switch nobody can see is not a switch. The artboard drew
 * this state in [Border] and [Rule], at 1.35:1 and 1.41:1 -- so on the screen
 * whose single question is which apps are being read, "not this one" was very
 * nearly invisible.
 *
 * Not used for text: at 3.12:1 it clears 1.4.11 and fails 1.4.3.
 */
val Faint = Color(0xFF8A8175)

/** Perforations and dotted rules. Decorative: no contrast rule applies. */
val Rule = Color(0xFFCFC4AE)

/** Material's `outlineVariant`, for components this app does not draw. */
val Border = Color(0xFFDED5C4)

/** The one accent: the on state, the alert, the authoritative stamp. */
val Stamp = Color(0xFFA03826)

/**
 * The category bars' ramp (spec 8). One hue, six steps, **darkest first**:
 * index 0 is the largest category. On Paper, darkest to palest: 16.55, 10.90,
 * 6.82, 4.41, 2.94, 2.07:1.
 */
val ChartRamp = listOf(
    Color(0xFF1A1714), Color(0xFF3D372F), Color(0xFF5C554A),
    Color(0xFF7A7264), Color(0xFF98907F), Color(0xFFB6AD99),
)

/**
 * The daily rhythm grid's ramp (spec 8, #68). Four steps, **palest first**,
 * the reverse of [ChartRamp]: the grid's rule takes the darkest k steps as
 * `GridRamp.takeLast(k)`, the prototype's `GRID4.slice(4 - k)`.
 *
 * Its own entry because the palest step, `#c3bba8`, is not in [ChartRamp].
 * The darkest is [ChartRamp]'s, so the top of both scales is one colour.
 *
 * On Paper, palest to darkest: 1.77, 2.94, 6.82, 16.55:1; on Card 1.55,
 * 2.58, 5.99, 14.53:1. The two pale steps are below WCAG 1.4.11's 3:1 as the
 * artboard drew them; the legend's printed range is what keeps colour from
 * being the only reading.
 */
val GridRamp = listOf(
    Color(0xFFC3BBA8), Color(0xFF98907F), Color(0xFF5C554A), Color(0xFF1A1714),
)
