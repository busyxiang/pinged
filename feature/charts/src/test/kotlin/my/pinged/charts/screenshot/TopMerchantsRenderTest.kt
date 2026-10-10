package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.TopMerchants
import my.pinged.charts.model.MonthTrust
import my.pinged.charts.model.merchants
import my.pinged.data.dao.MerchantTotal
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The top-merchants ranking (#92), drawn from constructed totals at the
 * artboard's 390dp on the lower ground.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The artboard's width; Robolectric's default screen is narrower than it.
@Config(qualifiers = "w390dp-h844dp")
class TopMerchantsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun of(vararg rows: Triple<String, Long, Int>) =
        rows.map { (name, sen, count) -> MerchantTotal(name.uppercase(Locale.ROOT), name, "MYR", sen, count) }

    /** The artboard's five, and three more past them. */
    private val typical = of(
        Triple("Grab", 31_240L, 18), Triple("99 Speedmart", 26_815L, 11), Triple("Shell", 21_000L, 4),
        Triple("foodpanda", 18_690L, 9), Triple("Lotus's", 17_430L, 3), Triple("Watsons", 6_420L, 2),
        Triple("Mr DIY", 3_380L, 5), Triple("Tealive", 1_250L, 1),
    )

    @Test fun aTypicalRanking() = capture("merchants_typical") { TopMerchants(merchants(typical, MonthTrust(0))) }

    @Test fun anUntrustedMonthGreysEveryTotalAndCount() = capture("merchants_untrusted") {
        TopMerchants(merchants(typical, MonthTrust(notCaptured = 3)))
    }

    /** Past the top five, then the rest, then the refund on its own row, signed. */
    @Test fun aRefundSortsLastAndALongNameEnds() = capture("merchants_negative_long_name") {
        TopMerchants(
            merchants(
                of(
                    Triple("Restoran Yuen Kee Home Town Cafe Sungai Buloh", 8_450L, 6),
                    Triple("Grab", 3_120L, 2), Triple("Shell", 2_800L, 1), Triple("Watsons", 2_150L, 1),
                    Triple("Mr DIY", 1_990L, 2), Triple("Tealive", 1_250L, 1),
                    Triple("Shopee", -4_500L, 1),
                ),
                MonthTrust(0),
            ),
        )
    }

    /** A month whose every merchant netted negative still draws them. */
    @Test fun onlyRefunds() = capture("merchants_only_refunds") {
        TopMerchants(merchants(of(Triple("Shopee", -4_500L, 1), Triple("Lazada", -1_290L, 1)), MonthTrust(0)))
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            PingedTheme {
                Box(Modifier.testTag(TAG).width(390.dp).background(Card).padding(bottom = 16.dp)) { content() }
            }
        }
        compose.onNodeWithTag(TAG).captureRoboImage(golden(name))
    }

    private companion object {
        const val TAG = "section"
    }
}
