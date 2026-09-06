package my.pinged.capture

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rebinding, as far as it can be tested from inside the process.
 *
 * **No test here reproduces the failure the task exists for.** Replacing the
 * APK kills this process, so nothing of ours is running to observe the unbind.
 * What is covered is everything around it: the grant is readable, the rebind
 * call survives being made, the receiver is genuinely declared and reachable,
 * and the report keeps the grant and liveness apart. The real check is the
 * manual reinstall.
 */
@RunWith(AndroidJUnit4::class)
class RebindTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun cancelAnythingScheduled() {
        // These tests really do schedule stage two, because the receiver really
        // does. Left running, a worker would wake later and write to the shared
        // database while another class is asserting against it.
        CaptureFixtures.cancelStageTwo(context)
    }

    @Test
    fun grantIsDetectedThroughTheFrameworkApi() {
        val component = PingedComponents.listener(context).flattenToString()
        CaptureFixtures.shell("cmd notification allow_listener $component")

        assertTrue(
            "The grant did not take. Shell said: " +
                CaptureFixtures.shell("cmd notification allowed_listeners"),
            CaptureFixtures.waitFor { ListenerStatus.isGranted(context) },
        )
    }

    @Test
    fun grantIsReadAgainstTheFrozenComponentAndNotThePackage() {
        // isNotificationListenerAccessGranted takes a ComponentName, and the
        // grant is stored against the flattened form of it. A rename or a move
        // of the listener class leaves the old grant pointing at a component
        // that no longer exists, with no callback and nothing the user can see
        // -- so the value this test pins is the string, not the boolean.
        val component = PingedComponents.listener(context)

        assertEquals(context.packageName, component.packageName)
        assertEquals("my.pinged.capture.PingedNotificationListener", component.className)
        assertEquals(
            "${context.packageName}/my.pinged.capture.PingedNotificationListener",
            component.flattenToString(),
        )
    }

    @Test
    fun requestRebindDoesNotThrowWhenGranted() {
        CaptureFixtures.shell("cmd notification allow_listener " + PingedComponents.listener(context).flattenToString())
        CaptureFixtures.waitFor { ListenerStatus.isGranted(context) }

        ListenerStatus.requestRebind(context)
    }

    @Test
    fun requestRebindDoesNotThrowWhenTheGrantIsAbsent() {
        // The state that matters more. requestRebind throws when the grant is
        // gone, and every caller is a broadcast receiver or an app-foreground
        // hook where an exception takes the process with it -- on the exact
        // devices where the grant is most likely to have been revoked.
        val component = PingedComponents.listener(context).flattenToString()
        CaptureFixtures.shell("cmd notification disallow_listener $component")
        CaptureFixtures.waitFor { !ListenerStatus.isGranted(context) }

        ListenerStatus.requestRebind(context)

        // Put it back for whatever runs next; test ordering is not guaranteed
        // and ListenerTest needs the grant.
        CaptureFixtures.shell("cmd notification allow_listener $component")
        CaptureFixtures.waitFor { ListenerStatus.isGranted(context) }
    }

    /**
     * The heartbeat this reads is process-wide, throttled to one write every
     * five minutes, and every other test class in this run feeds it -- some with
     * fabricated timestamps. So these two tests never assert that the stored
     * value is fresh. They read whatever it is and ask the report about a `now`
     * chosen relative to it, which is what actually needs proving: the same
     * heartbeat, read at two different moments, produces the healthy answer and
     * the OEM-kill answer.
     */
    private suspend fun lastSeen(): Long {
        val stored = CaptureHealth.lastSeenAt(context)
        if (stored != 0L) return stored
        CaptureHealth.recordSeen(context, System.currentTimeMillis())
        return CaptureHealth.lastSeenAt(context)
    }

    @Test
    fun theReportDistinguishesGrantFromLiveness() = runBlocking {
        val component = PingedComponents.listener(context).flattenToString()
        CaptureFixtures.shell("cmd notification allow_listener $component")
        CaptureFixtures.waitFor { ListenerStatus.isGranted(context) }
        val seen = lastSeen()

        val report = ListenerStatus.report(context, now = seen + 60_000L)

        assertTrue("The grant is present and the report should say so", report.granted)
        assertEquals(seen, report.lastNotificationAt)
        assertEquals(60_000L, report.staleForMillis)
        assertFalse("A minute is not a day; this is not a dead listener", report.looksDead)
        assertFalse(report.needsUserAction)
    }

    @Test
    fun aGrantWithNothingArrivingIsTheSignatureTheReportMustNotHide() = runBlocking {
        CaptureFixtures.shell("cmd notification allow_listener " + PingedComponents.listener(context).flattenToString())
        CaptureFixtures.waitFor { ListenerStatus.isGranted(context) }
        val seen = lastSeen()

        // Ask the question two days after the last heartbeat rather than
        // back-dating the heartbeat, because the heartbeat is throttled and a
        // back-dated write is exactly what the throttle refuses.
        val report = ListenerStatus.report(
            context,
            now = seen + 2 * ListenerStatus.STALE_AFTER_MILLIS,
        )

        assertTrue(report.granted)
        assertTrue("Granted but silent is the OEM-kill signature", report.looksDead)
        assertTrue(
            "All three rebind paths have already run by here; the only thing " +
                "left is to tell the user (spec 10.5)",
            report.needsUserAction,
        )
    }

    @Test
    fun theReceiverIsDeclaredForBothActionsAndIsNotExported() {
        val declared = context.packageManager.getReceiverInfo(
            ComponentName(context, RebindReceiver::class.java),
            0,
        )
        assertFalse(
            "The receiver takes protected system broadcasts only; exporting it " +
                "would let any app on the device drive it",
            declared.exported,
        )

        for (action in listOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) {
            val resolved = context.packageManager.queryBroadcastReceivers(
                Intent(action).setPackage(context.packageName),
                0,
            ).map { it.activityInfo.name }
            assertTrue(
                "Nothing is registered for $action, so capture would stay dead " +
                    "after a reinstall or a reboot. Resolved: $resolved",
                RebindReceiver::class.java.name in resolved,
            )
        }
    }

    /**
     * The ids of every request currently filed under the stage-two unique name.
     *
     * Identity, not emptiness: cancelling unique work leaves its `WorkInfo` behind
     * in `CANCELLED` until something prunes it, and a request enqueued here may
     * also reach `SUCCEEDED` before the assertion runs. A new UUID under the same
     * name is the one signal that survives both.
     */
    private fun scheduledIds(): Set<java.util.UUID> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ParseWorker.UNIQUE_NAME)
            .get(10, TimeUnit.SECONDS)
            .map { it.id }
            .toSet()

    @Test
    fun bothRebindActionsAreProtectedBroadcastsThisAppCannotSendItself() {
        // Not a curiosity: it is the reason exported="false" on the receiver is
        // safe. Only the system may send these, so the manifest entry cannot be
        // driven by another app -- and it is also why no test in this file can
        // deliver the real thing. The platform refuses the app's own uid.
        for (action in listOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) {
            val thrown = runCatching {
                context.sendBroadcast(
                    Intent(context, RebindReceiver::class.java).setAction(action),
                )
            }.exceptionOrNull()

            assertTrue(
                "$action was accepted from this app's own uid, which would mean " +
                    "any app on the device could fake a reinstall. Got: $thrown",
                thrown is SecurityException,
            )
        }
    }

    @Test
    fun theReceiverSchedulesStageTwoOnAReinstallBroadcast() {
        val before = scheduledIds()

        // Called directly, because the previous test shows the platform will
        // not let this process send the real action. What is proved here is the
        // body: given the reinstall action, stage two gets asked for. That the
        // system would deliver it is proved only by an actual reinstall, and
        // that check stays manual.
        RebindReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(
            "A reinstall can leave rows at NEW that no later notification " +
                "would schedule a run for, so the receiver has to ask",
            CaptureFixtures.waitFor { (scheduledIds() - before).isNotEmpty() },
        )
    }

    @Test
    fun theReceiverIgnoresAnActionItWasNotDeclaredFor() {
        val before = scheduledIds()

        // A real broadcast dispatch, through the manifest declaration, using an
        // action nothing protects. An explicit intent reaches the component
        // regardless of its intent-filter, so this is exactly the shape of a
        // stray internal broadcast -- and the receiver must not treat it as a
        // reinstall.
        context.sendBroadcast(
            Intent(context, RebindReceiver::class.java).setAction(UNDECLARED_ACTION),
        )
        Thread.sleep(2_000)

        assertEquals(
            "The receiver acted on an intent its manifest entry does not " +
                "advertise, which is a wider contract than it declares",
            emptySet<java.util.UUID>(),
            scheduledIds() - before,
        )
    }

    private companion object {
        private const val UNDECLARED_ACTION = "my.pinged.capture.test.NOT_A_REBIND"
    }
}
