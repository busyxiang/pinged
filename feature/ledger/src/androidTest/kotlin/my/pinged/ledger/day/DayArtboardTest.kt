package my.pinged.ledger.day

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import my.pinged.ledger.ArtboardProbe.bounds
import my.pinged.ledger.ArtboardProbe.close
import my.pinged.ledger.ArtboardProbe.isDp
import my.pinged.ledger.ArtboardProbe.settledScreen
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * #228: the Day screen against `design/Day.dc.html`, where only drawn pixels
 * and laid-out bounds can say it matches. What the header says is
 * `DayScreenContentTest`'s, on the JVM.
 */
class DayArtboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val density get() = compose.activity.resources.displayMetrics.density

    /** The board's 9dp perforation under the capture line, Card above Paper's notches, and Card to the foot. */
    @Test fun aTornEdgeLeadsOntoCardThatRunsToTheFoot() {
        day()
        val edge = bounds(compose.onNodeWithTag(DAY_TORN_EDGE))
        val line = bounds(compose.onNodeWithText(CAPTURE_LINE))
        assertTrue("The edge is ${edge.height / density}dp tall, not 9", isDp(edge.height, 9f, density))
        assertTrue(
            "The edge is ${(edge.top - line.bottom) / density}dp under the capture line, not at least 20",
            edge.top - line.bottom >= 20 * density - 1.5f,
        )

        val screen = settledScreen()
        val band = (edge.left until edge.right).count { close(screen[it, edge.top + 1], Card) }
        assertTrue("The edge's top is not Card: $band of ${edge.width}px", band > edge.width * 0.9)
        val margin = (edge.left until edge.right).count { close(screen[it, edge.top - 4], Paper) }
        assertTrue("Over the edge is not Paper: $margin of ${edge.width}px", margin > edge.width * 0.9)

        val foot = bounds(compose.onRoot()).bottom - 4
        val ground = (edge.left until edge.right).count { close(screen[it, foot], Card) }
        assertTrue(
            "A day with no rows is not Card to the foot: $ground of ${edge.width}px",
            ground > edge.width * 0.9,
        )
    }

    /** The board's steps are each at least 44dp, the target a mis-tapped 20dp cell is for. */
    @Test fun eachStepIsAtLeast44dp() {
        day()
        listOf("THU 24", "SAT 26").forEach { label ->
            val step = bounds(compose.onNodeWithText(label))
            assertTrue("$label is ${step.height / density}dp tall", step.height >= 44 * density - 1.5f)
            assertTrue("$label is ${step.width / density}dp wide", step.width >= 44 * density - 1.5f)
        }
    }

    private fun day() {
        compose.setContent {
            PingedTheme {
                DayScreenContent(
                    date = DATE,
                    read = DayRead(
                        rows = emptyList(),
                        heading = DayHeading("RM210.00", keptOut = null, notWatching = false, captureLine = CAPTURE_LINE),
                        chooser = ChooserRead(),
                    ),
                    storageUnavailable = false,
                    onBack = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                    onStep = {},
                    today = DATE.plusDays(20),
                )
            }
        }
    }

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 9, 25)
        const val CAPTURE_LINE = "4 PAYMENTS · PINGED WAS WATCHING"
    }
}
