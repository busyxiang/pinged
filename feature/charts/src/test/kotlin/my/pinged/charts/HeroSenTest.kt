package my.pinged.charts

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.dao.CurrencyTotal
import my.pinged.ui.theme.Muted
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
 * `Charts.dc.html` draws the hero's `.30` smaller and in Muted, as Spending's
 * hero does, so the two totals of one month read alike (#221).
 */
@RunWith(RobolectricTestRunner::class)
class HeroSenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun theHerosSenAreSmallerAndMuted() {
        val september = YearMonth.of(2026, 9)
        val read = chartsRead(
            month = september,
            today = LocalDate.of(2026, 9, 20),
            historyStart = september.atDay(1),
            captured = (1..19).map { september.atDay(it) }.toSet(),
            monthTotals = listOf(CurrencyTotal("MYR", 119_273L)),
        )
        compose.setContent {
            PingedTheme { ChartsScreenContent(YearMonth.of(2026, 9), ChartsState.Month(monthView(read)), unavailable = false) }
        }

        val text: AnnotatedString = compose.onNodeWithText("RM1,192.73").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        val sen = text.spanStyles.singleOrNull { text.text.substring(it.start, it.end) == ".73" }
        assertTrue("The hero's .73 is drawn at the ringgit's size: ${text.spanStyles}", sen != null)
        assertEquals(Muted, sen!!.item.color)
        assertTrue("The .73 is not smaller than the 44sp ringgit", sen.item.fontSize < 44.sp)
    }
}
