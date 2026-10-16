package my.pinged.ledger

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import my.pinged.ledger.ArtboardProbe.bounds
import my.pinged.ledger.ArtboardProbe.close
import my.pinged.ledger.ArtboardProbe.isDp
import my.pinged.ledger.ArtboardProbe.layoutOf
import my.pinged.ledger.ArtboardProbe.lineCentre
import my.pinged.ledger.ArtboardProbe.px
import my.pinged.ledger.ArtboardProbe.settledScreen
import my.pinged.ledger.settings.EXPORT_TITLE
import my.pinged.ledger.settings.ExportReceiptSheet
import my.pinged.ledger.settings.SettingsState
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Mono
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import kotlin.math.abs

/**
 * #171: the Export sheet against `design/Export.dc.html`, minus the CSV half
 * and the range and exclusions `ExportSheet`'s KDoc keeps off this card. The
 * one option is the board's chosen card, so it takes the chosen card's Stamp.
 */
class ExportArtboardTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theTitleIsTheSerifExport() {
        export()
        // Merged, so the button's own "Export" is its clickable parent and only the title has no click.
        val title = compose.onNode(hasText(EXPORT_TITLE) and hasClickAction().not())
        val style = layoutOf(title).layoutInput.style
        assertEquals("The title's family", Display, style.fontFamily)
        assertEquals("The title's size", 23.sp, style.fontSize)
    }

    /** 14dp of padding inside a 1.5dp border: Stamp lies between 14 and 16dp left of the name. */
    @Test fun theOptionIsACardOutlinedInStamp() {
        export()
        val name = bounds(compose.onNodeWithText(OPTION, useUnmergedTree = true))
        val screen = settledScreen()
        val y = name.top + name.height / 2
        val outlined = (name.left - px(16.5f, density)..name.left - px(13.5f, density)).any { close(screen[it, y], Stamp) }
        assertTrue("No Stamp border 14dp left of the option's name", outlined)
    }

    @Test fun theOptionIsNamedInKarlaMediumAt15Point5AndTaggedJson() {
        export()
        val style = layoutOf(compose.onNodeWithText(OPTION, useUnmergedTree = true)).layoutInput.style
        assertEquals("The option's family", Body, style.fontFamily)
        assertEquals("The option's size", 15.5.sp, style.fontSize)
        assertEquals("The option's weight", FontWeight.Medium, style.fontWeight)
        val tag = compose.onNodeWithText("JSON", useUnmergedTree = true)
        assertEquals("The format tag's family", Mono, layoutOf(tag).layoutInput.style.fontFamily)
        val name = bounds(compose.onNodeWithText(OPTION, useUnmergedTree = true))
        val at = bounds(tag)
        // The name is weighted, so its bounds run up to the tag.
        assertTrue("The tag is not right of the name", at.left >= name.right)
        assertTrue("The tag is not on the name's line", at.top < name.bottom && at.bottom > name.top)
    }

    @Test fun theActionIsA52dpStampButton() {
        export()
        val button = compose.onNode(hasText(ACTION) and hasClickAction()).performScrollTo()
        val at = bounds(button)
        assertTrue("The button is ${at.height / density}dp tall, not 52", isDp(at.height, 52f, density))
        val width = screenWidth
        assertTrue("The button is ${at.width}px of $width, not margin to margin", isDp(width - at.width, 40f, density))
        val screen = settledScreen()
        val fill = screen[at.left + px(6f, density), at.top + at.height / 2]
        assertTrue("The button is filled $fill, not Stamp", close(fill, Stamp))
        val label = layoutOf(compose.onNode(hasText(ACTION) and hasClickAction().not() and hasParent(hasClickAction()), useUnmergedTree = true)).layoutInput.style
        assertEquals("The button's label", Paper, label.color)
    }

    @Test fun theFootnoteIsCentred() {
        export()
        val note = compose.onNodeWithText(FOOTNOTE, useUnmergedTree = true).performScrollTo()
        val width = screenWidth
        val centre = lineCentre(note)
        assertTrue("The footnote is centred at $centre, not ${width / 2}", abs(centre - width / 2f) <= 2f)
    }

    private fun export() {
        compose.setContent {
            PingedTheme {
                Box(Modifier.fillMaxSize().background(Paper))
                ExportReceiptSheet(state = SettingsState(loaded = true, exportRows = 112), onPick = {}, onCancel = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    /** The sheet is a second root, so the activity's own root is not the only one to ask. */
    private val screenWidth get() = compose.activity.resources.displayMetrics.widthPixels

    private companion object {
        const val OPTION = "Everything"
        const val ACTION = "Export"
        const val FOOTNOTE = "YOU CHOOSE WHERE IT GOES · PINGED CANNOT UPLOAD IT ANYWHERE"
    }
}
