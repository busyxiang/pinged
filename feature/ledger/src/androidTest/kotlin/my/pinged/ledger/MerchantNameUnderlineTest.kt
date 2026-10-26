package my.pinged.ledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.LocalDates
import my.pinged.ledger.home.FakeMerchantActions
import my.pinged.ledger.home.MerchantOpening
import my.pinged.ledger.home.MerchantSheet
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.NAME_FIELD_TAG
import my.pinged.ledger.home.OpenMerchantSheet
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.PingedTheme
import my.pinged.ui.theme.READING_DELAY_MILLIS
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.abs

/**
 * The merchant sheet's NAME field is underlined in [Muted], as
 * `MerchantSheet.dc.html` and `LoadingSheet.dc.html` both draw it
 * (`1.5px solid #6b6459`), before its read and after.
 *
 * Read off the field's bottom two pixel rows, where only the underline is:
 * the line is 1.5dp drawn flush with the node's bottom, so its last row is
 * wholly inside it at any density, and the darkest pixel there is the
 * line's own colour.
 */
@RunWith(AndroidJUnit4::class)
class MerchantNameUnderlineTest {
    @get:Rule val compose = createComposeRule()

    private val opening = MerchantOpening("99SPEEDMART", "99 Speedmart", LocalDates.of(LocalDate.of(2026, 9, 14)))

    private fun draw(sheet: OpenMerchantSheet) {
        compose.mainClock.autoAdvance = false
        compose.setContent { PingedTheme { MerchantSheet(sheet, FakeMerchantActions()) } }
        // Frame by frame, and past the sheet's slide-in as well as the
        // reading delay: a capture of a sheet still entering failed
        // PixelCopy once in two runs.
        repeat(SETTLE_FRAMES) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
        }
    }

    @Test fun beforeItsReadTheNameIsUnderlinedInMuted() {
        draw(OpenMerchantSheet(opening))
        assertUnderlinedInMuted()
    }

    @Test fun onceReadTheNameIsUnderlinedInMuted() {
        draw(
            OpenMerchantSheet(
                opening,
                MerchantSheetState(
                    ownKey = opening.ownKey,
                    identityKey = opening.ownKey,
                    name = opening.name,
                    derivedName = opening.name,
                    txnCount = 11,
                    mergedInto = null,
                    members = emptyList(),
                    others = emptyList(),
                    suggested = emptySet(),
                ),
            ),
        )
        assertUnderlinedInMuted()
    }

    private fun assertUnderlinedInMuted() {
        val pixels = compose.onNodeWithTag(NAME_FIELD_TAG, useUnmergedTree = true).captureToImage().toPixelMap()
        var darkest: Color? = null
        for (y in pixels.height - 2 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (darkest == null || lum(c) < lum(darkest)) darkest = c
            }
        }
        val line = checkNotNull(darkest)
        assertTrue(
            "The NAME field's underline is $line, not the boards' Muted $Muted (Faint is $Faint)",
            distance(line, Muted) < 0.03f,
        )
    }

    private companion object {
        /** 960ms of frames: [READING_DELAY_MILLIS] and the sheet's entrance, with room. */
        const val SETTLE_FRAMES = 60
    }

    private fun lum(c: Color) = c.red + c.green + c.blue

    private fun distance(a: Color, b: Color) = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))
}
