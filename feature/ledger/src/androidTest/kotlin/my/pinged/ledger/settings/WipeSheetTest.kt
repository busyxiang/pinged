package my.pinged.ledger.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import my.pinged.ledger.theme.PingedTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * `design/Wipe.dc.html`'s body, **as behaviour and type rather than as
 * layout.** The counts carry one correction the artboard could not know -- see
 * [countsAreOmittedRatherThanGuessed].
 *
 * [WipeSheetBody] is what these drive, not [WipeSheet]: the latter is
 * [PingedTheme]'s sheet chrome plus this body, and a `ModalBottomSheet` inside
 * `createComposeRule` animates in through a window of its own. The chrome and
 * the row that opens it are covered instead by [WipeHostTest], which drives
 * the real screen.
 *
 * **What this suite cannot see.** The harness is a plain `Column` with the
 * 20dp [my.pinged.ledger.theme.ReceiptSheet] applies, which is enough for the
 * body to compose and for its children to sit in order -- and is not the sheet
 * on the phone. It has no `verticalScroll`, no modal window and no IME, so it
 * never runs short of height -- and a child measured at zero because the sheet
 * around it did is exactly the failure it cannot show: [WipeHostTest] records
 * `Delete` at 0px that way, in the real chrome, with every test here green.
 * Nothing below asserts a bound, a gap or an overlap either, and no screenshot
 * test exists in this repository (`Type.kt` records why), so every geometric
 * claim in `WipeSheet`'s KDoc is unguarded.
 *
 * Wrapping in `PingedTheme` matters for a narrower reason: without it every
 * `Text` that does not name its own family renders in the platform sans, and
 * the sheet's two such strings would drift from the rest of the app with
 * nothing failing.
 */
class WipeSheetTest {
    @get:Rule val compose = createComposeRule()

    private val counts = WipeCounts(txns = 112, captures = 1204, merchants = 38, months = 6)

