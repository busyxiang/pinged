package my.pinged.charts.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import my.pinged.ui.ringgit
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Mono
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Paper
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * §8: the numerals are IBM Plex Mono, verified in a screenshot.
 *
 * A width or bounds assertion cannot see a silent fallback: Plex Mono's digits
 * advance 600.0 per em and the system `monospace` (DroidSansMono, bundled in
 * Robolectric's native runtime) 600.1, so the two lay out identically (#71).
 * So this draws the same amount in [Mono] and in [FontFamily.Monospace] and
 * asserts the pixels differ. If `Mono` ever stops loading Plex -- or is pointed
 * at the system face -- the two renders match and this fails.
 *
 * The [Mono] render is also the golden `verifyRoborazziDebug` compares, so a
 * change of face that still differs from DroidSansMono fails there instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonoControlRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anAmountIsDrawnInPlexMonoAndNotInTheSystemMonospace() {
        var family by mutableStateOf<FontFamily>(Mono)
        compose.setContent {
            // Constructed, and every digit appears once.
            Text(
                text = ringgit(123_456_789_00L),
                style = MonoNumerals.copy(fontFamily = family, fontSize = 32.sp, color = Ink),
                modifier = Modifier.testTag(AMOUNT).background(Paper).padding(8.dp),
            )
        }

        val plex = compose.onNodeWithTag(AMOUNT).captureToImage()
        compose.onNodeWithTag(AMOUNT).captureRoboImage(golden("mono_amount"))

        family = FontFamily.Monospace
        compose.waitForIdle()
        val system = compose.onNodeWithTag(AMOUNT).captureToImage()

        val differing = differingPixels(plex, system)
        assertTrue(
            "Mono rendered the same pixels as FontFamily.Monospace: Plex Mono did not load, " +
                "or Mono no longer names it ($differing pixels differ)",
            differing > 0,
        )
    }

    /** Pixels that differ; a size mismatch counts every pixel. */
    private fun differingPixels(a: ImageBitmap, b: ImageBitmap): Int {
        if (a.width != b.width || a.height != b.height) return a.width * a.height
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (pa[x, y] != pb[x, y]) n++
        return n
    }

    private companion object {
        const val AMOUNT = "amount"
    }
}
