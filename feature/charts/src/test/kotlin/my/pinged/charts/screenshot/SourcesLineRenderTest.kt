package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.charts.SourcesLineText
import my.pinged.data.SourcesLine
import my.pinged.data.entity.CaptureSource
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PingedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * The sources line under the hero (#365), at CI's 320dp width with the hero's
 * 20dp sides: it wraps and never truncates a name.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourcesLineRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun on(pkg: String, label: String) = CaptureSource(pkg, label, enabled = true, firstSeenAt = 0L)

    /** Charts passes `onOpenUnread`, so the unreadable names are drawn as the tap targets it ships (#368). */
    private fun capture(name: String, line: SourcesLine) {
        compose.setContent {
            PingedTheme {
                Box(Modifier.testTag(LINE).width(320.dp).background(Paper).padding(horizontal = 20.dp, vertical = 6.dp)) {
                    SourcesLineText(line, onOpenUnread = {})
                }
            }
        }
        compose.onNodeWithTag(LINE).captureRoboImage(golden(name))
    }

    @Test fun threeAppsAndByHandWrap() = capture(
        "sources_line_three_apps",
        SourcesLine.of(
            listOf(on(TNG, "Touch 'n Go eWallet"), on(MAE, "Maybank MAE"), on(GRAB, "GrabPay")),
            setOf(TNG, MAE, GRAB),
            emptySet(),
            byHand = true,
        ),
    )

    @Test fun twoUnreadableApps() = capture(
        "sources_line_unreadable",
        SourcesLine.of(
            listOf(on(TNG, "Touch 'n Go eWallet"), on(MAE, "Maybank MAE"), on(GRAB, "GrabPay"), on(SHOPEE, "ShopeePay")),
            setOf(TNG, MAE),
            emptySet(),
            byHand = false,
        ),
    )

    private companion object {
        const val LINE = "sourcesLine"
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
        const val SHOPEE = "com.shopeepay.my"
    }
}
