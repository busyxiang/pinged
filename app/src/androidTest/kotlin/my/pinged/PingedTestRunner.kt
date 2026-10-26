package my.pinged

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import kotlinx.coroutines.delay
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
 *
 * `-Pandroid.testInstrumentationRunnerArguments.holdSampleMs=N` holds every
 * `AppStatus` sample N ms, so a test that acts on the app before the first
 * sample has drawn it fails every time instead of on CI's slow runs.
 */
class PingedTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        runBlocking { FirstRun.recordDone(targetContext) }
        arguments?.getString(HOLD_SAMPLE_MS)?.toLong()?.let { held -> AppStatus.beforeSample = { delay(held) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val HOLD_SAMPLE_MS = "holdSampleMs"
    }
}
