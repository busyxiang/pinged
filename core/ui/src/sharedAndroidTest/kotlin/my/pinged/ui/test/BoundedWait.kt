package my.pinged.ui.test

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [ComposeTestRule.waitUntil], failing at [timeoutMillis] even while
 * [condition] is still running.
 *
 * `waitUntil` reads the clock only between calls to its condition, and a
 * condition that reads semantics (`fetchSemanticsNodes`,
 * `performScrollToNode`) first waits for idle through `Espresso.onIdle`.
 * Espresso times out a main thread that stays busy, but not one that is idle
 * at one check and busy at the next, and that holds the call -- and the
 * emulator's lease every worktree shares -- forever: on 2026-10-19
 * `EditRouteTest` held it 7 minutes until another session stopped it. Here a watchdog interrupts the waiting thread at the timeout;
 * `Espresso.onIdle` parks in `FutureTask.get`, which an interrupt ends.
 * `BoundedWaitTest` is the falsifying test.
 *
 * Every `waitUntil` in an androidTest goes through this;
 * `BoundedWaitSourceTest` refuses a bare one.
 */
fun ComposeTestRule.boundedWaitUntil(
    timeoutMillis: Long,
    conditionDescription: String? = null,
    condition: () -> Boolean,
) {
    val waiter = Thread.currentThread()
    val lock = Any()
    // Guarded by lock: the watchdog interrupts only while the wait is live,
    // so no interrupt can land on whatever the test thread does next.
    var live = true
    var fired = false
    val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "boundedWaitUntil").apply { isDaemon = true } }
    watchdog.schedule(
        {
            synchronized(lock) {
                if (live) {
                    fired = true
                    waiter.interrupt()
                }
            }
        },
        timeoutMillis,
        TimeUnit.MILLISECONDS,
    )
    try {
        if (conditionDescription == null) waitUntil(timeoutMillis, condition)
        else waitUntil(conditionDescription, timeoutMillis, condition)
    } catch (e: Throwable) {
        if (e !is ComposeTimeoutException && synchronized(lock) { fired }) {
            throw AssertionError(
                "Waited ${timeoutMillis}ms${conditionDescription?.let { " for $it" }.orEmpty()} and the condition " +
                    "was still running: it waits for idle, and the main thread never went idle",
                e,
            )
        }
        throw e
    } finally {
        synchronized(lock) {
            live = false
            // An interrupt that arrived after the wait's last blocking call is
            // still pending; clear it before it fails an unrelated call.
            if (fired) Thread.interrupted()
        }
        watchdog.shutdownNow()
    }
}
