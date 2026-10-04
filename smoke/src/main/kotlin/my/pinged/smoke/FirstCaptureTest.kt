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
        tapUntil(By.text("SETTINGS"), By.text("Capture sources"), "settings")
        tapUntil(By.text("Capture sources"), By.text(LABEL), "the allow-list")

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
        device.wait(Until.findObject(selector), TIMEOUT_MS) ?: failWithScreen("Waited ${TIMEOUT_MS / 1000}s for $what and it never appeared")

    private fun tapUntil(target: BySelector, next: BySelector, what: String) {
        if (!device.tapUntil(target, next, TIMEOUT_MS)) failWithScreen("Tapped for $what for ${TIMEOUT_MS / 1000}s and it never opened")
    }

    private fun failWithScreen(message: String): Nothing {
        val screen = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }
        fail("$message. Screen:\n$screen")
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

/**
 * Tap [target] until [next] is on screen, or [timeoutMs] passes; true if it is.
 *
 * **One tap is not enough, because the screen can move between UiAutomator
 * reading [target]'s bounds and the tap arriving.** On a fresh install the
 * banner strip is drawn only once `MainActivity.sampleHealth` has opened the
 * database, which can be seconds after the first frame, and it pushes the top
 * bar 208px down: a tap aimed at SETTINGS then lands on the banner's text.
 * Measured on a 320x640 emulator tapping at first sight: 2 misses in 20
 * (issue #16). Re-tapping is safe only for navigation -- `pushOnce` makes a
 * second tap on a screen already opening a no-op -- so a toggle must not
 * come through here.
 */
internal fun UiDevice.tapUntil(target: BySelector, next: BySelector, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val left = deadline - System.currentTimeMillis()
        val found = wait(Until.findObject(target), left) ?: return hasObject(next)
        runCatching { found.click() } // stale if the screen changed under it; the next pass re-finds it
        if (wait(Until.hasObject(next), minOf(TAP_SETTLE_MS, deadline - System.currentTimeMillis()))) return true
    }
    return hasObject(next)
}

/** How long one tap gets to open its screen; the slowest open measured idle was 1.9s. */
private const val TAP_SETTLE_MS = 5_000L

