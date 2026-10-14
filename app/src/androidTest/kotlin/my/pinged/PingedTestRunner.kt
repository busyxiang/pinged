package my.pinged

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Grants `POST_NOTIFICATIONS` before any test runs.
 *
 * Opening the capture sources asks for it (spec 9.6's prompt needs it), and a
 * system dialog nobody answers would sit over every test that passes through
 * Sources, and outlive the Activity that asked. The ask itself is checked by
 * hand: it is the system's dialog, not this app's.
 */
class PingedTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
