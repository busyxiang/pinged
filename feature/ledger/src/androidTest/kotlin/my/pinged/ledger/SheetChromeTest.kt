package my.pinged.ledger

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ALWAYS_SWITCH_TAG
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.home.TeachOffer
import my.pinged.ledger.settings.EXPORT_TITLE
import my.pinged.ledger.settings.ExportReceiptSheet
import my.pinged.ledger.settings.SettingsState
import my.pinged.ledger.settings.WipeSheet
import my.pinged.ui.theme.CLOSE
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * #164: the sheets' chrome, read back off the screen, against the artboards'
 * sheet edge (`design/Chooser.dc.html`, `Export`, `Wipe`, `Months`): square
 * corners, a 1.5dp dashed top edge, no drag handle, and a scrim of [Ink] at
 * 42%. And the chooser's two stock controls, which drew Material's pill.
 *
 * Pixels, because every one of these is a default Material supplies when the
 * call site says nothing: no semantics or layout assertion changes when a
 * sheet grows 28dp corners.
 *
 * The whole screen is read, as the user sees it; see [screen] for why.
 */
class SheetChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aReceiptSheetHasSquareCorners() = assertSquareCorners(receiptSheet())

    @Test fun aReceiptSheetDrawsNoDragHandle() = assertNoDragHandle(receiptSheet())

    @Test fun aReceiptSheetDimsBehindItInInk() = assertInkScrim(receiptSheet())

    @Test fun aReceiptSheetsTopEdgeIsDashedInInk() = assertDashedEdge(receiptSheet(), Ink)

    @Test fun theDeleteSheetsTopEdgeIsDashedInStamp() {
        compose.setContent {
            PingedTheme {
                Behind()
                WipeSheet(counts = null, onConfirm = {}, onExportFirst = {}, onDismiss = {})
            }
        }
        assertDashedEdge(screen(), Stamp)
    }

    /** Artboards: 24 to 26 under the last control, against 20 at the sides. */
    @Test fun aReceiptSheetLeavesRoomUnderItsContent() {
        val sheet = receiptSheet()
        val node = compose.onNodeWithTag(CONTENT_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val bottom = node.positionOnScreen.y + node.size.height
        val nav = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val gap = (sheet.height - bottom - nav) / density
        assertTrue("The sheet leaves %.1fdp under its content; the artboards leave 24 to 26".format(gap), gap in 23.5f..26.5f)
    }

    @Test fun theChooserHasSquareCorners() = assertSquareCorners(chooser())

    @Test fun theChooserDrawsNoDragHandle() = assertNoDragHandle(chooser())

    @Test fun theChooserDimsBehindItInInk() = assertInkScrim(chooser())

    @Test fun theChoosersTopEdgeIsDashedInInk() = assertDashedEdge(chooser(), Ink)

    /**
     * The top-left quarter-height square of Save: a pill leaves it mostly
     * the sheet's [Paper]; a 2dp corner leaves it [Stamp] but for a few
     * antialiased pixels in the corner.
     */
    @Test fun theChoosersSaveIsNotAPill() {
        val screen = chooser()
        val (at, size) = on(screen, compose.onNodeWithText(SAVE_LABEL).fetchSemanticsNode())
        val side = size.second / 2
        var paper = 0
        for (y in 0 until side) for (x in 0 until side) if (close(screen[at.first + x, at.second + y], Paper)) paper++
        val share = paper.toFloat() / (side * side)
        assertTrue("%.0f%% of Save's corner square is the sheet showing through".format(share * 100), share < 0.02f)
    }

    /** `design/Chooser.dc.html`: 42 by 24 with a [Stamp] track, as `SourcesScreen`'s toggle. */
    @Test fun theChoosersSwitchIsTheAppsToggle() {
        val screen = chooser()
        val (at, size) = on(screen, compose.onNodeWithTag(ALWAYS_SWITCH_TAG, useUnmergedTree = true).fetchSemanticsNode())
        assertEquals("width", 42, (size.first / density).roundToInt())
        assertEquals("height", 24, (size.second / density).roundToInt())
        // Left of the knob, which sits at the right end while on.
        val track = screen[at.first + (6 * density).toInt(), at.second + size.second / 2]
        assertTrue("The switch's track is $track, not Stamp", close(track, Stamp))
    }

    /** The artboards' header: the title, and an X that dismisses. */
    @Test fun theExportSheetHasItsTitleAndClosesFromItsX() {
        var dismissed = false
        compose.setContent {
            PingedTheme {
                Behind()
                ExportReceiptSheet(state = SettingsState(), onPick = {}, onCancel = {}, onDismiss = { dismissed = true })
            }
        }
        compose.onAllNodesWithText(EXPORT_TITLE).assertAny(hasNoClickAction())
        compose.onNodeWithContentDescription(CLOSE).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("The export sheet's X did not dismiss it", dismissed)
    }

    /** What a stock component that reads the theme's shape is handed. */
    @Test fun theThemesShapesAreTwoDp() {
        lateinit var shapes: Shapes
        compose.setContent { PingedTheme { shapes = MaterialTheme.shapes } }
        compose.waitForIdle()
        val two = RoundedCornerShape(2.dp)
        listOf(shapes.extraSmall, shapes.small, shapes.medium, shapes.large, shapes.extraLarge)
            .forEachIndexed { i, shape -> assertEquals("shape $i", two, shape) }
    }

    // ---- the four edge assertions ------------------------------------------

    private fun assertSquareCorners(sheet: PixelMap) {
        val top = sheetTop(sheet, sheet.width / 2)
        val left = (0 until sheet.width).first { isSheet(sheet[it, top + (40 * density).toInt()]) }
        val cornerTop = sheetTop(sheet, left + (2 * density).toInt())
        assertTrue(
            "The sheet's edge 2dp in from its corner is %.1fdp below its top".format((cornerTop - top) / density),
            cornerTop - top <= 1,
        )
    }

    private fun assertNoDragHandle(@Suppress("UNUSED_PARAMETER") sheet: PixelMap) {
        compose.onAllNodesWithContentDescription("Drag handle", substring = true, ignoreCase = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let { assertTrue("The sheet draws Material's drag handle", it.isEmpty()) }
    }

    private fun assertInkScrim(sheet: PixelMap) {
        val top = sheetTop(sheet, sheet.width / 2)
        val scrim = over(sheet[sheet.width / 2, top / 2], Paper)
        val expected = over(Ink.copy(alpha = 0.42f), Paper)
        assertTrue("The scrim composites to $scrim over Paper; Ink at 42% is $expected", close(scrim, expected))
    }

    /**
     * The sheet's first row clear of the status bar is a run of [colour]
     * broken by [Paper]: dashed, not solid, not absent.
     *
     * Clear of the status bar because a sheet taller than the screen runs up
     * under it and the sheet pads its content by the inset: measured at CI's
     * 320x640, the delete sheet's Paper starts at row 0 and its content at
     * row 52, the status bar's height. The edge is drawn on the content, so
     * it stays visible there; drawn on the sheet itself it would sit under the
     * status bar's icons.
     */
    private fun assertDashedEdge(sheet: PixelMap, colour: Color) {
        val status = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!
            .getInsets(WindowInsetsCompat.Type.statusBars()).top
        val top = maxOf(sheetTop(sheet, sheet.width / 2), status)
        val row = (0 until sheet.width).map { over(sheet[it, top], Paper) }
        val inked = row.count { close(it, colour) }.toFloat() / row.size
        val gaps = row.count { close(it, Paper) }.toFloat() / row.size
        assertTrue(
            "The sheet's top row, %dpx down, is %.0f%% %s and %.0f%% Paper".format(top, inked * 100, colour, gaps * 100),
            inked > 0.25f && gaps > 0.15f,
        )
    }

    // ---- what is drawn ------------------------------------------------------

    private fun receiptSheet(): PixelMap {
        compose.setContent {
            PingedTheme {
                Behind()
                ReceiptSheet(onDismissRequest = {}) {
                    Box(Modifier.fillMaxWidth().height(120.dp).testTag(CONTENT_TAG))
                }
            }
        }
        return screen()
    }

    /** With a merchant and a category picked, so both the switch and Save are drawn, and on. */
    private fun chooser(): PixelMap {
        compose.setContent {
            PingedTheme {
                Behind()
                CategoryPicker(
                    categories = listOf(
                        Category(id = 1, name = "Food & drink", iconKey = "food", sortOrder = 0),
                        Category(id = 2, name = "Groceries", iconKey = "groceries", sortOrder = 1),
                    ),
                    subject = ChooserSubject("PROBE KOPITIAM", "12.50"),
                    teach = TeachOffer(merchant = "Probe Kopitiam", startsOn = true),
                    initial = 1,
                    preview = { RetroPreview(movable = 0, conflicts = emptyList()) },
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        return screen()
    }

    /**
     * The screen as the user sees it, sheet and scrim composited over the
     * [Paper] the activity is painted in.
     *
     * Not `captureToImage`: measured on emulator-5554, every node inside the
     * sheet captured the activity's window instead, a flat #FAFAFA, because
     * the sheet's dialog is not one that capture can find.
     */
    private fun screen(): PixelMap {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        return shot.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap().toPixelMap()
    }

    /** [node]'s pixels on [screen], as an offset and a size. */
    private fun on(screen: PixelMap, node: SemanticsNode): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val at = node.positionOnScreen
        return (at.x.roundToInt() to at.y.roundToInt()) to (node.size.width to node.size.height)
    }

    /** What the scrim dims: the [Paper] every screen behind a sheet is painted in. */
    @Composable private fun Behind() = Box(Modifier.fillMaxSize().background(Paper))

    private val density get() = compose.activity.resources.displayMetrics.density

    /** The first row down [x] that is the sheet rather than the scrim. */
    private fun sheetTop(sheet: PixelMap, x: Int): Int = (0 until sheet.height).first { isSheet(sheet[x, it]) }

    /** Paper, or the ink of the edge drawn on it; the scrim is neither. */
    private fun isSheet(c: Color): Boolean = over(c, Paper).let { close(it, Paper) || close(it, Ink) || close(it, Stamp) }

    private fun over(c: Color, ground: Color): Color = Color(
        red = c.red * c.alpha + ground.red * (1 - c.alpha),
        green = c.green * c.alpha + ground.green * (1 - c.alpha),
        blue = c.blue * c.alpha + ground.blue * (1 - c.alpha),
    )

    private fun close(a: Color, b: Color): Boolean =
        abs(a.red - b.red) < TOLERANCE && abs(a.green - b.green) < TOLERANCE && abs(a.blue - b.blue) < TOLERANCE


    private companion object {
        const val CONTENT_TAG = "sheet-content"
        const val TOLERANCE = 6f / 255f
    }
}
