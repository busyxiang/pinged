package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.RhythmGridSection
import my.pinged.charts.model.RhythmGrid
import my.pinged.charts.model.rhythmGrid
import my.pinged.data.LocalDates
import my.pinged.data.dao.DayTotal
import my.pinged.ui.RINGGIT
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * The daily rhythm grid's goldens (#88): the composable drawn from fixed,
 * constructed totals on the artboard's Card ground, at a fixed width so the
 * picture does not depend on the window. The window is a 360dp phone's:
 * Robolectric's default is 320dp, which would squeeze the legend's column.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp")
class RhythmGridRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private fun sep(day: Int): LocalDate = september.atDay(day)

    private fun grid(
        sen: Map<Int, Long>,
        today: LocalDate = LocalDate.of(2026, 10, 5),
        historyStart: LocalDate = LocalDate.of(2026, 8, 1),
        notCaptured: Set<Int> = emptySet(),
    ): RhythmGrid = rhythmGrid(
        september,
        today,
        historyStart,
        captured = (1..30).filter { it !in notCaptured && sep(it) >= historyStart }.map(::sep).toSet(),
        dayTotals = sen.map { (d, s) -> DayTotal(LocalDates.of(sep(d)), RINGGIT, s) },
    )

    private fun capture(grid: RhythmGrid, name: String) {
        compose.setContent {
            PingedTheme {
                RhythmGridSection(
                    grid,
                    onDayClick = {},
                    modifier = Modifier.testTag(GRID).width(360.dp).background(Card),
                )
            }
        }
        compose.onNodeWithTag(GRID).captureRoboImage(golden(name))
    }

    /** The artboard's September, every amount as it draws them. */
    @Test fun aTypicalMonth() = capture(grid(ARTBOARD_SEPTEMBER), "grid_typical")

    /**
     * Every state at once: spent at all four steps, nothing spent, net
     * negative, not captured, today, blank after today, blank before the
     * history start (the 3rd), and the footer that explains it.
     */
    @Test fun aMonthWithEveryState() = capture(
        grid(
            sen = mapOf(4 to 3_250L, 5 to 11_840L, 7 to 9_620L, 8 to 21_480L, 9 to -26_400L, 11 to 4_130L, 15 to 6_000L),
            today = sep(15),
            historyStart = sep(4),
            notCaptured = setOf(12, 13),
        ),
        "grid_every_state",
    )

    /** Every spent day the same: one swatch, `EVERY DAY YOU SPENT`. */
    @Test fun aFlatMonth() = capture(
        grid((1..30).filter { it % 3 != 0 }.associateWith { 1_200L }),
        "grid_flat",
    )

    /** The history start on the 7th: blank before it, and `Nothing before 7 September 2026`. */
    @Test fun theHistoryStartsMonth() = capture(
        grid(ARTBOARD_SEPTEMBER.filterKeys { it >= 7 }, historyStart = sep(7)),
        "grid_history_start",
    )

    private companion object {
        const val GRID = "grid"

        /** The artboard's `design/Charts.dc.html` September, in sen; the missing days spent nothing. */
        val ARTBOARD_SEPTEMBER = mapOf(
            1 to 3_250L, 2 to 11_840L, 4 to 9_620L, 5 to 21_480L, 6 to 8_760L, 7 to 4_130L,
            8 to 6_290L, 9 to 15_540L, 10 to 2_870L, 11 to 17_430L, 12 to 23_650L, 14 to 5_820L,
            15 to 18_990L, 16 to 7_410L, 17 to 3_360L, 18 to 14_280L, 19 to 19_840L, 20 to 4_520L,
            22 to 8_870L, 23 to 12_150L, 24 to 5_230L, 25 to 21_000L, 26 to 17_560L, 27 to 6_340L,
            29 to 9_720L, 30 to 4_780L,
        )
    }
}
