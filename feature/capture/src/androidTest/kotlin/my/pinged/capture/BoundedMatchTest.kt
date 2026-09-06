package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.BoundedMatch
import my.pinged.parse.PackRegex
import my.pinged.parse.RegexTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The regex budget, on the engine that actually ships.
 *
 * This exists because its subject shipped broken and no test could see it. The
 * previous guard was a `CharSequence` throwing from `charAt` past its deadline,
 * which works on the desktop JVM because the engine reads input through that
 * interface -- Android's `Matcher` calls `input.toString()` first and hands ICU
 * a plain string, so a 50 ms budget against this pattern ran 11,590 ms and threw
 * nothing. `:core:parse` is a JVM module, so its own tests could never have
 * caught it.
 *
 * `(a+)+b` is the plain nested quantifier. JDK 25 optimizes it away, which is
 * why the JVM test uses a backreference; ICU does not.
 */
@RunWith(AndroidJUnit4::class)
class BoundedMatchTest {

    companion object {
        /** Every caller returns on its 50ms budget; this is only a safety net. */
        const val TIMEOUT = 10_000L

        /**
         * **Every abandoned match this class started is waited out here, and that
         * is not tidiness.**
         *
         * `cancel(true)` interrupts nothing -- `java.util.regex` never checks the
         * flag -- so an abandoned match keeps a core busy until it finishes on its
         * own, in a process shared with every other test in this APK. The regex
         * budget is wall clock, so that starvation turns a legitimate match in a
         * later class into `GAVE_UP`: no rule matched, no transaction written,
         * which surfaces as "the payment vanished".
         *
         * It did. Three `DedupTest` cases failed on CI, always the first three to
         * run after this class, one reporting `expected:<MATCHED> but was:<GAVE_UP>`
         * outright. Twelve clean local full-suite runs said nothing, because a
         * machine with spare cores absorbs it.
         *
         * The inputs above are sized so this returns quickly. The wait is what
         * makes that a guarantee rather than an expectation, and the assertion is
         * what stops a future input size quietly reintroducing the leak.
         */
        @AfterClass
        @JvmStatic
        fun doNotLeaveAnyCoresBurning() {
            val stillRunning = BoundedMatch.awaitIdle(timeoutMillis = 30_000)
            assertEquals(
                "This class left $stillRunning abandoned regex matches running. " +
                    "Each burns a core, the regex budget is wall clock, and the " +
                    "next test class pays for it as spurious GAVE_UP outcomes.",
                0,
                stillRunning,
            )
        }
    }
    @Test fun aCatastrophicPatternGivesUpOnDevice() {
        val evil = PackRegex.compile("(a+)+b")
        // Twenty-three, and the number is measured rather than chosen. `(a+)+b`
        // doubles per character; on this emulator, completion time by input length:
        //
        //   20 -> 47ms    23 -> 392ms    26 -> 3.0s
        //   21 -> 98ms    24 -> 761ms    28 -> 11.9s
        //   22 -> 211ms   25 -> 1.5s     30 -> ~48s
        //
        // So twenty does NOT blow a 50ms budget -- it completes just inside it,
        // which is why this test fails at twenty with "expected a timeout, got
        // null". Twenty-three is eight times the budget, so the guard fires with
        // margin on a faster machine, and finishes in under half a second, so the
        // abandoned match does not outlive the test that started it.
        //
        // It was thirty, which is ~48 seconds of a burning core in a process
        // shared with every other test in this APK. `cancel(true)` interrupts
        // nothing, and the regex budget is wall clock, so that starvation turned
        // legitimate matches in `DedupTest` into `GAVE_UP` -- no rule matched, no
        // transaction, "the payment vanished". Three cases, always the first three
        // to run after this class. `@AfterClass` below is the other half.
        val input = "a".repeat(23)

        val started = System.nanoTime()
        val thrown = runCatching { BoundedMatch.find(evil, input, 50L) }.exceptionOrNull()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue("expected a timeout, got $thrown", thrown is RegexTimeoutException)
        assertTrue(
            "the caller waited ${elapsedMillis}ms on a 50ms budget; the guard did not fire",
            elapsedMillis < 2_000,
        )
    }

    /**
     * Abandoned matches cannot accumulate threads.
     *
     * `cancel(true)` interrupts nothing -- `java.util.regex` never checks the flag
     * -- so every abandoned match leaves a thread burning a core. The pool was
     * `newCachedThreadPool`, unbounded, and `CAPTURE_BUDGET_MILLIS` bounds
     * abandonment only *within* one capture: across a 50,000-row dry run a pack
     * with one catastrophic pattern leaked threads until `pthread_create` failed.
     *
     * **The input has to actually be abandoned, and twenty was not.** Measured on
     * this emulator, `(a+)+b` over twenty characters completes in 47ms against a
     * 50ms budget -- so every caller here got its answer, no thread was left
     * running, and the assertion below passed over a pool that was never under
     * any pressure. Twenty-three takes 392ms, which is eight times the budget.
     *
     * Twenty-four callers against a ceiling of sixteen, so the ceiling is what is
     * measured rather than the load.
     */
    @Test fun abandonedMatchesDoNotAccumulateThreads() {
        val evil = PackRegex.compile("(a+)+b")
        val input = "a".repeat(23)

        // Concurrent callers, not a loop. Called one after another the abandoned
        // matches finish fast enough to be reused, and an unbounded pool can
        // stay small by luck. Concurrent callers force it to make one each.
        val callers = (1..24).map {
            Thread { runCatching { BoundedMatch.find(evil, input, 50L) } }
                .apply { isDaemon = true; start() }
        }
        callers.forEach { it.join(TIMEOUT) }

        val running = Thread.getAllStackTraces().keys.count { it.name == "pinged-regex" }
        assertTrue(
            "Twenty-four concurrent abandoned matches left $running regex " +
                "threads alive. Each one " +
                "burns a core until its match finishes, and nothing bounded how " +
                "many there could be.",
            running <= 16,
        )
    }

    @Test fun anOrdinaryPatternStillReturnsItsMatch() {
        val amount = PackRegex.compile("(?:RM|MYR)\\s?(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)")
        val found = BoundedMatch.find(amount, "Payment of RM12.30 to Kedai Ali", 50L)
        assertEquals("12.30", found?.groups?.get("amount")?.value)
    }
}
