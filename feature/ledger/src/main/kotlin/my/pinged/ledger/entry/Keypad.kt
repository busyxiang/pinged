package my.pinged.ledger.entry

import my.pinged.parse.Amount

/**
 * The entry screen's amount, as a cash register takes it (#97): each digit is
 * `sen * 10 + d`, backspace is `sen / 10`, so `1`,`2`,`5`,`0` is RM12.50 with
 * no decimal point to find.
 *
 * The amount is a `Long` of sen and nothing else -- no `BigDecimal`, no string
 * parse. The field's text is only ever the digits of that `Long` ([shown]),
 * and a change to it is read as keys pressed ([keyed]), not as a number, so a
 * comma-decimal keyboard's separator cannot reach the amount at all.
 */
internal object Keypad {

    /** The field's text for [sen]: its digits, and nothing at all for zero. */
    fun shown(sen: Long): String = if (sen == 0L) "" else sen.toString()

    /**
     * The amount after the field's text went from [before] to [after].
     *
     * Digits appended are typed one by one, digits taken off the end are
     * erased, and anything else that is not a digit is dropped first. A change
     * that is neither -- a paste over the text, a replaced selection -- is not
     * a key press and leaves [sen] as it was.
     */
    fun keyed(sen: Long, before: String, after: String): Long {
        val was = before.filter(::isDigit)
        val now = after.filter(::isDigit)
        return when {
            now.startsWith(was) -> now.drop(was.length).fold(sen) { amount, d -> typed(amount, d - '0') }
            was.startsWith(now) -> (now.length until was.length).fold(sen) { amount, _ -> amount / 10 }
            else -> sen
        }
    }

    /**
     * [digit] pressed: ignored when it would take the amount past
     * [Amount.CEILING_SEN], so a stuck key cannot record an impossible figure.
     */
    private fun typed(sen: Long, digit: Int): Long =
        (sen * 10 + digit).takeIf { it <= Amount.CEILING_SEN } ?: sen

    /** `0`-`9` only, never `Char.isDigit`: that is Unicode's `Nd`, full-width digits and all. */
    private fun isDigit(c: Char): Boolean = c in '0'..'9'
}
