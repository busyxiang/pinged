package my.pinged.ledger

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.detail.DELETE
import my.pinged.ledger.detail.DetailRead
import my.pinged.ledger.detail.DetailScreenContent
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.SAVE_LABEL
import my.pinged.ledger.search.APPLY
import my.pinged.ledger.search.AmountSheet
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * #220: controls the rest of the app draws one way and one screen drew
 * another, read back off the screen. A colour or a stroke's dashes are all
 * that differ, so no semantics or layout assertion can see any of them.
 *
 * Each pixel is called by the nearest of [Paper], [Ink], [Stamp] and [Muted],
 * so antialiasing at a glyph's edge counts for whichever it is closest to.
 */
class SharedControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Search's amount sheet: Apply is its primary action, filled [Stamp] as every other. */
    @Test fun theAmountSheetsApplyIsFilledInStamp() {
        compose.setContent {
            PingedTheme {
                Behind()
                AmountSheet(minSen = null, maxSen = null, onApply = { _, _ -> }, onDismiss = {})
            }
        }
        val counts = tally(screen(), compose.onNodeWithText(APPLY).fetchSemanticsNode())
        assertTrue("Apply is drawn $counts", counts.share(Stamp) > 0.8f)
    }

    /**
     * The chooser with nothing picked: Save outlined in [Muted] on the sheet,
     * as Cash entry's disabled Save is, not a solid slab that reads as
     * enabled. The category sheet draws the same `SaveButton`.
     */
    @Test fun theChoosersDisabledSaveIsAnOutline() {
        compose.setContent {
            PingedTheme {
                Behind()
                CategoryPicker(
                    categories = listOf(Category(id = 1, name = "Groceries", iconKey = "groceries", sortOrder = 0)),
                    subject = ChooserSubject("PROBE KOPITIAM", "12.50"),
                    teach = null,
                    initial = null,
                    preview = { RetroPreview(movable = 0, conflicts = emptyList()) },
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        val counts = tally(screen(), compose.onNodeWithText(SAVE_LABEL).fetchSemanticsNode())
        assertTrue("a disabled Save is drawn $counts", counts.share(Paper) > 0.8f)
        assertTrue("a disabled Save's outline is not Muted: $counts", counts.share(Muted) > 0.01f)
    }

    /**
     * Detail's Delete: a solid [Stamp] edge, as `design/Detail.dc.html` draws
     * it, since `Perforation.kt` keeps dashes for offers. Read along the top
     * edge, where a dash's gaps would leave the screen's [Paper] showing.
     */
    @Test fun detailsDeleteHasASolidEdge() {
        val txn = ledgerTxn(amountSen = 5_000L, id = 9L)
        val row = DetailRow(
            row = FeedRow(txn, identityKey = txn.merchantKey, displayName = MERCHANT),
            categoryName = "Groceries",
            captureTitle = "DuitNow Payment",
            captureText = "You have paid RM50.00 to TAN WEI MING",
            captureBigText = null,
            matchedRuleId = "DUITNOW-PAID-V1",
            packVersion = 7,
        )
        val chooser = ChooserRead(categories = listOf(Category(id = 1L, name = "Groceries", iconKey = "cart", sortOrder = 0)))
        compose.setContent {
            PingedTheme { DetailScreenContent(DetailRead(row, chooser), storageUnavailable = false, onBack = {}, zone = ZoneOffset.UTC) }
        }
        // The merged node: the clickable box that draws the edge, not its label.
        val image = compose.onNodeWithText(DELETE).performScrollTo().captureToImage().toPixelMap()
        // Clear of the 2dp corners.
        val inset = (3 * density).roundToInt()
        val columns = (inset until image.width - inset)
        val inked = columns.count { x -> (0 until 4).any { y -> nearest(image[x, y]) == Stamp } }
        val share = inked.toFloat() / columns.count()
        assertTrue("%.0f%% of Delete's top edge is inked".format(share * 100), share > 0.97f)
    }

    // ---- what is drawn ------------------------------------------------------

    /** What a sheet's scrim dims: the [Paper] every screen behind a sheet is painted in. */
    @Composable private fun Behind() = Box(Modifier.fillMaxSize().background(Paper))

    /** The screen as the user sees it: `captureToImage` cannot find a sheet's dialog (see `SheetChromeTest`). */
    private fun screen(): PixelMap {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        return shot.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap().toPixelMap()
    }

    /** How many of [node]'s pixels on [screen] are nearest each token. */
    private fun tally(screen: PixelMap, node: SemanticsNode): Tally {
        val at = node.positionOnScreen
        val x0 = at.x.roundToInt()
        val y0 = at.y.roundToInt()
        val counts = mutableMapOf<Color, Int>()
        for (y in y0 until y0 + node.size.height) for (x in x0 until x0 + node.size.width) {
            counts.merge(nearest(screen[x, y]), 1, Int::plus)
        }
        return Tally(counts)
    }

    private class Tally(val counts: Map<Color, Int>) {
        fun share(c: Color): Float = (counts[c] ?: 0).toFloat() / counts.values.sum()
        override fun toString() = TOKENS.entries.joinToString { (name, c) -> "$name %.0f%%".format(share(c) * 100) }
    }

    private fun nearest(c: Color): Color = TOKENS.values.minBy { t ->
        val r = c.red - t.red
        val g = c.green - t.green
        val b = c.blue - t.blue
        r * r + g * g + b * b
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private companion object {
        val TOKENS = linkedMapOf("Paper" to Paper, "Ink" to Ink, "Stamp" to Stamp, "Muted" to Muted)
    }
}
