package my.pinged.ui

import java.util.Locale
import kotlin.math.abs

/** The currency the screens draw as `RM`, or bare under a ringgit heading. */
const val RINGGIT = "MYR"

/**
 * The true minus sign (U+2212) every negative figure is drawn with, as the
 * artboard and the prototype print it, never an ASCII hyphen.
 */
const val MINUS = "−"

/** Sen as `RM2,847.30`, or `−RM120.00` below zero. */
fun ringgit(sen: Long): String = money(sen, RINGGIT, symbol = true)

/** Sen as `2,847.30` or `−120.00`: a figure in a column or block that already says ringgit. */
fun ringgitDigits(sen: Long): String = money(sen, RINGGIT, symbol = false)

/**
 * Sen in [currency]: ringgit as [ringgit] or [ringgitDigits] print it, by
 * [symbol]; any other currency always names its code, `USD −12.00`, since a
 * bare figure means ringgit and `txn` carries a currency column for the day
 * that stops being the only one (spec 4.5).
 *
 * `Long` arithmetic throughout, since money is `Long` sen. `Locale.ROOT` for
 * the grouping, so the drawn string does not depend on a device setting: a
 * device that groups with a full stop would print `RM1.234,56` and fail every
 * test matching the text exactly.
 */
fun money(sen: Long, currency: String, symbol: Boolean): String {
    val sign = if (sen < 0) MINUS else ""
    val magnitude = abs(sen)
    val digits = String.format(Locale.ROOT, "%,d", magnitude / 100) +
        "." + String.format(Locale.ROOT, "%02d", magnitude % 100)
    return when {
        currency != RINGGIT -> "$currency $sign$digits"
        symbol -> "${sign}RM$digits"
        else -> sign + digits
    }
}
