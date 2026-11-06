package my.pinged.charts

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.monthView
import my.pinged.data.NamedSource
import my.pinged.data.SourcesLine
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.MonthSplit
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
 * the hero total and above the grid, its `NO APP IS READ YET` opening
 * Capture sources, and its unreadable names Could not read (#368). Which apps it names is `SourcesLineTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesLineChartsTest {

    @get:Rule
    val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)

    private fun draw(
        sources: SourcesLine,
        onOpenSources: () -> Unit = {},
        onOpenUnread: (NamedSource) -> Unit = {},
        captured: Set<LocalDate> = (1..30).map { september.atDay(it) }.toSet(),
        net: Long = 84_250L,
        split: List<MonthSplit> = emptyList(),
    ) {
        val read = chartsRead(
            month = september,
            today = LocalDate.of(2026, 10, 5),
            historyStart = september.atDay(1),
            captured = captured,
            monthTotals = listOf(CurrencyTotal("MYR", net)),
            monthSplit = split,
            sources = sources,
        )
        compose.setContent {
            PingedTheme {
                ChartsScreenContent(
                    september,
                    ChartsState.Month(monthView(read)),
                    unavailable = false,
                    sourcesLine = SourcesLineActions(openSources = onOpenSources, openUnread = onOpenUnread),
                )
            }
        }
    }

    private fun on(pkg: String, label: String) = CaptureSource(pkg, label, enabled = true, firstSeenAt = 0L)

    /**
     * After the hero's own lines, so `MAY BE INCOMPLETE` stays beside the
     * figure it qualifies (#73): a month with every line the hero has, its
     * split and its not-captured caveat.
     */
    @Test fun theLineIsDrawnUnderTheHerosCaveatsAndAboveTheGrid() {
        draw(
            SourcesLine.of(listOf(on(TNG, "Touch 'n Go"), on(MAE, "MAE")), setOf(TNG, MAE), emptySet(), byHand = true, packLabels = emptyMap()),
            captured = (2..30).map { september.atDay(it) }.toSet(),
            net = -1_000L,
            split = listOf(MonthSplit("MYR", spentSen = 4_000L, cameBackSen = 5_000L)),
        )

        val line = compose.onNodeWithText("FROM MAE AND TOUCH 'N GO, AND BY HAND").getUnclippedBoundsInRoot()
        val split = compose.onNodeWithText("CAME BACK", substring = true).getUnclippedBoundsInRoot()
        val incomplete = compose.onNodeWithText("MAY BE INCOMPLETE", substring = true).getUnclippedBoundsInRoot()
        val grid = compose.onNodeWithTag(dayTag(september.atDay(1)), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("The sources line is not under the hero's split", line.top >= split.bottom)
        assertTrue("The sources line is between the figure and its not-captured caveat", line.top >= incomplete.bottom)
        assertTrue("The sources line is not above the grid and the bars", line.bottom <= grid.top)
    }

    @Test fun aPastMonthWithEveryDayCapturedSaysMonthEnded() {
        draw(SourcesLine.of(emptyList(), emptySet(), emptySet(), byHand = false, packLabels = emptyMap()))

        compose.onNodeWithText("MONTH ENDED").assertExists()
        compose.onNodeWithText("MONTH COMPLETE").assertDoesNotExist()
    }

    @Test fun noAppIsReadYetOpensCaptureSources() {
        var opened = 0
        draw(SourcesLine.of(emptyList(), emptySet(), emptySet(), byHand = false, packLabels = emptyMap()), onOpenSources = { opened++ })

        compose.onNodeWithText("NO APP IS READ YET").performClick()
        assertEquals("NO APP IS READ YET did not open Capture sources", 1, opened)
    }

    /**
     * #368: each unreadable name is a labelled action that opens Could not
     * read for that app; a name in the `FROM` list is not, and the line
     * around them opens nothing. The tap on the name itself is
     * `UnreadFromSourcesLineTest`'s, on a device: it lands by where the text
     * lays the name out.
     */
    @Test fun anUnreadableNameOpensCouldNotReadForItsApp() {
        var sources = 0
        val opened = mutableListOf<NamedSource>()
        draw(
            SourcesLine.of(
                listOf(on(TNG, "Touch 'n Go"), on(GRAB, "GrabPay"), on(SHOPEE, "ShopeePay")),
                setOf(TNG),
                emptySet(),
                byHand = false,
                packLabels = emptyMap(),
            ),
            onOpenSources = { sources++ },
            onOpenUnread = { opened += it },
        )
        val line = compose.onNodeWithText("FROM TOUCH 'N GO. GRABPAY AND SHOPEEPAY ARE ON BUT PINGED CANNOT READ THEM YET")
        val actions = line.fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

        assertEquals(
            "The unreadable names are not each an action naming what it does",
            listOf(openUnreadLabel("GRABPAY"), openUnreadLabel("SHOPEEPAY")),
            actions.map { it.label },
        )
        actions.first { it.label == openUnreadLabel("GRABPAY") }.action()
        assertEquals("GRABPAY did not open Could not read for GrabPay", listOf(NamedSource(GRAB, "GRABPAY")), opened)

        line.performClick()
        assertEquals("A line with an app to name opened Capture sources", 0, sources)
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
        const val SHOPEE = "com.shopeepay.my"
    }
}
