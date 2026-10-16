package my.pinged

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.runBlocking

/**
 * Grants `POST_NOTIFICATIONS` and ends first run before any test runs.
 *
 * Opening the capture sources asks for the permission (spec 9.6's prompt needs it), and a
 * system dialog nobody answers would sit over every test that passes through
 * Sources, and outlive the Activity that asked. The ask itself is checked by
 * hand: it is the system's dialog, not this app's.
 *
 * First run (#156) draws onboarding in place of the app on a device without
 * notification access, which a CI emulator is. Every test but
 * `OnboardingTest`, which starts first run again, is about the app behind it.
 */
class PingedTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runBlocking { FirstRun.recordDone(targetContext) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
