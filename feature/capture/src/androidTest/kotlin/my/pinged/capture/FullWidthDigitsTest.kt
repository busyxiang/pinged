package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.Amount
import my.pinged.parse.PackRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-width digits, on the engine that actually ships.
 *
 * This module's parsing is compiled twice: by `java.util.regex` in
 * `:core:parse`'s JVM tests, where `\d` means ASCII, and by ICU here, where it
 * means `\p{Nd}`. No JVM test in this repository can see the difference.
 *
 * The cost is not a missed notification: full-width text pairs full-width
 * digits with the full-width stop U+FF0E, which `\.` does not match, so a rule
 * captures the ringgit and drops the sen. Measured before the fix,
 * `Amount.toSen` returned 1200 for the full-width "12" and the bundled fragment
 * captured "12" out of a full-width "RM12.50".
 *
 * These live in `:feature:capture` because `:core:parse` has no instrumented
 * source set -- the same asymmetry the bug came from.
 */
@RunWith(AndroidJUnit4::class)
class FullWidthDigitsTest {

    /** U+FF11 U+FF12: "12" in full-width form. */
    private val fullWidthTwelve = "１２"

    /** U+FF11 U+FF12 U+FF0E U+FF15 U+FF10: "12.50" in full-width form. */
    private val fullWidthTwelveFifty = "１２．５０"

    /**
     * The premise, asserted rather than assumed. If this ever stops holding,
     * everything below is testing nothing and should be deleted.
     */
    @Test fun androidReadsBackslashDAsEveryUnicodeDigit() {
        assertEquals(
            "ICU no longer treats \\d as \\p{Nd}, so this file's reason to exist is gone",
            true,
            Regex("^\\d+$").matches(fullWidthTwelve),
        )
        assertEquals(
            "BigDecimal no longer reads Unicode digits, so a full-width amount " +
                "can no longer become a number",
            "12",
            java.math.BigDecimal(fullWidthTwelve).toString(),
        )
    }

    /** The last gate before money refuses what it cannot read as ASCII. */
    @Test fun aFullWidthAmountIsNotMoney() {
        assertNull(
            "A full-width amount was converted to sen, and the sen are whatever " +
                "survived the stop the pattern could not match",
            Amount.toSen(fullWidthTwelve),
        )
        assertNull(Amount.toSen(fullWidthTwelveFifty))
        assertNull(Amount.toSen("RM$fullWidthTwelve"))
    }

    /** And ASCII still is, which is what stops the fix being a rejection of everything. */
    @Test fun anAsciiAmountIsStillMoney() {
        assertEquals(1250L, Amount.toSen("12.50"))
        assertEquals(1250L, Amount.toSen("RM12.50"))
    }

    /**
     * The bundled fragment captures nothing from a full-width amount, so the
     * rule does not match and the capture is recorded UNMATCHED.
     *
     * A partial capture is the dangerous outcome: it produces a transaction,
     * for the wrong amount, indistinguishable from a correct one.
     */
    @Test fun theBundledAmountFragmentDoesNotCaptureAFullWidthAmount() {
        val fragment = Graph.parsePack().fragments.getValue("amount")
        val regex = PackRegex.compile(fragment)

        assertNull(
            "The fragment captured part of a full-width amount, which is a " +
                "transaction for the wrong money rather than no transaction",
            regex.find("Payment of RM$fullWidthTwelveFifty to KOPITIAM")
                ?.groups?.get(PackRegex.AMOUNT_GROUP)?.value,
        )
        assertNotNull(
            "and it still has to read an ordinary amount",
            regex.find("Payment of RM12.50 to KOPITIAM"),
        )
    }
}
