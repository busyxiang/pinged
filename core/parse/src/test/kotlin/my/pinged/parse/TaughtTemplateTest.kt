package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 5.8: a rule derived from two taps, and what it reads afterwards.
 * Every expected value is the artboard's (`design/Teach.dc.html`) or a
 * literal worked by hand, never one recomputed the way the code does.
 */
class TaughtTemplateTest {
    private val pkg = "com.hongleong.connect"

    /** The artboard's message, as stage one stores it. */
    private val hongLeong = TaughtTemplate.words(
        title = null,
        text = "Transaksi berjaya. RM88.00 telah ditolak dari akaun anda 1234.",
        bigText = null,
    )

    private fun matcherOf(vararg rules: Pair<TaughtTemplate.Derived, Int>): RuleMatcher =
        TaughtTemplate.matcher(
            rules.mapIndexed { i, (derived, matches) ->
                TaughtTemplate.Stored(
                    id = i + 1L,
                    sourcePackage = pkg,
                    pattern = derived.pattern,
                    direction = Direction.EXPENSE,
                    matchCount = matches,
                )
            },
        )

    private fun derived(d: TaughtTemplate.Derived?): TaughtTemplate.Derived = requireNotNull(d) { "derived nothing" }

    private fun read(matcher: RuleMatcher, text: String, title: String? = null) =
        matcher.match(pkg, title, text, null)

    @Test fun `the words are the message split where it has spaces`() {
        assertEquals(
            listOf("Transaksi", "berjaya.", "RM88.00", "telah", "ditolak", "dari", "akaun", "anda", "1234."),
            hongLeong.words,
        )
        assertEquals(0, hongLeong.titleWords)
    }

