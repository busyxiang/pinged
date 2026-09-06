package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM half of the budget. `BoundedMatchTest` in `:feature:capture` is the
 * half that matters, and the reason both exist is that the previous guard
 * passed here while doing nothing on a phone.
 *
 * `(a+)+b` is not usable as the evil pattern on this JDK (25.0.4.1): the
 * engine resolves it in under a millisecond, so the test would pass without
 * the guard. A backreference still defeats its optimizations and runs for
 * about twelve seconds unguarded, so it stands in here. On Android's
 * ICU-backed engine the plain nested quantifier is still catastrophic, which
 * is exactly why the device test uses that one.
 */
class DeadlineTest {
    @Test fun `a catastrophic pattern gives up instead of hanging`() {
        val evil = PackRegex.compile("^(a+)+\\1$")
        val input = "a".repeat(30) + "b"

        val started = System.nanoTime()
        val thrown = runCatching { BoundedMatch.find(evil, input, 50L) }.exceptionOrNull()

        assertTrue("expected a timeout, got $thrown", thrown is RegexTimeoutException)
        assertTrue(
            "the caller waited ${(System.nanoTime() - started) / 1_000_000}ms on a 50ms budget",
            System.nanoTime() - started < 2_000_000_000L,
        )
    }

    @Test fun `an ordinary pattern returns its match`() {
        val amount = PackRegex.compile("(?:RM|MYR)\\s?(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)")
        val found = BoundedMatch.find(amount, "Payment of RM12.30 to Kedai Ali", 50L)
        assertEquals("12.30", found?.groups?.get("amount")?.value)
    }
}
