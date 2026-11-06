package my.pinged.ledger.review

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import my.pinged.data.entity.StandingAnswer
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Spec 7.3's Always beside Undo, measured at CI's 320dp. On a device, not
 * under Robolectric, because it reads the device's type.
 *
 * Measured on emulator-5554 at 320dp, density 160: the bar is 280dp inside
 * its margins, Always 68dp and Undo 58dp at 12dp a side, which leaves the
 * line 154dp. `LEFT OUT · RM100.00` needs 151.5dp and keeps one line;
 * `LEFT OUT · RM99,999.99` needs 175.5dp and takes two. At Undo's own 16dp
 * a side the line would have 138dp and even RM100.00 would wrap. Two lines
 * of `MonoLabel` are 32dp, inside the buttons' 48dp, so a wrapped line does
 * not make the bar taller: that, the break at the `·`, and both buttons
 * whole on the bar are what this asserts.
 */
class UndoBarFitTest {
    @get:Rule val compose = createComposeRule()

    private var undoable by mutableStateOf(Undoable("ACCEPTED · RM100.00", emptyList()))

    @Test fun theLineAndBothButtonsFitAt320dp() {
        compose.setContent {
            PingedTheme {
                Box(Modifier.requiredSize(DpSize(320.dp, 640.dp))) {
                    ReviewInboxScreenContent(
                        cards = emptyList(),
                        storageUnavailable = false,
                        busy = null,
                        onBack = {},
                        onAnswer = { _, _ -> },
                        undoable = undoable,
                    )
                }
            }
        }
        compose.waitForIdle()
        val plain = compose.onNodeWithTag(UNDO_BAR).getBoundsInRoot()
        val lineCounts = mutableMapOf<String, Int>()
        for (said in listOf("LEFT OUT · RM100.00", "LEFT OUT · RM99,999.99")) {
            undoable = Undoable(said, emptyList(), always = AlwaysOffer(ANSWER, "ALWAYS LEAVING OUT · RM99,999.99"))
            compose.waitForIdle()
            val bar = compose.onNodeWithTag(UNDO_BAR).getBoundsInRoot()
            val always = compose.onNodeWithText(ALWAYS).getBoundsInRoot()
            val undo = compose.onNodeWithText(UNDO).getBoundsInRoot()
            val line = layoutOf(said)
            val lines = (0 until line.lineCount).map { said.substring(line.getLineStart(it), line.getLineEnd(it)).trim() }
            lineCounts[said] = lines.size
            Log.i(
                TAG,
                "'$said': bar ${bar.width} x ${bar.height}, line ${line.size.width}px of " +
                    "${line.multiParagraph.intrinsics.maxIntrinsicWidth}px unwrapped, Always ${always.width}, " +
                    "Undo ${undo.width}, lines $lines",
            )
            assertEquals("Always made the bar taller", plain.height, bar.height)
            assertTrue("'$said' is ${lines.size} lines: $lines", lines.size <= 2)
            if (lines.size == 2) assertTrue("'$said' breaks inside the amount: $lines", lines[0].endsWith("·"))
            assertTrue("Undo runs off the bar: $undo outside $bar", undo.right <= bar.right)
            assertTrue("Always overlaps Undo: $always, $undo", always.right <= undo.left)
        }
        assertEquals("RM100.00 wraps beside Always and Undo", 1, lineCounts["LEFT OUT · RM100.00"])
        // What the bar reads once Always is tapped: one button, the longest line.
        undoable = Undoable("ALWAYS LEAVING OUT · RM99,999.99", emptyList(), standing = 1L)
        compose.waitForIdle()
        assertEquals("The bar after Always is taller", plain.height, compose.onNodeWithTag(UNDO_BAR).getBoundsInRoot().height)
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        return layouts.single()
    }

    private companion object {
        const val TAG = "UndoBarFit"
        val ANSWER = StandingAnswer(matchedRuleId = "tng-reload-v1", sourcePackage = "my.com.tngdigital.ewallet", merchantKey = null, createdAt = 0L)
    }
}
