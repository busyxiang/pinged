package my.pinged.charts

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.SourcesLine
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.CaptureSource
import my.pinged.ui.theme.PingedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth

/**
 * The sources line on Charts (#365; spec 9.3, Hero): the read's line, under
 * the hero total and above the grid, and its `NO APP IS READ YET` opening
 * Capture sources. Which apps it names is `SourcesLineTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesLineChartsTest {

    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)

    private fun draw(sources: SourcesLine, onOpenSources: () -> Unit = {}) {
        val read = chartsRead(
            month = september,
            today = LocalDate.of(2026, 10, 5),
            historyStart = september.atDay(1),
            captured = (1..30).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 84_250L)),
            sources = sources,
        )
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    september,
                    ChartsState.Month(monthView(read)),
                    unavailable = false,
                    onOpenSources = onOpenSources,
                )
            }
        }
    }

    private fun on(pkg: String, label: String) = CaptureSource(pkg, label, enabled = true, firstSeenAt = 0L)

    @Test fun theLineIsDrawnUnderTheHeroAndAboveTheGrid() {
        draw(SourcesLine.of(listOf(on(TNG, "Touch 'n Go"), on(MAE, "MAE")), setOf(TNG, MAE), emptySet(), byHand = true))

        val line = compose.onNodeWithText("FROM MAE AND TOUCH 'N GO, AND BY HAND").getUnclippedBoundsInRoot()
        val total = compose.onNodeWithText("TOTAL SPENT").getUnclippedBoundsInRoot()
        val grid = compose.onNodeWithTag(dayTag(september.atDay(1)), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("The sources line is not under the hero", line.top > total.bottom)
        assertTrue("The sources line is not above the grid and the bars", line.bottom <= grid.top)
    }

    @Test fun aPastMonthWithEveryDayCapturedSaysMonthEnded() {
        draw(SourcesLine.of(emptyList(), emptySet(), emptySet(), byHand = false))

        compose.onNodeWithText("MONTH ENDED").assertExists()
        compose.onNodeWithText("MONTH COMPLETE").assertDoesNotExist()
    }

    @Test fun noAppIsReadYetOpensCaptureSources() {
        var opened = 0
        draw(SourcesLine.of(emptyList(), emptySet(), emptySet(), byHand = false), onOpenSources = { opened++ })

        compose.onNodeWithText("NO APP IS READ YET").performClick()
        assertEquals("NO APP IS READ YET did not open Capture sources", 1, opened)
    }

    @Test fun anUnreadableNameOpensNothingYet() {
        var opened = 0
        draw(
            SourcesLine.of(listOf(on(TNG, "Touch 'n Go"), on(GRAB, "GrabPay")), setOf(TNG), emptySet(), byHand = false),
            onOpenSources = { opened++ },
        )

        compose.onNodeWithText("FROM TOUCH 'N GO. GRABPAY IS ON BUT PINGED CANNOT READ IT YET").performClick()
        assertEquals("A line with an app to name opened Capture sources; its names are #368's", 0, opened)
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
    }
}
