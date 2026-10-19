package my.pinged.ledger.licences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ledger.BackChevron
import my.pinged.ledger.SETTINGS_BACK
import my.pinged.ledger.R
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.dottedRule

/**
 * The bundled fonts' SIL OFL texts (spec 8, "Typefaces, and how they ship"),
 * pushed from settings' Licences row: every word as the file has it, through
 * [reflow].
 *
 * **[LICENCES] is the only reference to `R.raw.ofl_*`, and it is what keeps
 * them in a release APK.** The resource shrinker drops a resource no code
 * names, and the OFL requires the text to go wherever the fonts go. `:smoke`'s
 * `LicencesShipTest` opens each one in the minified build.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    // About 13 KB from the APK, read once per entry on the main thread.
    val texts = remember(resources) {
        LICENCES.map { (family, id) -> family to reflow(resources.openRawResource(id).bufferedReader().use { it.readText() }) }
    }
    LicencesContent(texts, onBack, modifier)
}

/** [LicencesScreen] with the texts already read and reflowed, so a test can draw any. */
@Composable
internal fun LicencesContent(texts: List<Pair<String, String>>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        Header(onBack)
        for ((family, text) in texts) {
            Text(
                family.uppercase(),
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
            )
            Text(
                text,
                fontFamily = Body,
                fontSize = 13.sp,
                color = Ink,
                modifier = Modifier.fillMaxWidth().dottedRule(atTop = false).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            )
        }
    }
}

/** The chevron beside the serif title, as on every screen Settings opens. */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackChevron(SETTINGS_BACK, onBack)
        Text(
            LICENCES_TITLE,
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 24.sp,
            color = Ink,
        )
    }
}

/**
 * [text] with each paragraph's hard wraps undone, so it wraps to the screen
 * rather than ending lines mid-sentence where the file's do, at up to 79
 * columns. The wording is the licence and is kept word for word; the file's
 * line breaks are not part of it. Blank-line paragraph breaks stay where the
 * file has them, and a heading or a rule ([isHeading]) keeps its own line
 * rather than running into the text under it. Trailing spaces go: the preamble has one mid-paragraph.
 * Lines are split by [lines], so Plex's CRLF file reads as the others do.
 */
internal fun reflow(text: String): String {
    val lines = text.lines().map { it.trimEnd() }
    return buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                val before = lines[index - 1]
                val joined = before.isNotEmpty() && line.isNotEmpty() && !isHeading(before) && !isHeading(line)
                append(if (joined) ' ' else '\n')
            }
            append(line)
        }
    }
}

/**
 * The OFL's `PREAMBLE` and `PERMISSION & CONDITIONS`, and its rules of
 * dashes: no lower case and at most three words. The disclaimer is in
 * capitals too, but every line of it is longer.
 */
private fun isHeading(line: String): Boolean = line.none { it.isLowerCase() } && line.split(' ').size <= 3

/** Each bundled family, in `Type.kt`'s order, with its licence. */
private val LICENCES: List<Pair<String, Int>> = listOf(
    "Instrument Serif" to R.raw.ofl_instrument_serif,
    "Karla" to R.raw.ofl_karla,
    "IBM Plex Mono" to R.raw.ofl_ibm_plex_mono,
)

/** The settings row's label and this screen's title. */
const val LICENCES_TITLE = "Licences"
