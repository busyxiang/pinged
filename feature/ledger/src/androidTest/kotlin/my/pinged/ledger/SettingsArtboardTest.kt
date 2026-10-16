package my.pinged.ledger

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import my.pinged.ledger.ArtboardProbe.bounds
import my.pinged.ledger.ArtboardProbe.close
import my.pinged.ledger.ArtboardProbe.darkest
import my.pinged.ledger.ArtboardProbe.inked
import my.pinged.ledger.ArtboardProbe.isDp
import my.pinged.ledger.ArtboardProbe.lineCentre
import my.pinged.ledger.ArtboardProbe.px
import my.pinged.ledger.ArtboardProbe.settledScreen
import my.pinged.ledger.settings.Footer
import my.pinged.ledger.settings.SettingsRows
import my.pinged.ledger.settings.SettingsState
import my.pinged.ledger.settings.Storage
import my.pinged.ledger.settings.TORN_EDGE_TAG
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import kotlin.math.abs

/**
 * #171: Settings against `design/Settings.dc.html`, one test per place it had
 * drifted. The rows and the footer are drawn as the screen draws them, under
 * a scroll so a test can bring any of them on screen at CI's 320x640.
 */
class SettingsArtboardTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The board's 9dp perforation, 10dp under a section's last row and 12dp over the next label. */
    @Test fun aTornEdgeSeparatesTheSections() {
        settings()
        val edges = compose.onAllNodesWithTag(TORN_EDGE_TAG).fetchSemanticsNodes().map(::bounds)
        assertEquals("Torn edges between the four sections", 3, edges.size)
        val labels = listOf("CAPTURE", "HOW THINGS GET SORTED", "YOUR DATA", "ABOUT").map {
            bounds(compose.onNodeWithText(it).fetchSemanticsNode())
        }
        edges.forEachIndexed { i, edge ->
            assertTrue("Edge $i is not under section $i's label", edge.top >= labels[i].bottom)
            assertTrue(
                "Edge $i sits ${(labels[i + 1].top - edge.bottom) / density}dp over the next label, not 12",
                isDp(labels[i + 1].top - edge.bottom, 12f, density),
            )
            assertTrue("Edge $i is ${edge.height / density}dp tall, not 9", isDp(edge.height, 9f, density))
        }
        val last = bounds(compose.onNodeWithText("Corrections from pack updates").fetchSemanticsNode())
        assertTrue(
            "The first edge sits ${(edges[0].top - last.bottom) / density}dp under the row, not 10",
            isDp(edges[0].top - last.bottom, 10f, density),
        )
        // The band is Card; its notches are Paper. Its top pixel row is all band.
        val screen = settledScreen()
        val band = (edges[0].left until edges[0].right).count { close(screen[it, edges[0].top + 1], Card) }
        assertTrue("The edge's top is not Card: $band of ${edges[0].width}px", band > edges[0].width * 0.9)
    }

    @Test fun aOneLineRowIs44dpTall() {
        settings()
        val row = bounds(compose.onNodeWithText("Capture sources").fetchSemanticsNode())
        assertTrue("A one-line row is ${row.height / density}dp, not 44", isDp(row.height, 44f, density))
    }

    @Test fun aRowWithACaptionHas10dpAboveAnd3dpBeforeTheCaption() {
        settings()
        val row = bounds(compose.onNodeWithText(EXPORT).performScrollTo().fetchSemanticsNode())
        val label = bounds(compose.onNodeWithText(EXPORT, useUnmergedTree = true).fetchSemanticsNode())
        val caption = bounds(compose.onNodeWithText(EXPORT_CAPTION, useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("The label is ${(label.top - row.top) / density}dp under the row's top, not 10", isDp(label.top - row.top, 10f, density))
        assertTrue("The caption is ${(caption.top - label.bottom) / density}dp under the label, not 3", isDp(caption.top - label.bottom, 3f, density))
    }

    /** Faint is allowed for a mark that is not text (`Color.kt`), and the board draws the chevron in it. */
    @Test fun theChevronIsFaint() {
        settings()
        val row = bounds(compose.onNodeWithText("Capture sources").fetchSemanticsNode())
        val screen = settledScreen()
        val glyph = darkest(screen, row.right - px(37f, density) until row.right - px(20f, density), row.top until row.bottom)
        assertTrue("The chevron's core is $glyph, not Faint", close(glyph, Faint))
    }

    @Test fun theFooterIsCentredUnderADottedRule() {
        settings()
        val first = compose.onNodeWithText(FOOTER_FIRST, useUnmergedTree = true).performScrollTo()
        val width = compose.onRoot().fetchSemanticsNode().size.width
        for (line in listOf(FOOTER_FIRST, FOOTER_SECOND)) {
            val centre = lineCentre(compose.onNodeWithText(line, useUnmergedTree = true))
            assertTrue("\"$line\" is centred at $centre, not ${width / 2}", abs(centre - width / 2f) <= 2f)
        }
        val top = bounds(first.fetchSemanticsNode()).top
        val screen = settledScreen()
        val ruled = (top - px(14f, density) until top).any { y ->
            (0 until width).count { inked(screen[it, y], Rule) } > width / 6
        }
        assertTrue("No dotted Rule above the footer", ruled)
    }

    private fun settings() {
        compose.setContent {
            PingedTheme {
                Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
                    SettingsRows(
                        state = SettingsState(storage = Storage.HEALTHY, sourcesOn = 4, learnedCount = 54, loaded = true),
                        onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {}, onOpenLicences = {},
                        onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                    )
                    Footer(buildVersion = "1.0", packVersion = 7)
                }
            }
        }
        compose.waitForIdle()
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private companion object {
        const val EXPORT = "Export everything"
        const val EXPORT_CAPTION = "EVERY TRANSACTION AND THE NOTIFICATIONS BEHIND THEM"
        const val FOOTER_FIRST = "NO INTERNET PERMISSION · NOTHING LEAVES THIS PHONE"
        const val FOOTER_SECOND = "BUILD 1.0 · PACK 7"
    }
}
