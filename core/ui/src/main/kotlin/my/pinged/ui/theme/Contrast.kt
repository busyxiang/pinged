package my.pinged.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.2's contrast ratio, and the two thresholds this app holds itself to.
 *
 * In `main` rather than in a test source set because two different test suites
 * need it and they cannot see each other: the palette is guarded by a JVM unit
 * test, and what is actually *drawn* can only be guarded on a device. Copying
 * the formula into both is how the two would come to disagree about what a
 * ratio is.
 *
 * Nothing in the app draws with this. It is the rule the design system is
 * measured against, kept beside the colours it measures.
 */
object Contrast {

    /** WCAG 1.4.3, text against its background. */
    const val TEXT_MINIMUM = 4.5

    /** WCAG 1.4.11, a user-interface component or its states. */
    const val COMPONENT_MINIMUM = 3.0

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG 2.2's relative luminance, on sRGB channels. */
    private fun luminance(colour: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(colour.red) +
            0.7152 * channel(colour.green) +
            0.0722 * channel(colour.blue)
    }
}
