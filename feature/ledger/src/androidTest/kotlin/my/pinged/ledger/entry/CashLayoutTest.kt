package my.pinged.ledger.entry

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import my.pinged.ledger.PIXEL_WINDOW
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #272: Cash entry's wrapped chips and a correction's keyboard, measured on
 * a device. The chips are measured against the screen the test runs on,
 * which is CI's 320x640 under `emu --ci-size`, where they were cut off.
 */
class CashLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun draw(state: ManualEntryState) = compose.setContent {
        PingedTheme {
            ManualEntryScreenContent(state = state, onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {})
        }
    }

    /** Every chip and More inside the row's 20dp margins: wrapped, none cut off. */
    @Test fun theChipsWrapWithinTheScreen() {
        // The longest name selected, so it takes the fourth place.
        val longest = CHIPS.maxBy { it.name.length }
        draw(ManualEntryState(categories = CHIPS, draft = ManualDraft(category = CategoryChoice(longest.id, ChosenBy.TAPPED))))
        compose.waitForIdle()
        val edge = compose.onRoot().getBoundsInRoot().right - 20.dp
        val shown = shownChips(CHIPS, longest.id)
        assertTrue("The longest name is not drawn", longest in shown)
        for (name in shown.map { it.name }) {
            val right = compose.onNode(hasText(name) and isSelectable()).assertIsDisplayed().getBoundsInRoot().right
            assertTrue("$name's chip ends at $right, past $edge", right <= edge + 0.5.dp)
        }
        val more = compose.onNodeWithText(MORE).assertIsDisplayed().getBoundsInRoot().right
        assertTrue("More ends at $more, past $edge", more <= edge + 0.5.dp)
    }

    /**
     * A correction opens with no keyboard, and Delete payment inside
     * [PIXEL_WINDOW]. The keyboard check is what guards the focus: the
     * window is a box on whatever screen runs the test, so its bounds are
     * layout, not what the device displays. Watched for [KEYBOARD_WAIT]: CI's
     * Gboard took 455ms to show (`DeleteReachesTheLedgerTest`).
     *
     * Not CI's window: at 320x640 Delete's top is about 690dp down, in a
     * window of 564dp under the bars, with no keyboard at all -- the chips
     * take three rows there and Save's band 114dp.
     */
    @Test fun aCorrectionOpensWithNoKeyboardAndDeleteInAPixelsWindow() {
        val draft = ManualDraft(amountSen = 1_250L, category = CategoryChoice(CHIPS.first().id, ChosenBy.TAPPED))
        val state = ManualEntryState(categories = CHIPS, editing = 7, loaded = draft, draft = draft)
        compose.setContent {
            PingedTheme {
                Box(Modifier.requiredSize(PIXEL_WINDOW).testTag(WINDOW)) {
                    Box(Modifier.imePadding()) {
                        ManualEntryScreenContent(state = state, onAmount = {}, onMerchant = {}, onNote = {}, onSave = {}, onBack = {}, onSaved = {})
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + KEYBOARD_WAIT
        while (System.currentTimeMillis() < deadline && !keyboardShown()) Thread.sleep(100)
        assertFalse("A correction opened with the keyboard up", keyboardShown())
        compose.waitForIdle()
        val window = compose.onNodeWithTag(WINDOW).getBoundsInRoot()
        val delete = compose.onNodeWithText(DELETE_PAYMENT).getBoundsInRoot()
        assertTrue("Delete payment ends at ${delete.bottom}, below the window's ${window.bottom}", delete.bottom <= window.bottom)
    }

    private fun keyboardShown(): Boolean {
        var shown = false
        compose.runOnUiThread {
            shown = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private companion object {
        const val KEYBOARD_WAIT = 2_000L
        const val WINDOW = "pixel-window"
        val CHIPS = SEED_CHIPS
    }
}
