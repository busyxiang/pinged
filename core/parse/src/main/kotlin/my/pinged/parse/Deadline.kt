package my.pinged.parse

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RegexTimeoutException : RuntimeException("Regex exceeded its time budget")

/**
 * Runs one match under a wall-clock budget, on a thread the caller can walk
 * away from.
 *
 * **The obvious implementation does not work on Android.** A `CharSequence`
 * that throws from `charAt` once its deadline passes interrupts a match on the
 * desktop JVM, where the engine reads the input through that interface.
 * Android's `Matcher` calls `input.toString()` before handing the text to ICU,
 * so the wrapper's `get` is never called. Measured on an emulator: a 50 ms
 * budget against `(a+)+b` over 28 characters ran for 11,590 ms and threw
 * nothing.
 *
 * No test caught it because `DeadlineTest` runs on the JVM, and JDK 25 no
 * longer backtracks catastrophically on `(a+)+b` -- so that test had already
 * been rewritten around a backreference to make the guard fire at all. The
 * guard was certified on the platform where it is not needed and inert on the
 * one where it is.
 *
 * There is no way to interrupt `java.util.regex` in place, so the match moves
 * to a pooled daemon thread and the caller stops waiting when the budget runs
 * out. Two honest costs: the handoff measured 8.65 us per match on a Pixel 10a
 * emulator (3.04 us inline against 11.69 us pooled), which is ~1.7 s across
 * spec 15.8's 50,000-row dry run; and an abandoned match keeps burning a core
 * until it finishes on its own.
 */
object BoundedMatch {

    /**
     * A cached pool with a **ceiling**, which an unbounded one does not have.
     *
     * `cancel(true)` interrupts nothing here -- `java.util.regex` never checks the
     * flag -- so every abandoned match leaves a thread burning a core.
     * `newCachedThreadPool` will make as many as it is asked for, and
     * [RuleMatcherLimits.CAPTURE_BUDGET_MILLIS] bounds abandonment within *one*
     * capture at about three while saying nothing across captures: a 50,000-row dry
     * run against a pack carrying one catastrophic pattern leaked threads until
     * `pthread_create` failed, killing the process instead of producing `GAVE_UP`.
     *
     * **Sixteen, and a small pool is worse than a large one.** `newFixedThreadPool(2)`
     * looks orderly -- two stuck threads queue everything behind them and every
     * caller times out into `GAVE_UP` -- but the pool is process-wide, so two
     * pathological matches permanently stop parsing for *every* package, including
     * the ones whose rules are fine. It failed 26 tests on the spot.
     *
     * So: `newCachedThreadPool`'s shape with a hard maximum. Normal load uses one
     * thread; sixteen simultaneously stuck means the pack is broken, and [find]
     * then refuses rather than allocating.
     */
    private const val MAX_THREADS = 16

    private val threads = ThreadPoolExecutor(
        0,
        MAX_THREADS,
        60L,
        TimeUnit.SECONDS,
        // Synchronous, not a queue: a task must find a free thread or be
        // refused. A queue would hide saturation by making callers wait out
        // their whole budget behind work that has not started.
        SynchronousQueue(),
    ) { runnable -> Thread(runnable, "pinged-regex").apply { isDaemon = true } }

    /**
     * Blocks until no abandoned match is still running, or [timeoutMillis]
     * passes. Returns how many were still going.
     *
     * Tests only, and it exists because an abandoned match is not free to its
     * neighbours. `cancel(true)` interrupts nothing, so a test that deliberately
     * abandons one leaves a thread burning a core in a *process shared with
     * every other test in the APK* -- and [RuleMatcherLimits.TIME_BUDGET_MILLIS]
     * is wall clock, so a starved CPU turns a legitimate match in some later
     * class into `GAVE_UP`, which reads as "no rule matched" and produces no
     * transaction at all.
     *
     * That is not hypothetical: it took three `DedupTest` cases down on CI,
     * always the first three to run after `BoundedMatchTest`, one of them
     * reporting `expected:<MATCHED> but was:<GAVE_UP>` outright.
     *
     * A test that chooses to abandon a match pays for it in its own `@AfterClass`
     * rather than leaving the bill for whatever runs next.
     */
    fun awaitIdle(timeoutMillis: Long): Int {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (threads.activeCount > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        return threads.activeCount
    }

    /**
     * [regex] against [input], or [RegexTimeoutException] if it takes longer
     * than [budgetMillis].
     *
     * `cancel(true)` interrupts nothing here -- `java.util.regex` does not
     * check the interrupt flag -- and is called anyway so that a task still
     * queued rather than running is dropped rather than run later.
     */
    fun find(regex: Regex, input: CharSequence, budgetMillis: Long): MatchResult? {
        val text = input.toString()
        val task = try {
            threads.submit(Callable { regex.find(text) })
        } catch (_: RejectedExecutionException) {
            // Every thread is stuck on an abandoned match, so this one would
            // be too. Reported as a timeout because that is what it is: the
            // budget cannot be met, and the caller's answer is the same.
            throw RegexTimeoutException()
        }
        return try {
            task.get(budgetMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            throw RegexTimeoutException()
        } catch (interrupted: InterruptedException) {
            // Not a timeout, and deliberately not turned into one.
            //
            // `Future.get` throws this when *the caller's* thread is interrupted, meaning
            // the work this parse belongs to is being stopped. `RuleMatcherLimits` would
            // read a timeout as "this rule gave up, try the next one", so the loop would
            // carry on matching on a thread told to stop. Kotlin does not force this catch,
            // so it escaped both of `RuleMatcher`'s handlers untyped, with the interrupt
            // flag silently cleared by `get`. Restored and rethrown: the flag is how
            // everything above finds out.
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failed: ExecutionException) {
            // A StackOverflowError from the engine's recursion arrives here
            // wrapped; the caller treats it the same as a timeout.
            throw failed.cause ?: failed
        }
    }
}
