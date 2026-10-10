package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.CaptureDays
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #72: the foreground check records today's `capture_day` row when the
 * listener is bound, and only then.
 *
 * Each test empties the table under a listener that stays bound. That is
 * what a process sees after midnight: it is still bound, today has no row,
 * and no `onListenerConnected` is coming.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundCaptureDayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = Databases.captureDayDao(context)

    @After
    fun cancelAnythingScheduled() = CaptureFixtures.cancelStageTwo(context)

    /**
     * Bound, with the connect's own `capture_day` write already landed.
     *
     * Waiting for [ListenerBinding.connected] alone is not enough. The flag is
     * set as soon as `onListenerConnected` runs, but its write follows later
     * on the capture thread. If that write lands after the caller's delete,
     * it sets the memo again and the foreground write is skipped, so the test
     * would be checking the memo and not the gate. That write happens once
     * per connect, and a later test's delete clears the memo it set, so this
     * waits for it only once per process.
     */
    private fun boundAndSettled(): LocalDate {
        val component = PingedComponents.listener(context).flattenToString()
        CaptureFixtures.shell("cmd notification allow_listener $component")
        val today = LocalDates.of(System.currentTimeMillis())
        assertTrue(
            "The listener never bound, so this test cannot set up its premise",
            CaptureFixtures.waitFor {
                ListenerBinding.connected && (connectWriteLanded || CaptureDays.dayLastBound() == today)
            },
        )
        connectWriteLanded = true
        return today
    }

    @Test
    fun aForegroundWithTheListenerBoundRecordsToday() {
        val today = boundAndSettled()
        dao.deleteAllForImport()

        ListenerStatus.onAppForeground(context)

        val row = CaptureFixtures.waitForValue(5_000L) { dao.byDate(today) }
        assertTrue(
            "The foreground check found the listener bound and left today with " +
                "no capture_day row: $row",
            row?.listenerBound == true,
        )
    }

    @Test
    fun aForegroundWithoutTheBindingWritesNothing() {
        val today = boundAndSettled()
        // Today's row is seeded at `listener_bound = 0`. Any app's notification
        // can arrive during the wait and create a row that says bound, but on
        // an existing row only the binding writer touches that column. The
        // delete clears `CaptureDays`' memo, so if no write happens, the gate
        // stopped it. The DAO call below does not go through that memo.
        dao.deleteAllForImport()
        dao.recordListenerBound(today, false)

        // Granted, and no connect: the state after an unbind the grant
        // survived.
        ListenerBinding.connected = false
        try {
            ListenerStatus.onAppForeground(context)
            Thread.sleep(3_000)
            val row = dao.byDate(today)
            assertFalse("A foreground with no binding recorded today as bound: $row", row!!.listenerBound)
        } finally {
            ListenerBinding.connected = true
        }
    }

    private companion object {
        /** Set once [boundAndSettled] has seen the connect's own write land. */
        var connectWriteLanded = false
    }
}
