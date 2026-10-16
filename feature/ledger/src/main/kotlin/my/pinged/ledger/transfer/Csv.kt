package my.pinged.ledger.transfer

import my.pinged.data.LocalDate

/**
 * Spec 12's CSV, one line at a time: RFC 4180 quoting, formula
 * neutralisation, and numbers and dates that no locale can reach.
 *
 * Nothing here calls `String.format` or a `DateTimeFormatter`: an amount and
 * a date are integer arithmetic on sen and `yyyymmdd`, so a comma-decimal
 * default locale (spec 12, 15.7) has nothing to act on.
 */
object Csv {
    /**
     * Written once, before the header. Excel is an intended destination and
     * reads a UTF-8 file without one as the system code page (spec 12).
     */
    const val BOM = "\uFEFF"

    /** RFC 4180's record separator. */
    private const val CRLF = "\r\n"

    /**
     * What a spreadsheet reads as the start of a formula: spec 12's four, and
     * OWASP's tab and carriage return. A field starting with one is prefixed
     * with `'`, which every spreadsheet reads as "this is text".
     */
    private const val FORMULA_STARTS = "=+-@\t\r"

    /** One record: each field neutralised, then quoted where it needs to be. */
    fun line(fields: List<String?>): String =
        fields.joinToString(",", postfix = CRLF) { field(it.orEmpty()) }

    /**
     * Neutralised before it is quoted, so the `'` is inside the quotes and a
     * spreadsheet still sees the field's first character as text.
     */
    private fun field(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in FORMULA_STARTS) "'$value" else value
        val quoted = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (quoted) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /**
     * [sen] as ringgit and sen, `12.34`. Always positive here: spec 4 stores
     * `amount_sen` positive and the direction column says which way the money
     * went, which is also why no amount starts with a neutralised `-`.
     */
    fun amount(sen: Long): String {
        require(sen >= 0) { "amount_sen is stored positive (spec 4), was $sen" }
        val cents = sen % 100
        return "${sen / 100}." + (if (cents < 10) "0" else "") + cents
    }

    /** ISO 8601's `2026-09-07`, which every spreadsheet reads as a date. */
    fun date(date: LocalDate): String {
        val d = date.yyyymmdd
        return "${d / 10_000}-" + two(d / 100 % 100) + "-" + two(d % 100)
    }

    private fun two(n: Int) = if (n < 10) "0$n" else "$n"
}
