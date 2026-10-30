package my.pinged.ledger.breakdown

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.data.LocalDates
import my.pinged.data.dao.BreakdownLine
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.NotInTotal
import my.pinged.data.dao.NotInTotalLine
import my.pinged.data.entity.Category
import my.pinged.ledger.home.ChooserRead
import my.pinged.ledger.home.FakeCategoryActions
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.ledgerTxn
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Breakdown (#342) at CI's 320x640, this module's Robolectric screen:
 * `design/Breakdown.dc.html`'s Food & Drinks in September, its lines summing
 * to the bar's RM842.50 and their counts to 56, with Restoran Nasi Kandar
 * open; the same month untrusted (`BreakdownUntrusted`); and a merchant whose
 * name is far longer than the line. Constructed data only: a golden is a
 * picture of its input, and the repository is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BreakdownRenderTest {
    @get:Rule val compose = createComposeRule()

    private val september = YearMonth.of(2026, 9)
    private val food = Category(id = 1L, name = "Food & Drinks", iconKey = "utensils", sortOrder = 0)
    private val kandar = LineKey.of("RESTORAN NASI KANDAR")

    private fun line(key: String?, name: String?, sen: Long, count: Int) = BreakdownLine(key, name, "MYR", sen, count)

    private val board = listOf(
        line("FOODPANDA", "foodpanda", 18_690L, 9),
        line("YUEN KEE", "Yuen Kee", 15_200L, 14),
        line("RESTORAN SRI MELAYU", "Restoran Sri Melayu Masakan Kampung Baru", 9_980L, 6),
        line(null, null, 9_600L, 7),
        line("ZUS COFFEE", "ZUS Coffee", 9_240L, 8),
        line("TEALIVE", "Tealive", 7_650L, 5),
        line("RESTORAN NASI KANDAR", "Restoran Nasi Kandar", 7_140L, 2),
        line("NASI LEMAK ANTARABANGSA", "Nasi Lemak Antarabangsa", 5_250L, 1),
        line("MCDONALDS", "McDonald’s", 3_990L, 3),
        line("DOMINOS", "Domino’s", -2_490L, 1),
    )

    private fun kandarRow(id: Long, sen: Long, day: Int) = FeedRow(
        ledgerTxn(sen, localDate = LocalDates.of(september.atDay(day)), categoryId = food.id, id = id).copy(
            merchantRaw = "RESTORAN NASI KANDAR",
            merchantDisplay = "Restoran Nasi Kandar",
            merchantKey = "RESTORAN NASI KANDAR",
        ),
        "RESTORAN NASI KANDAR",
        "Restoran Nasi Kandar",
    )

    private fun read(lines: List<BreakdownLine> = board, notCaptured: Int = 0, rows: Map<LineKey, List<FeedRow>> = mapOf(kandar to listOf(kandarRow(2L, 3_700L, 16), kandarRow(1L, 3_440L, 4)))) =
        BreakdownRead(
            category = food,
            month = september,
            today = LocalDate.of(2026, 10, 30),
            notCaptured = notCaptured,
            lines = lines,
            keptOut = listOf(NotInTotal(NotInTotalLine.PENDING, "MYR", 1, 4_500L)),
            firstHeld = 3L,
            rows = rows,
            chooser = ChooserRead(categories = listOf(food)),
        )

    @Test fun theBoard() = capture("breakdown", read(), setOf(kandar))

    @Test fun anUntrustedMonth() = capture("breakdown_untrusted", read(notCaptured = 1), setOf(kandar))

    @Test fun aLongMerchantName() = capture(
        "breakdown_long_name",
        read(
            lines = listOf(
                line("LONG", "Restoran Nasi Kandar Pelita Jalan Ampang Kuala Lumpur Cawangan Kedua", 123_450L, 123),
                line("ZUS COFFEE", "ZUS Coffee", 9_240L, 8),
            ),
            rows = emptyMap(),
        ),
        emptySet(),
    )

    private fun capture(name: String, read: BreakdownRead, expanded: Set<LineKey>) {
        compose.setContent {
            PingedTheme {
                BreakdownScreenContent(
                    month = september,
                    read = read,
                    expanded = expanded,
                    storageUnavailable = false,
                    onBack = {},
                    onGone = {},
                    onToggle = {},
                    categories = FakeCategoryActions(),
                    merchants = FakeMerchantActions(),
                )
            }
        }
        compose.onRoot().captureRoboImage(golden(name))
    }

    /**
     * The committed golden for [name], from the module directory: it must agree
     * with `roborazzi.outputDir` in this module's build file, as
     * `:feature:charts`' `golden` does with its own.
     */
    private fun golden(name: String): String = "src/test/screenshots/$name.png"
}
