package my.pinged.ledger

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import my.pinged.ledger.ArtboardProbe.Bounds
import my.pinged.ledger.ArtboardProbe.bounds
import my.pinged.ledger.ArtboardProbe.close
import my.pinged.ledger.ArtboardProbe.darkest
import my.pinged.ledger.ArtboardProbe.inked
import my.pinged.ledger.ArtboardProbe.isDp
import my.pinged.ledger.ArtboardProbe.layoutOf
import my.pinged.ledger.ArtboardProbe.lineCentre
import my.pinged.ledger.ArtboardProbe.px
import my.pinged.ledger.ArtboardProbe.settledScreen
import my.pinged.ledger.settings.CAPTURE_ALERTS
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
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import kotlin.math.abs

/**
 * Settings against `design/Settings.dc.html`, as redrawn for #230: one test
 * per place it had drifted. The rows and the footer are drawn as the screen
 * draws them, under a scroll so a test can bring any of them on screen at
 * CI's 320x640, with the board's own values.
 */
class SettingsArtboardTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The board's 9dp perforation, 4dp under a section's last row and 8dp over the next label. */
    @Test fun aTornEdgeSeparatesTheSections() {
        settings()
        val edges = compose.onAllNodesWithTag(TORN_EDGE_TAG).fetchSemanticsNodes().map(::bounds)
        assertEquals("Torn edges between the three sections", 2, edges.size)
        val labels = listOf("CAPTURE", "HOW THINGS GET SORTED", "YOUR DATA").map {
            bounds(compose.onNodeWithText(it).fetchSemanticsNode())
        }
        edges.forEachIndexed { i, edge ->
            assertTrue("Edge $i is not under section $i's label", edge.top >= labels[i].bottom)
            assertTrue(
                "Edge $i sits ${(labels[i + 1].top - edge.bottom) / density}dp over the next label, not 8",
                isDp(labels[i + 1].top - edge.bottom, 8f, density),
            )
            assertTrue("Edge $i is ${edge.height / density}dp tall, not 9", isDp(edge.height, 9f, density))
        }
        val last = bounds(compose.onNodeWithText(UNREAD).fetchSemanticsNode())
        assertTrue(
            "The first edge sits ${(edges[0].top - last.bottom) / density}dp under the row, not 4",
            isDp(edges[0].top - last.bottom, 4f, density),
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

    /**
     * The board's row is 44px, its label and caption centred in it, the caption
     * 2px under the label. Restore's, whose caption fits on one line even at
     * CI's 320dp: Export's wraps there, which is narrower than the board.
     */
    @Test fun aCaptionedRowCentresItsLabelAndCaption() {
        settings()
        val row = bounds(compose.onNodeWithText(RESTORE).performScrollTo().fetchSemanticsNode())
        val label = bounds(compose.onNodeWithText(RESTORE, useUnmergedTree = true).fetchSemanticsNode())
        val caption = bounds(compose.onNodeWithText(RESTORE_CAPTION, useUnmergedTree = true).fetchSemanticsNode())
        assertTrue("The row is ${row.height / density}dp, not 44", isDp(row.height, 44f, density))
        assertTrue("The caption is ${(caption.top - label.bottom) / density}dp under the label, not 2", isDp(caption.top - label.bottom, 2f, density))
        val above = label.top - row.top
        val below = row.bottom - caption.bottom
        assertTrue("${above / density}dp above the label and ${below / density}dp below the caption", abs(above - below) <= 2)
    }

    /**
     * #230: the captions sat beside their values on two lines. At the board's
     * 390dp each fits on one, as drawn, with the board's values beside it.
     */
    @Test fun eachCaptionFitsBesideItsValueAtTheBoardsWidth() {
        settings(Modifier.requiredWidth(390.dp))
        val wrapped = CAPTIONS.mapNotNull { caption ->
            val node = compose.onNodeWithText(caption, useUnmergedTree = true)
            val lines = layoutOf(node).lineCount
            "$caption: $lines lines".takeIf { lines != 1 }
        }
        assertTrue("Wrapped at 390dp: $wrapped", wrapped.isEmpty())
    }

    /** Faint is allowed for a mark that is not text (`Color.kt`), and the board draws the chevron in it. */
    @Test fun theChevronIsFaint() {
        settings()
        val row = bounds(compose.onNodeWithText("Capture sources").fetchSemanticsNode())
        val screen = settledScreen()
        val glyph = darkest(screen, row.right - px(37f, density) until row.right - px(20f, density), row.top until row.bottom)
        assertTrue("The chevron's core is $glyph, not Faint", close(glyph, Faint))
    }

    /**
     * #230: the board's arrow out of the app, not a chevron, on the row that
     * opens Android's settings. The arrow's head has its corner at (16, 8) of
     * a 24-unit box, where a chevron's strokes, from (9, 5) to (15, 12), leave
     * Paper.
     */
    @Test fun capturesAlertsLeadOutOfTheApp() {
        settings()
        val alerts = bounds(compose.onNodeWithText(CAPTURE_ALERTS).performScrollTo().fetchSemanticsNode())
        val sources = bounds(compose.onNodeWithText("Capture sources").fetchSemanticsNode())
        val screen = settledScreen()
        assertTrue("No arrowhead's corner on the alerts row", corner(screen, alerts, 16f))
        assertTrue("The sources row's chevron has a corner where an arrow's would be", !corner(screen, sources, 17f))
    }

    /** The board draws the unread count in Stamp: each is a notification the ledger has lost. */
    @Test fun theUnreadCountIsStamp() {
        settings()
        val count = bounds(compose.onNodeWithText("12", useUnmergedTree = true).fetchSemanticsNode())
        val glyph = darkest(settledScreen(), count.left until count.right, count.top until count.bottom)
        assertTrue("The unread count's core is $glyph, not Stamp", close(glyph, Stamp))
    }

    @Test fun theFooterIsCentredUnderADottedRule() {
        settings()
        val first = compose.onNodeWithText(FOOTER_FIRST, useUnmergedTree = true).performScrollTo()
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val firstCentre = lineCentre(first)
        assertTrue("\"$FOOTER_FIRST\" is centred at $firstCentre, not ${width / 2}", abs(firstCentre - width / 2f) <= 2f)
        // The second line is two texts, the link its own: centred as one.
        val build = compose.onNodeWithText(FOOTER_SECOND, useUnmergedTree = true)
        val link = compose.onNodeWithText("LICENCES", useUnmergedTree = true)
        val left = bounds(build).left + layoutOf(build).getLineLeft(0)
        val right = bounds(link).left + layoutOf(link).getLineRight(0)
        val secondCentre = (left + right) / 2f
        assertTrue("The second line is centred at $secondCentre, not ${width / 2}", abs(secondCentre - width / 2f) <= 2f)
        assertTrue("The second line is not under the first", bounds(build).top >= bounds(first).bottom)
        val top = bounds(first.fetchSemanticsNode()).top
        val screen = settledScreen()
        val ruled = (top - px(14f, density) until top).any { y ->
            (0 until width).count { inked(screen[it, y], Rule) } > width / 6
        }
        assertTrue("No dotted Rule above the footer", ruled)
    }

    /** Whether the mark at the end of [row], [size]dp square, is inked at the arrowhead's corner. */
    private fun corner(screen: PixelMap, row: Bounds, size: Float): Boolean {
        val box = px(size, density)
        val left = row.right - px(20f, density) - box
        val top = row.top + (row.height - box) / 2
        val x = left + box * 16 / 24
        val y = top + box * 8 / 24
        val reach = px(1f, density)
        return darkest(screen, x - reach..x + reach, y - reach..y + reach).let { close(it, Faint) }
    }

    private fun settings(width: Modifier = Modifier.fillMaxWidth()) {
        val now = System.currentTimeMillis()
        compose.setContent {
            PingedTheme {
                Column(width.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
                    SettingsRows(
                        state = SettingsState(
                            storage = Storage.HEALTHY,
                            sourcesOn = 4,
                            learnedCount = 54,
                            ignoredCount = 0,
                            unreadCount = 12,
                            categoryCount = 16,
                            lastExportAt = now - TimeUnit.DAYS.toMillis(3),
                            lastCheckAt = now,
                            loaded = true,
                        ),
                        onOpenSources = {}, onOpenCorrections = {}, onOpenLearned = {}, onOpenCategories = {},
                        onExport = {}, onRestore = {}, onCheck = {}, onDelete = {},
                        alertsOn = true, onAlerts = {}, onOpenUnread = {},
                    )
                    Footer(buildVersion = "1.0", packVersion = 7, onOpenLicences = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private companion object {
        const val RESTORE = "Replace everything from a backup"
        const val RESTORE_CAPTION = "DISCARDS WHAT IS HERE FIRST"
        const val EXPORT_CAPTION = "EVERY TRANSACTION AND ITS NOTIFICATION"
        const val UNREAD = "Notifications Pinged could not read"
        val CAPTIONS = listOf(
            "A DAILY CHECK, IN ANDROID'S SETTINGS",
            "HELPS ADD SUPPORT FOR NEW BANKS",
            EXPORT_CAPTION,
            RESTORE_CAPTION,
            "NO PROBLEMS FOUND",
        )
        const val FOOTER_FIRST = "NO INTERNET PERMISSION · NOTHING LEAVES THIS PHONE"
        const val FOOTER_SECOND = "BUILD 1.0 · PACK 7 · "
    }
}
