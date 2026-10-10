package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.NotInTotalBlock
import my.pinged.charts.model.KeptOut
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The "not in the total" block from fixed, constructed lines (#89; #81, Screenshots). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotInTotalRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun draw(lines: List<KeptOut>) = compose.setContent {
        PingedTheme {
            Box(Modifier.testTag(BLOCK).width(390.dp).background(Paper)) {
                NotInTotalBlock(lines)
            }
        }
    }

    @Test fun allThreeLines() {
        draw(
            listOf(
                KeptOut("Transfers, left out", "450.00"),
                KeptOut("You excluded", "3,880.00"),
                KeptOut("7 awaiting review", "341.90"),
            ),
        )
        compose.onNodeWithTag(BLOCK).captureRoboImage(golden("not_in_total_three_lines"))
    }

    @Test fun oneLine() {
        draw(listOf(KeptOut("1 awaiting review", "18.00")))
        compose.onNodeWithTag(BLOCK).captureRoboImage(golden("not_in_total_one_line"))
    }

    @Test fun noLinesDrawNoBlock() {
        draw(emptyList())
        compose.onNodeWithText("NOT IN THE TOTAL").assertDoesNotExist()
    }

    private companion object {
        const val BLOCK = "notInTotal"
    }
}