    /**
     * Named arguments throughout. [WipeCounts] is four `Int`s in a row, so a
     * positional fixture reordered with the data class stays green while every
     * label points at the wrong number -- which is the failure
     * [theCountsSitAgainstTheirOwnLabels] exists to catch, and it cannot catch
     * it from a fixture that moved with the code.
     */
    private fun sheet(
        counts: WipeCounts? = this.counts,
        onConfirm: () -> Unit = {},
        onExportFirst: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) = compose.setContent {
        PingedTheme {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                WipeSheetBody(counts, onConfirm, onExportFirst, onDismiss)
            }
        }
    }

    @After fun restoreTheLocale() = Locale.setDefault(DEVICE)

    /**
     * The field is found by `hasSetTextAction` rather than by its label text:
     * the label is drawn as its own `Text` above the rule (the artboard has no
     * box around the field), so `onNodeWithText("TYPE DELETE TO CONFIRM")`
     * would match the label and `performTextInput` would fail on a node that
     * takes no text. There is exactly one text field in the sheet.
     */
    @Test fun deleteIsRefusedUntilTheWordIsTyped() {
        sheet()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText("Delete").assertIsEnabled()
    }

    /** Lowercase is not the word. Accepting it makes the friction decorative. */
    @Test fun theWordIsComparedExactly() {
        sheet()
        compose.onNode(hasSetTextAction()).performTextInput("delete")
        compose.onNodeWithText("Delete").assertIsNotEnabled()
    }

    /**
     * **The field shows the word it wants, in the casing it wants.**
     *
     * The artboard draws a ghosted `DELETE` inside the field and it is the
     * only place the required casing appears -- the label above says "TYPE
     * DELETE TO CONFIRM" in a style that is uppercase for every string on
     * this screen, so it cannot be read as an instruction about case.
     * `BasicTextField` has no placeholder of its own, which is how the first
     * version shipped without one.
     *
     * The second half matters as much: a placeholder still drawn once the
     * user types overlaps what they typed, because `decorationBox` stacks the
     * two in the same `Box`.
     */
    @Test fun theFieldShowsTheWordItWantsUntilOneIsTyped() {
        sheet()
        compose.onNodeWithText(CONFIRMATION).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("D")
        compose.onNodeWithText(CONFIRMATION).assertDoesNotExist()
    }

    /**
     * **The tap that destroys the ledger reaches the caller.**
     *
     * The enabled state and the callback are two separate lines in
     * `WipeSheetBody`, and [deleteIsRefusedUntilTheWordIsTyped] only reads the
     * first: with `onClick = {}` the word would arm a button whose only effect
     * is to stop looking disabled, and every other case here would pass.
     */
    @Test fun theArmedDeleteReachesTheCaller() {
        val confirmed = AtomicInteger(0)
        sheet(onConfirm = { confirmed.incrementAndGet() })

        compose.onNode(hasSetTextAction()).performTextInput(CONFIRMATION)
        compose.onNodeWithText("Delete").performClick()

        assertEquals("The armed Delete did not call onConfirm", 1, confirmed.get())
    }

    /**
     * **Ruling R23: the offer to take a copy first is on this sheet.**
     *
     * The first draft of this task dropped it, and the one moment a user is
     * about to destroy a ledger nothing has ever backed up is exactly when it
     * is worth making. Nothing else in the suite composes it, so deleting the
     * row outright left every case green.
     */
    @Test fun theOfferToExportFirstIsOnTheSheetAndHandsOff() {
        val offered = AtomicInteger(0)
        sheet(onExportFirst = { offered.incrementAndGet() })

        compose.onNodeWithText("Export it first").assertIsDisplayed().performClick()

        assertEquals("Export it first did not call onExportFirst", 1, offered.get())
    }

    /**
     * **Ruling R24: which number belongs to which row.**
     *
     * Asserting that `1,204` is on screen somewhere is not this claim:
     * swapping two `CountRow` arguments leaves every value present and every
     * label present, and the sheet then tells the user it is about to delete
     * 1,204 transactions and 112 notifications. Each row merges its label and
     * its value into one semantics node precisely so the pair can be asserted
     * as a pair -- see `CountRow`.
     */
    @Test fun theCountsSitAgainstTheirOwnLabels() {
        sheet()
        compose.onNodeWithText("Transactions").assert(hasText("112"))
        compose.onNodeWithText("Original notifications").assert(hasText("1,204"))
        compose.onNodeWithText("Merchants you taught").assert(hasText("38"))
        compose.onNodeWithText("Months of history").assert(hasText("6"))
    }

    /**
     * `String.format(Locale.ROOT, ...)`, pinned rather than asserted in a
     * comment. The emulator is en-US, so `Locale.getDefault()` produces the
     * same `1,204` here and every other case in this suite would stay green;
     * under a locale that groups with a full stop it produces `1.204`.
     */
    @Test fun theGroupingDoesNotFollowTheDeviceLocale() {
        Locale.setDefault(Locale.GERMANY)
        sheet()
        compose.onNodeWithText("Original notifications").assert(hasText("1,204"))
    }

    /**
     * `COUNT(*)` reported 5,000 rows on a database that could produce 2,200
     * (design 2.3), so on anything but a healthy ledger the itemisation would
     * overstate the loss. It is left out rather than guessed.
     */
    @Test fun countsAreOmittedRatherThanGuessed() {
        sheet(counts = null)
        compose.onNodeWithText("Transactions").assertDoesNotExist()
        compose.onNodeWithText("Pinged cannot count what it cannot read.").assertIsDisplayed()
    }

    private companion object {
        /** Restored after [theGroupingDoesNotFollowTheDeviceLocale] moves it. */
        val DEVICE: Locale = Locale.getDefault()

        /** The same string `WipeSheet.CONFIRMATION` is private for. */
        const val CONFIRMATION = "DELETE"
    }
}