    @Test fun `the preview is the artboard's, untapped words literal and other numbers any number`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))

        assertEquals(
            listOf(
                TaughtTemplate.Part.Literal("Transaksi berjaya. RM"),
                TaughtTemplate.Part.Amount,
                TaughtTemplate.Part.Literal(" telah ditolak dari akaun anda "),
                TaughtTemplate.Part.AnyNumber,
                TaughtTemplate.Part.Literal("."),
            ),
            derived.preview,
        )
        assertFalse(derived.hasMerchant)
    }

    @Test fun `a later message of the same shape reads at its own amount, whatever its other numbers`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))
        val outcome = read(matcherOf(derived to 5), "Transaksi berjaya. RM1,250.40 telah ditolak dari akaun anda 9876.")

        outcome as MatchOutcome.Matched
        assertEquals(125_040L, outcome.amountSen)
        assertNull(outcome.merchantRaw)
        assertEquals(Direction.EXPENSE, outcome.direction)
    }

    @Test fun `a message with one word different is not read`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))
        val matcher = matcherOf(derived to 5)

        assertEquals(MatchOutcome.Unmatched, read(matcher, "Transaksi gagal. RM88.00 telah ditolak dari akaun anda 1234."))
        // Nor one with more after it: the rule is the whole message.
        assertEquals(
            MatchOutcome.Unmatched,
            read(matcher, "Transaksi berjaya. RM88.00 telah ditolak dari akaun anda 1234. Baki RM10.00"),
        )
    }

    @Test fun `the merchant is the words tapped, and its trailing stop stays literal`() {
        val words = TaughtTemplate.words(null, "Paid RM12.00 to KEDAI RUNCIT AH SENG. Ref 00000000", null)
        val derived = derived(TaughtTemplate.derive(words, amount = 1, merchant = 3..6))

        assertTrue(derived.hasMerchant)
        val outcome = read(matcherOf(derived to 5), "Paid RM7.50 to 99 SPEEDMART. Ref 12345678")
        outcome as MatchOutcome.Matched
        assertEquals(750L, outcome.amountSen)
        assertEquals("99 SPEEDMART", outcome.merchantRaw)
    }

    @Test fun `the first three matches are REVIEW, the fourth is HIGH`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))
        val text = "Transaksi berjaya. RM5.00 telah ditolak dari akaun anda 1234."

        assertEquals(Confidence.REVIEW, (read(matcherOf(derived to 0), text) as MatchOutcome.Matched).confidence)
        assertEquals(Confidence.REVIEW, (read(matcherOf(derived to 2), text) as MatchOutcome.Matched).confidence)
        assertEquals(Confidence.HIGH, (read(matcherOf(derived to 3), text) as MatchOutcome.Matched).confidence)
    }

    @Test fun `the rule id names the stored row, and reads back to it`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))
        val outcome = read(matcherOf(derived to 5), "Transaksi berjaya. RM5.00 telah ditolak dari akaun anda 1234.")

        outcome as MatchOutcome.Matched
        assertEquals(1L, TaughtTemplate.storedIdOf(outcome.ruleId))
        assertNull(TaughtTemplate.storedIdOf("tng-payment-v1"))
    }

    @Test fun `a tapped word with no amount in it derives nothing`() {
        assertNull(TaughtTemplate.derive(hongLeong, amount = 0, merchant = null))
        // Nor one whose only number is not money Pinged records.
        assertNull(TaughtTemplate.derive(TaughtTemplate.words(null, "Paid RM0.00 today", null), amount = 1, merchant = null))
    }

    @Test fun `a merchant over the amount derives nothing`() {
        assertNull(TaughtTemplate.derive(hongLeong, amount = 2, merchant = 1..3))
    }

    /** CLAUDE.md: `\d` is ASCII on the JVM and Unicode on Android. */
    @Test fun `a derived pattern never says backslash d, w or s`() {
        val words = TaughtTemplate.words("Maybank 2u", "Paid RM12.00 to 7-ELEVEN #1234 (KL) at 10:45 [ok] \\d", null)
        val derived = derived(TaughtTemplate.derive(words, amount = 3, merchant = 5..6))

        assertFalse(derived.pattern, Regex("(?<!\\\\)\\\\[dDwWsS]").containsMatchIn(derived.pattern))
        // And its punctuation is literal: the sample reads itself back.
        val outcome = read(matcherOf(derived to 5), "Paid RM12.00 to 7-ELEVEN #1234 (KL) at 10:45 [ok] \\d", title = "Maybank 2u")
        outcome as MatchOutcome.Matched
        assertEquals(1_200L, outcome.amountSen)
        assertEquals("7-ELEVEN #1234", outcome.merchantRaw)
    }

    @Test fun `the title is read too, its words before the body's`() {
        val words = TaughtTemplate.words("Boost", "You paid RM3.00 at Tealive.", null)
        assertEquals(1, words.titleWords)
        val derived = derived(TaughtTemplate.derive(words, amount = 3, merchant = 5..5))
        val matcher = matcherOf(derived to 5)

        assertTrue(read(matcher, "You paid RM4.00 at Zus.", title = "Boost") is MatchOutcome.Matched)
        assertEquals(MatchOutcome.Unmatched, read(matcher, "You paid RM4.00 at Zus.", title = "Boost Promo"))
    }

    @Test fun `a full-width amount is not read as money`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))
        assertEquals(
            MatchOutcome.Unmatched,
            read(matcherOf(derived to 5), "Transaksi berjaya. RM１２.00 telah ditolak dari akaun anda 1234."),
        )
    }

    /** A backup is a file anyone can edit, and a restored pattern is never derived again. */
    @Test fun `a stored pattern that says backslash d is not used`() {
        val matcher = TaughtTemplate.matcher(
            listOf(TaughtTemplate.Stored(1, pkg, "^Paid RM(?<amount>\\d+)$", Direction.EXPENSE, 5)),
        )
        assertEquals(MatchOutcome.Unmatched, read(matcher, "Paid RM5"))
    }

    @Test fun `a stored pattern that does not compile is skipped, not thrown`() {
        val derived = derived(TaughtTemplate.derive(hongLeong, amount = 2, merchant = null))
        val matcher = TaughtTemplate.matcher(
            listOf(
                TaughtTemplate.Stored(1, pkg, "(?<amount>[0-9]+", Direction.EXPENSE, 5),
                TaughtTemplate.Stored(2, pkg, derived.pattern, Direction.REFUND, 5),
            ),
        )
        val outcome = read(matcher, "Transaksi berjaya. RM5.00 telah ditolak dari akaun anda 1234.") as MatchOutcome.Matched
        assertEquals(Direction.REFUND, outcome.direction)
    }
}
