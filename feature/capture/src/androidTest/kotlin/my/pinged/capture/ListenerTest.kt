package my.pinged.capture

import my.pinged.data.Databases
import my.pinged.data.LocalDates
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real delivery path: the system binds the listener, a notification is
 * posted, and the row appears.
 *
 * This is the only test that proves the wiring -- the manifest entry, the
 * frozen component name, the grant, and the callback -- rather than the
 * decisions. `IngestTest` covers the decisions, and can post as packages this
 * test cannot.
 *
 * The listener skips its own package (spec 10.2) and a test APK cannot post as
 * a bank, so [CaptureIngest.captureOwnPackage] is flipped for the duration.
 * That seam is module-internal and `false` in every build.
 */
@RunWith(AndroidJUnit4::class)
class ListenerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dao = Databases.rawCaptureDao(context)
    private val notifications =
        context.getSystemService(NotificationManager::class.java)

    private val channelId = "capture-e2e"

    @Before
    fun grantAccessAndBind() {
        val component = PingedComponents.listener(context).flattenToString()
        CaptureFixtures.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        CaptureFixtures.shell("cmd notification allow_listener $component")
        notifications.createNotificationChannel(
            NotificationChannel(channelId, "Capture", NotificationManager.IMPORTANCE_DEFAULT),
        )
        CaptureIngest.captureOwnPackage = true

        assertTrue(
            "The notification-access grant did not take. Shell said: " +
                CaptureFixtures.shell("cmd notification allowed_listeners"),
            CaptureFixtures.waitFor { notifications.isNotificationListenerAccessGranted(component.toComponent()) },
        )
        // The grant and the binding are independent (spec 10.1), so waiting on
        // the grant is not waiting on the bind. There is no public signal for
        // "bound", so the wait below is on the effect: the first post either
        // lands or the test fails saying so.
    }

    @After
    fun resetTestSeam() {
        CaptureIngest.captureOwnPackage = false
        notifications.cancelAll()
        // These tests really do schedule stage two, because the listener really
        // does. Cancelling it here keeps a real worker from waking up later and
        // writing to the shared database while another test class is asserting
        // against it.
        CaptureFixtures.cancelStageTwo(context)
    }

    private fun String.toComponent() =
        android.content.ComponentName.unflattenFromString(this)!!

    private fun post(id: Int, text: String) {
        notifications.notify(
            id,
            CaptureFixtures.notification(context, title = "Bank", text = text, channel = channelId),
        )
    }

    @Test
    fun aNotificationFromAnEnabledPackageIsCaptured() {
        CaptureFixtures.allowList(context, context.packageName, enabled = true)
        val marker = "ZZE2EENABLED" + System.nanoTime()
        val before = dao.countAll()

        post(101, "Payment of RM12.00 to $marker successful")

        assertTrue(
            "No capture row appeared within the timeout",
            CaptureFixtures.waitFor { dao.countAll() > before },
        )
        assertTrue(
            "The captured row does not contain the posted text",
            CaptureFixtures.textInDatabase(context, marker).contains("raw_capture.text"),
        )
        // Stage two is asked for, through the real listener, once a row exists.
        // Without this the row is durable and never parsed: the ledger stays
        // empty and nothing anywhere says why.
        assertTrue(
            "The listener did not schedule stage two after writing a row",
            CaptureFixtures.waitFor {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(ParseWorker.UNIQUE_NAME)
                    .get()
                    .isNotEmpty()
            },
        )
    }

    /**
     * Spec 9.6's invariant, through the real system path: a notification from a
     * package that is not enabled produces no `raw_capture` row and no stored
     * content of any kind.
     */
    @Test
    fun aNotificationFromADisabledPackageStoresNothing() {
        CaptureFixtures.allowList(context, context.packageName, enabled = false)
        val marker = "ZZE2EDISABLED" + System.nanoTime()
        val before = dao.countAll()

        post(102, "Payment of RM99.00 to $marker successful")

        // A negative through an asynchronous path needs a settling window, or
        // it passes because nothing has happened yet. The enabled case above
        // measures how long the same path takes to write a row.
        Thread.sleep(3_000L)

        assertEquals(before, dao.countAll())
        assertEquals(
            "The text of a disabled package's notification reached the database",
            emptyList<String>(),
            CaptureFixtures.textInDatabase(context, marker),
        )
    }

}
