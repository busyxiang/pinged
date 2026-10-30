package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.MoreMerchantsContent
import my.pinged.charts.model.MonthTrust
import my.pinged.charts.model.merchants
import my.pinged.data.dao.MerchantTotal
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `N more merchants` sheet (#344) at CI's 320dp: a September whose
 * ranking folds four merchants past the top five, one of them with a name
 * longer than the line, summing to RM176.40, and the same month untrusted.
 * Drawn as `EverythingElseRenderTest` draws its sheet, without the modal
 * window. Constructed data only: the repository is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-mdpi")
class MoreMerchantsRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun myr(key: String, name: String, sen: Long, count: Int) = MerchantTotal(key, name, "MYR", sen, count)

    private val september = listOf(
        myr("GRAB", "Grab", 31_240L, 18), myr("SPEEDMART", "99 Speedmart", 26_815L, 11),
        myr("SHELL", "Shell", 21_000L, 4), myr("FOODPANDA", "foodpanda", 18_690L, 9),
        myr("LOTUS", "Lotus's", 17_430L, 3), myr("WATSONS", "Watsons", 6_420L, 2),
        myr("KANDAR", "Restoran Nasi Kandar Pelita Jalan Ampang Cawangan Kedua", 5_840L, 3),
        myr("MRDIY", "Mr DIY", 3_380L, 5), myr("TEALIVE", "Tealive", 2_000L, 1),
        myr("SHOPEE", "Shopee", -4_500L, 2),
    )

    @Test fun theSheet() = capture("more_merchants", notCaptured = 0)

    @Test fun theSheetInAnUntrustedMonth() = capture("more_merchants_untrusted", notCaptured = 1)

    private fun capture(name: String, notCaptured: Int) {
        val ranked = merchants(september, MonthTrust(notCaptured))
        compose.setContent {
            PingedTheme {
                Box(Modifier.testTag(TAG).fillMaxWidth().background(Paper).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)) {
                    MoreMerchantsContent("SEPTEMBER 2026", requireNotNull(ranked.rest), ranked.greyed, onOpen = {}, onDismiss = {})
                }
            }
        }
        compose.onNodeWithTag(TAG).captureRoboImage(golden(name))
    }

    private companion object {
        const val TAG = "sheet"
    }
}
