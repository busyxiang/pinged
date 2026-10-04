package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What [ListenerStatus.report] does when `capture_health` will not read.
 *
 * Its caller is `MainActivity.onResume`, on a `lifecycleScope.launch` with no
 * `CoroutineExceptionHandler`: a throw from here does not fail the banner, it
 * reaches the thread's default handler and kills the process -- on the home
 * screen, on every foreground, for as long as the file stays unreadable. So
 * one subject of every test below is that nothing escapes.
 *
 * The other subject is the answer, because silence is not available. A report
 * that could not be taken is not a report of health, and no banner at all is
 * exactly how a dead listener looks, which is the failure spec 10.2 exists for.
 *
 * **The failures are handed in rather than provoked on disk.** `capture_health`
 * is one DataStore for the process and caches its first successful read, so a
 * preferences file corrupted from here is never read again and `report` would
 * answer from the cache -- a test passing for a reason that has nothing to do
 * with the guard.
 */
@RunWith(AndroidJUnit4::class)
class ListenerReportGuardTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The heartbeat read, which is the first of the two.
     *
     * `CorruptionException` on a truncated preferences file and a plain
     * `IOException` on a disk that will not read are the two real shapes, and
     * both arrive as this.
     */
    @Test fun aHeartbeatThatWillNotReadIsReportedRatherThanThrown() = runBlocking {
        val outcome = runCatching {
            ListenerStatus.report(
                context,
                lastSeenAt = { throw IOException("simulated unreadable preferences file") },
                storageUnavailable = { false },
            )
        }

        assertTrue(
            "Reading the heartbeat threw out of `report`: " +
                "${outcome.exceptionOrNull()}. Nothing above it catches this, so " +
                "the home screen crashes on every resume until the file is " +
                "readable again",
            outcome.isSuccess,
        )
        assertTrue(
            "`capture_health` could not be read and the report did not say so. " +
                "Every other field then describes a file nobody opened, and the " +
                "banner draws one of them as fact",
            outcome.getOrThrow().healthUnreadable,
        )
    }

    /**
     * The storage-flag read, which is the second.
     *
     * Separate from the heartbeat because a guard around only the first read
     * passes that test and fails this one: the two are read one after the
     * other and either can be the one that throws.
     */
    @Test fun aStorageFlagThatWillNotReadIsReportedRatherThanThrown() = runBlocking {
        val outcome = runCatching {
            ListenerStatus.report(
                context,
                lastSeenAt = { 1_700_000_000_000L },
                storageUnavailable = { throw IOException("simulated unreadable preferences file") },
            )
        }

        assertTrue(
            "Reading the storage flag threw out of `report`: " +
                "${outcome.exceptionOrNull()}, so only the read before it is " +
                "inside the guard",
            outcome.isSuccess,
        )
        assertTrue(
            "The second read failed and the report claimed to be a reading",
            outcome.getOrThrow().healthUnreadable,
        )
    }

    /**
     * **The fabricated fields, which are what stop this reading as health.**
     *
     * `healthUnreadable` alone would not: `looksDead` and [CaptureReport
     * .needsUserAction] are computed from the three fields beside it, and
     * anything that reads those without checking the flag first -- today
     * `RebindReceiver`'s KDoc points at `needsUserAction` as the whole reason
     * the type exists -- would be handed a phone that last saw a notification
     * this instant. The never-seen encoding is the safe direction, and this is
     * what holds it there.
     */
    @Test fun aReportThatCouldNotBeTakenDoesNotReadAsAHealthyOne() = runBlocking {
        val report = ListenerStatus.report(
            context,
            lastSeenAt = { throw IOException("simulated unreadable preferences file") },
            storageUnavailable = { false },
        )

        assertEquals(
            "An unreadable store was reported as a notification seen at the " +
                "epoch, which is a time and not an absence",
            0L,
            report.lastNotificationAt,
        )
        assertEquals(
            "An unreadable store was reported as freshly fed, so every reader " +
                "of `looksDead` is told capture is alive by a file that was " +
                "never opened",
            Long.MAX_VALUE,
            report.staleForMillis,
        )
        assertTrue(
            "A report that could not be taken says nothing needs the user, " +
                "which is the one answer it cannot support",
            report.needsUserAction,
        )
    }
}
