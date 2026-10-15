package my.pinged.capture

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.entity.CaptureSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 10.2's daily check reaching a user who is not opening the app: the
 * real worker, the real database, and the notification it leaves in the shade.
 */
@RunWith(AndroidJUnit4::class)
class CaptureHealthWorkerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val sources = Databases.captureSourceDao(context)

    private val day = 24 * 60 * 60 * 1000L
    private val bank = "com.example.gaps.worker${System.nanoTime()}"
    private val label = "Worker Bank ${System.nanoTime()}"

    @Before
    fun healthyListener() = runBlocking {
        CaptureFixtures.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        CaptureFixtures.shell("cmd notification allow_listener ${PingedComponents.listener(context).flattenToString()}")
        assertTrue(CaptureFixtures.waitFor { ListenerStatus.isGranted(context) })
        // A fresh heartbeat, so the only thing wrong is the quiet source. No
        // `forgetProcessMemo`: the throttle skips this only when it wrote in
        // the last five minutes, which is fresh too, and forgetting would also
        // clear `CaptureDays`' bound-today memo that `ForegroundCaptureDayTest`
        // waits on while the listener stays bound.
        CaptureHealth.recordSeen(context, System.currentTimeMillis())
        HealthCheck.forgetAlerted(context)
        notifications.cancelAll()
    }

    @After
    fun stopWatchingTheTestSource() {
        sources.setEnabled(bank, false)
        notifications.cancelAll()
    }

    private fun quietSource() {
        sources.insertIfNew(CaptureSource(pkg = bank, label = label, firstSeenAt = 0L))
        sources.setEnabled(bank, true)
        sources.setLastNotificationAt(bank, System.currentTimeMillis() - 20 * day)
        sources.setExpectedMonthlyCount(bank, 40)
    }

    private fun run(): ListenableWorker.Result =
        TestListenableWorkerBuilder<CaptureHealthWorker>(context).build().startWork().get()

    /** Ours, on the capture channel, that name the test source. */
    private fun postedAboutTheBank(): List<Notification> =
        notifications.activeNotifications
            .map { it.notification }
            .filter { it.channelId == SourcePrompt.CHANNEL_ID }
            .filter { n ->
                listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT)
                    .any { n.extras.getCharSequence(it)?.contains(label) == true }
            }

    @Test
    fun aQuietSourceIsPostedOnceAndNotEveryDay() {
        quietSource()

        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(
            "The quiet source was not named in any notification",
            CaptureFixtures.waitFor { postedAboutTheBank().isNotEmpty() },
        )

        notifications.cancelAll()
        run()
        Thread.sleep(500)
        assertTrue("The same news was posted again", postedAboutTheBank().isEmpty())
    }

    @Test
    fun aForegroundSchedulesTheDailyCheck() {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(CaptureHealthWorker.UNIQUE_NAME).result.get()
        work.pruneWork().result.get()

        ListenerStatus.onAppForeground(context)

        assertTrue(
            "No daily check was scheduled",
            CaptureFixtures.waitFor {
                work.getWorkInfosForUniqueWork(CaptureHealthWorker.UNIQUE_NAME).get()
                    .any { it.state == WorkInfo.State.ENQUEUED }
            },
        )
    }

    /** A heartbeat file that will not read is not evidence that all is well. */
    @Test
    fun anUnreadableHeartbeatLeavesAStandingAlertUp() = runBlocking {
        quietSource()
        run()
        assertTrue(CaptureFixtures.waitFor { postedAboutTheBank().isNotEmpty() })

        val unreadable = CaptureReport(
            granted = true,
            lastNotificationAt = 0L,
            staleForMillis = Long.MAX_VALUE,
            healthUnreadable = true,
        )
        HealthCheck.settle(context, unreadable, quiet = emptyList())
        Thread.sleep(500)

        assertTrue("An unreadable heartbeat withdrew a standing alert", postedAboutTheBank().isNotEmpty())
    }

    @Test
    fun aSourceThatSpeaksAgainWithdrawsTheAlert() {
        quietSource()
        run()
        assertTrue(CaptureFixtures.waitFor { postedAboutTheBank().isNotEmpty() })

        sources.setLastNotificationAt(bank, System.currentTimeMillis())
        run()

        assertTrue(
            "The alert about a source that spoke again stayed up",
            CaptureFixtures.waitFor { postedAboutTheBank().isEmpty() },
        )
    }
}
