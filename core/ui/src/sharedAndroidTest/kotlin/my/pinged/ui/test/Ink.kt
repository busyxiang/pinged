package my.pinged.ui.test

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * How far the darkest pixel of this capture stands from its ground, the
 * commonest pixel, as the mean of its sRGB channels: the contrast its text
 * is drawn at. sRGB and not luminance, because a layer's alpha blends the
 * encoded channels, so only they scale linearly with it.
 *
 * Measured twice on one node, before and during a re-read in place (#285),
 * the second over the first is the alpha the content was dimmed to, whatever
 * the text's own colour and however its glyphs are anti-aliased: alpha
 * scales every pixel's distance from the ground by the same factor.
 */
fun ImageBitmap.ink(): Float {
    val pixels = toPixelMap()
    val counts = HashMap<Int, Int>()
    val lums = FloatArray(pixels.width * pixels.height)
    var i = 0
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            val colour = pixels[x, y]
            val lum = (colour.red + colour.green + colour.blue) / 3f
            lums[i++] = lum
            // Bucketed, so the ground's anti-aliased edge does not split it.
            val key = (lum * 255).toInt()
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    val ground = counts.maxBy { it.value }.key / 255f
    return lums.maxOf { kotlin.math.abs(ground - it) }
}
