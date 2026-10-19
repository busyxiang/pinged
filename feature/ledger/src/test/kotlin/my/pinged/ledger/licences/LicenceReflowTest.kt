package my.pinged.ledger.licences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OFL texts as the screen draws them (#274): every word of each shipped
 * file, in its paragraphs, with only the file's hard wraps inside a
 * paragraph undone. Plain JVM, reading the files `R.raw` ships.
 */
class LicenceReflowTest {
    private val files = listOf("ofl_instrument_serif.txt", "ofl_karla.txt", "ofl_ibm_plex_mono.txt")
        .map { File("src/main/res/raw/$it") }

    @Test fun eachShippedTextReflowedIsTheFileWithItsWhitespaceCollapsed() {
        for (file in files) {
            val text = file.readText()
            assertEquals("${file.name} lost or changed a word", collapsed(text), collapsed(reflow(text)))
        }
    }

    @Test fun paragraphBreaksSurvive() {
        for (file in files) {
            val text = file.readText()
            val want = paragraphs(text)
            assertTrue("${file.name} has ${want.size} paragraphs, too few to test", want.size > 10)
            assertEquals("${file.name}'s paragraphs moved", want, paragraphs(reflow(text)))
        }
    }

    /** What the screen is for: a paragraph is one line, which the screen then wraps to its width. */
    @Test fun aHardWrappedParagraphBecomesOneLine() {
        for (file in files) {
            val lines = reflow(file.readText()).lines()
            assertTrue(
                "${file.name}'s condition 1 is still broken across lines",
                "1) Neither the Font Software nor any of its individual components, in Original or Modified Versions, may be sold by itself." in lines,
            )
            assertTrue(
                "${file.name}'s disclaimer is still broken across lines",
                lines.any { it.startsWith("THE FONT SOFTWARE IS PROVIDED") && it.endsWith("OTHER DEALINGS IN THE FONT SOFTWARE.") },
            )
        }
    }

    /** A heading or a rule keeps its own line rather than running into the text it heads. */
    @Test fun headingsAndRulesKeepTheirOwnLines() {
        for (file in files) {
            val lines = reflow(file.readText()).lines()
            for (heading in listOf("PREAMBLE", "DEFINITIONS", "PERMISSION & CONDITIONS", "TERMINATION", "DISCLAIMER")) {
                assertTrue("${file.name}'s $heading is not on a line of its own", heading in lines)
            }
            assertTrue("${file.name}'s title is not between its rules", "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007" in lines)
        }
    }

    private fun collapsed(text: String) = text.trim().replace(WHITESPACE, " ")

    private fun paragraphs(text: String) = text.trim().split(BLANK_LINE).map(::collapsed)

    private companion object {
        val WHITESPACE = Regex("[ \t\r\n]+")
        val BLANK_LINE = Regex("\r?\n[ \t]*\r?\n")
    }
}
