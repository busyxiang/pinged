package my.pinged.ledger.day

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.ledger.home.ChooserRead
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * `design/Day.dc.html`'s header (#228): the steppers to the neighbouring
 * days, the total with its sen smaller and Muted, and the capture line under
 * it. Drawn from a [DayRead] with no rows, so no database is opened.
 */
@RunWith(AndroidJUnit4::class)
class DayScreenContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theStepsNameTheNeighbouringDaysAndOpenThem() {
        val opened = mutableListOf<LocalDate>()
        draw(onStep = { opened += it })

        compose.onNodeWithText("THU 24").performScrollTo().performClick()
        compose.onNodeWithText("SAT 26").performScrollTo().performClick()

        assertEquals(
            "A step did not open the day it names",
            listOf(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 26)),
            opened,
        )
    }

    @Test fun todayHasNoStepIntoTomorrow() {
        draw(onStep = {}, today = FRIDAY)

        compose.onNodeWithText("THU 24").assertExists()
        assertEquals(
            "Today offered a step into a day that has not happened",
            0,
            compose.onAllNodes(hasText("SAT 26")).fetchSemanticsNodes().size,
        )
    }

    @Test fun theTotalsSenIsSmallerAndMuted() {
        draw()

        val total = compose.onNodeWithText("RM210.00").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        val sen = total.spanStyles.single { total.text.substring(it.start, it.end) == ".00" }.item
        assertEquals("The sen is not the board's 26 under a 46 total", 26.sp, sen.fontSize)
        assertEquals("The sen is not Muted", Muted, sen.color)
    }

    @Test fun theCaptureLineIsDrawnUnderTheTotal() {
        draw()

        compose.onNodeWithText("4 PAYMENTS · PINGED WAS WATCHING").assertExists()
    }

    private fun draw(onStep: ((LocalDate) -> Unit)? = null, today: LocalDate = FRIDAY.plusDays(20)) {
        compose.setContent {
            PingedTheme {
                DayScreenContent(
                    date = FRIDAY,
                    read = DayRead(
                        rows = emptyList(),
                        heading = DayHeading(
                            total = "RM210.00",
                            keptOut = null,
                            notWatching = false,
                            captureLine = "4 PAYMENTS · PINGED WAS WATCHING",
                        ),
                        chooser = ChooserRead(),
                    ),
                    storageUnavailable = false,
                    onBack = {},
                    onAssign = { _, _, _ -> },
                    onStep = onStep,
                    today = today,
                )
            }
        }
    }

    private companion object {
        /**
         * The board's day. A Friday: the board heads it "Thursday" and steps
         * to "WED 24" and "FRI 26", against its own scenario's Wednesday 30.
         */
        val FRIDAY: LocalDate = LocalDate.of(2026, 9, 25)
    }
}
