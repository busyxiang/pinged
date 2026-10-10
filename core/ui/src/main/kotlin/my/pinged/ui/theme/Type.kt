package my.pinged.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import my.pinged.ui.R

/**
 * The three Receipt families, bundled as resources (spec 8, "Typefaces, and how
 * they ship").
 *
 * `androidx.compose.ui.text.googlefonts` is deliberately absent: it resolves
 * through the Play Services font provider, so a de-Googled ROM silently renders
 * the fallback -- and sideloading is this app's entire distribution story.
 *
 * Provenance, so a later upgrade knows what it is replacing: all six files come
 * from github.com/google/fonts, directory `ofl/`, all three families under SIL
 * OFL 1.1. The licence texts ship in `res/raw/ofl_*.txt` verbatim, including
 * IBM's copyright line, because a licence that has been tidied is no longer the
 * licence that was granted.
 *
 * Karla is published upstream only as `Karla[wght].ttf`; the three static
 * instances were cut from it at wght 400, 500 and 700 with fontTools'
 * `instantiateVariableFont`. Static rather than variable-plus-`FontVariation`
 * because the weights are fixed and known, three files at ~45 KB cost about the
 * same as the variable font, and nothing then depends on the platform honouring
 * a variation axis at render time.
 */
val Display = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
)

val Body = FontFamily(
    Font(R.font.karla_regular, FontWeight.Normal),
    Font(R.font.karla_medium, FontWeight.Medium),
    Font(R.font.karla_bold, FontWeight.Bold),
)

/**
 * Numerals, uppercase labels and every machine-voiced line. **Never body copy**
 * -- that is a system rule, not a preference.
 *
 * On the fallback chain, stated precisely rather than claimed: a Compose
 * `FontFamily` is a weight/style resolution list, not a glyph-fallback chain,
 * so when IBM Plex Mono has no glyph the platform's own stack supplies it and
 * no API here can name which font that will be. What this module controls is
 * that the *digits* come from Plex, and they do -- Plex Mono is monospaced, so
 * every digit advances 600 units per em and column alignment holds without a
 * `tnum` feature. [MonoNumerals] carries that guarantee.
 *
 * No width test can verify it: the system `monospace` advances 600.1, so a
 * silent fallback lays out identically. :feature:charts's
 * `MonoControlRenderTest` does, by asserting a render in this family differs
 * from the same amount in `FontFamily.Monospace`, and by its golden.
 */
val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/**
 * Tabular figures, asked for explicitly.
 *
 * Redundant in Plex Mono, which is monospaced, and deliberately kept anyway:
 * it is the line that has to be true if the mono family is ever swapped for a
 * proportional one, and it costs nothing.
 */
val MonoNumerals = TextStyle(
    fontFamily = Mono,
    fontFeatureSettings = "tnum",
)

/**
 * The screen's small-caps machine voice: 12sp mono, wide tracking.
 *
 * Uppercasing is done in the string, not by a text transform, so what the
 * accessibility tree reports is what is drawn -- which is also what the UI test
 * matches on.
 *
 * **12sp, not the artboard's 10.** The board is 390px wide at 1px to the dp, so
 * its 10px reads as 10sp -- and almost every string on this screen uses this
 * style: both headings, the seen counts, and spec 9.6's claim about what is
 * stored. A legible colour at an illegible size is half a fix. Same overrule
 * the toggle got, for the same reason: the artboard is a picture, and it was
 * not drawn against anyone trying to read it.
 *
 * **1.0sp of tracking, not 0.1.** The board specifies `0.1em` at `10px`, which
 * is 1.0px. Every other value in this file converted its em correctly and this
 * one shipped the em unconverted, a factor of ten -- visible only on the three
 * consumers that use `MonoLabel` raw, because the rest override it.
 */
val MonoLabel = MonoNumerals.copy(
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.0.sp,
)

private val Default = Typography()

/**
 * Every slot is assigned a family, including the ones nothing draws yet.
 *
 * A slot left at its Material default renders in the platform sans, which on
 * a screen otherwise entirely in Karla reads as a bug and would ship
 * unnoticed. Display and headline and title take the serif (hero numerals and
 * sheet titles), body takes Karla, label takes the mono.
 */
val PingedTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = Display),
    displayMedium = Default.displayMedium.copy(fontFamily = Display),
    displaySmall = Default.displaySmall.copy(fontFamily = Display),
    headlineLarge = Default.headlineLarge.copy(fontFamily = Display),
    headlineMedium = Default.headlineMedium.copy(fontFamily = Display),
    headlineSmall = Default.headlineSmall.copy(fontFamily = Display),
    titleLarge = Default.titleLarge.copy(fontFamily = Display),
    titleMedium = Default.titleMedium.copy(fontFamily = Display),
    titleSmall = Default.titleSmall.copy(fontFamily = Display),
    bodyLarge = Default.bodyLarge.copy(fontFamily = Body),
    bodyMedium = Default.bodyMedium.copy(fontFamily = Body),
    bodySmall = Default.bodySmall.copy(fontFamily = Body),
    labelLarge = Default.labelLarge.copy(fontFamily = Mono, fontFeatureSettings = "tnum"),
    labelMedium = Default.labelMedium.copy(fontFamily = Mono, fontFeatureSettings = "tnum"),
    labelSmall = Default.labelSmall.copy(fontFamily = Mono, fontFeatureSettings = "tnum"),
)

/**
 * The middle dot that joins two halves of a label.
 *
 * One declaration. It was `private const val SEPARATOR` in SourcesScreen and
 * again in MainActivity -- because the first was not visible -- and written
 * out as a raw escape a third time in the screen test, which matches on it
 * exactly. Changing it in one place drifted the banner labels away from the
 * screen's detail lines with nothing failing.
 */
const val Separator: String = " \u00B7 "
