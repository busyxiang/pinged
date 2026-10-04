package my.pinged.smoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A fresh install's first capture, on R8's output: the user enables their
 * bank, the bank notifies, and the payment is on the ledger.
 *
 * Every step R8 can break silently is on this path and none is called from
 * here: the system binds the listener by its manifest name, Room finds its
 * generated `_Impl` by name, SQLCipher's native code calls back into Java by
 * name, WorkManager builds the worker from a class name in its own table, and
 * the pack is read and matched. A failure in any of them is an empty ledger,
 * which is why the assertion is on the ledger and not on a step.
 */
@RunWith(AndroidJUnit4::class)
class FirstCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** This APK, which is the bank. See smoke/build.gradle.kts. */
    private val bank: Context = instrumentation.context
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun aFreshInstallWithNotificationAccess() {
        // The listener grant and the Keystore entry are both keyed to the
        // install, so the clear comes first and the grant after it.
        shell("pm clear $APP")
        shell("pm grant ${bank.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $APP/my.pinged.capture.PingedNotificationListener")
        bank.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Payments", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    @Test fun theFirstPaymentAfterEnablingTheBankReachesTheLedger() {
        launch()
        await(By.text("SETTINGS"), "the ledger").click()
        await(By.text("Capture sources"), "settings").click()

        await(By.text(LABEL), "the allow-list row for $LABEL").click()
        assertTrue(
            "The allow-list row for this bank did not turn on when tapped",
            device.wait(Until.hasObject(By.checkable(true).checked(true)), TIMEOUT_MS),
        )

        bank.getSystemService(NotificationManager::class.java).notify(
            1,
            Notification.Builder(bank, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Touch 'n Go eWallet")
                .setContentText("Payment of RM47.19 to foodpanda KLCC successful")
                .build(),
        )

        launch()
        await(By.text(MERCHANT), "the captured payment on the ledger")
        await(By.textContains("47.19"), "the captured amount on the ledger")
    }

    private fun launch() {
        val intent = requireNotNull(bank.packageManager.getLaunchIntentForPackage(APP)) {
            "$APP is not installed, or has no launcher Activity"
        }
        bank.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** Fails with the screen as UiAutomator saw it, which is the only evidence a CI run keeps. */
    private fun await(selector: BySelector, what: String): UiObject2 =
        device.wait(Until.findObject(selector), TIMEOUT_MS) ?: run {
            val screen = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }
            fail("Waited ${TIMEOUT_MS / 1000}s for $what and it never appeared. Screen:\n$screen")
            error("unreachable")
        }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private companion object {
        const val APP = "my.pinged.tracker"
        const val CHANNEL = "payments"
        const val LABEL = "Smoke eWallet"
        const val MERCHANT = "foodpanda KLCC"

        /**
         * Stage two is a WorkManager job enqueued by the listener, and on a cold
         * emulator its first run includes WorkManager opening its own database.
         */
        const val TIMEOUT_MS = 30_000L
    }
}
