package my.pinged.parse

import java.math.BigDecimal
import java.util.Locale

object Amount {
    private const val CEILING_SEN: Long = 100_000_000L // RM1,000,000.00

    /**
     * `[0-9]`, never `\d`, and the difference is money.
     *
     * This class is compiled by `java.util.regex` on the JVM, where `\d` is ASCII,
     * and by ICU on Android, where it is `\p{Nd}` -- and `BigDecimal` parses those
     * too, through `Character.digit`. Measured on emulator-5554: the full-width
     * pair U+FF11 U+FF12 matches `^\d+$` and `BigDecimal` reads it as 12, so this
     * returned 1200 sen for it, while every JVM test in this module said null.
     *
     * The cost is not a rejected notification: full-width text pairs full-width
     * digits with U+FF0E, which `\.` does not match, so a rule captures the ringgit
     * and drops the sen. `PackRegex` refuses `\d` in a pattern for the same reason;
     * this is that rule at the last gate before money.
     */
    private val SHAPE = Regex("^[0-9]{1,3}(,[0-9]{3})*(\\.[0-9]{1,2})?$|^[0-9]+(\\.[0-9]{1,2})?$")

    fun toSen(raw: String): Long? {
        val cleaned = TextNormalizer.forMatch(raw)
            .uppercase(Locale.ROOT)
            .removePrefix("MYR")
            .removePrefix("RM")
            .replace(" ", "")
            .trim()

        if (cleaned.length > 15) return null // ceiling is RM1,000,000.00
        if (!SHAPE.matches(cleaned)) return null

        val sen = BigDecimal(cleaned.replace(",", ""))
            .movePointRight(2)
            .stripTrailingZeros()

        if (sen.scale() > 0) return null // finer than one sen

        val value = try {
            sen.longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return if (value <= 0L || value > CEILING_SEN) null else value
    }
}
