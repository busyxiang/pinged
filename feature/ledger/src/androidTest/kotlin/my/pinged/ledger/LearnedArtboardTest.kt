package my.pinged.ledger

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.LearnedMerchant
import my.pinged.ledger.ArtboardProbe.bounds
import my.pinged.ledger.ArtboardProbe.close
import my.pinged.ledger.ArtboardProbe.inked
import my.pinged.ledger.ArtboardProbe.layoutOf
import my.pinged.ledger.ArtboardProbe.px
import my.pinged.ledger.ArtboardProbe.settledScreen
import my.pinged.ledger.learned.DELETE
import my.pinged.ledger.learned.LEARNED_MERCHANTS
import my.pinged.ledger.learned.LearnedContent
import my.pinged.ledger.learned.LearnedState
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test

/**
 * #171: the learned merchants list against `design/Rules.dc.html`'s
 * MERCHANTS tab. The tabs and each row's pattern line wait on #150 and #151;
 * this is the title and the row the board draws under them.
 */
class LearnedArtboardTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theTitleIsSerif22() {
        learned()
        val style = layoutOf(compose.onNodeWithText(LEARNED_MERCHANTS, useUnmergedTree = true)).layoutInput.style
        assertEquals("The title's family", Display, style.fontFamily)
        assertEquals("The title's size", 22.sp, style.fontSize)
    }

    @Test fun aNameIsKarlaMediumAt14Point5() {
        learned()
        val style = layoutOf(compose.onNodeWithText(MR_DIY.name, useUnmergedTree = true)).layoutInput.style
        assertEquals("The name's family", Body, style.fontFamily)
        assertEquals("The name's size", 14.5.sp, style.fontSize)
        assertEquals("The name's weight", FontWeight.Medium, style.fontWeight)
    }

    /** The board's chip: a 1dp Border box 6dp out from the category's text. */
    @Test fun theCategoryIsABorderedChip() {
        learned()
        val text = bounds(compose.onNodeWithText(MR_DIY.categoryName, useUnmergedTree = true))
        val style = layoutOf(compose.onNodeWithText(MR_DIY.categoryName, useUnmergedTree = true)).layoutInput.style
        assertEquals("The chip's family", Body, style.fontFamily)
        val name = bounds(compose.onNodeWithText(MR_DIY.name, useUnmergedTree = true))
        assertTrue("The chip is not on the name's line, right of it", text.left > name.right && text.top < name.bottom)
        val screen = settledScreen()
        val y = text.top + text.height / 2
        val boxed = (text.left - px(7.5f, density)..text.left - px(5f, density)).any { inked(screen[it, y], Border) }
        assertTrue("No Border box 6dp left of the category", boxed)
    }

    @Test fun deleteIsAStampTrashIconWithA44dpTarget() {
        var deleted = false
        learned(onDelete = { deleted = true })
        compose.onNodeWithText(DELETE).assertDoesNotExist()
        val target = compose.onNodeWithContentDescription(DELETE)
        target.assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
        val at = bounds(target)
        val screen = settledScreen()
        val stamped = (at.top until at.bottom).any { y -> (at.left until at.right).any { close(screen[it, y], Stamp) } }
        assertTrue("Nothing in the delete target is drawn in Stamp", stamped)
        // The glyph is 17dp: Stamp only within 8.5dp of the target's centre, either way.
        val cx = at.left + at.width / 2
        val cy = at.top + at.height / 2
        val reach = px(9f, density)
        val stray = (at.top until at.bottom).any { y ->
            (at.left until at.right).any { x -> (abs(x - cx) > reach || abs(y - cy) > reach) && close(screen[x, y], Stamp) }
        }
        assertTrue("Stamp drawn outside a 17dp glyph", !stray)
        target.performClick()
        compose.waitForIdle()
        assertTrue("The trash icon did not delete", deleted)
    }

    private fun learned(onDelete: (LearnedMerchant) -> Unit = {}) {
        compose.setContent {
            PingedTheme { LearnedContent(LearnedState(loaded = true, items = listOf(MR_DIY)), onBack = {}, onDelete = onDelete) }
        }
        compose.waitForIdle()
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun abs(n: Int) = kotlin.math.abs(n)

    private companion object {
        val MR_DIY = LearnedMerchant("MR DIY", "Hardware shop", 4L, "Shopping", payments = 3)
    }
}
