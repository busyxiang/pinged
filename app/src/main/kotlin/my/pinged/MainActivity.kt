package my.pinged

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.CaptureReport
import my.pinged.capture.ListenerStatus
import my.pinged.ledger.sources.CaptureBanner
import my.pinged.ledger.sources.SourcesScreen
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.PingedTheme

/**
 * The only Activity, and until this class existed there was none at all: the
 * app installed, bound its listener, and discarded every notification from
 * every package, because default-deny is the correct answer for a source the
 * user has never enabled and there was no way to enable one.
 *
 * It shows spec 9.6's allow-list, with spec 10.2's capture-stopped banner
 * above it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var sources: SourcesViewModel

    private val health = MutableStateFlow<CaptureReport?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sources = SourcesViewModel(applicationContext, lifecycleScope)
        setContent {
            PingedTheme {
                // Insets are handled here rather than inside the screen, at
                // the one place that owns the window. API 35+ draws every app
                // edge-to-edge whatever the theme says about bar colours, so
                // without this the serif title sits under the status bar and
                // the footer under the gesture handle.
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Paper)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    GrantBanner()
                    SourcesScreen(sources, Modifier.weight(1f))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Spec 10.1's third rebind path is installed process-wide by
        // ForegroundRebinder; this is the screen catching up with whatever the user did
        // in Settings while it was away.
        //
        // The banner after the read, and in that order. `refresh()` is what opens the
        // database and therefore what records or clears the storage flag that
        // `ListenerStatus.report` reads. As two independent coroutines the report won
        // -- `refresh` suspends into `Dispatchers.IO` immediately -- so the banner
        // described the *previous* foreground. Both directions were visible on device.
        lifecycleScope.launch {
            sources.refresh().join()
            health.value = ListenerStatus.report(applicationContext)
        }
    }

    /**
     * Three states, not two, and the third is why this is not a straight rendering
     * of [CaptureReport.looksDead].
     *
     * `looksDead` is true the moment the grant is given and before anything has
     * arrived, which is correct as a *report* -- nothing has been seen, and no API
     * distinguishes a silent phone from a dead listener -- and wrong as a *banner*:
     * on a fresh install it told the user their phone had shut Pinged down and that
     * spending had gone unrecorded, both claims about a past that does not exist
     * yet. The "stopped" copy is reserved for a report that has seen something and
     * then stopped.
     */
    @Composable
    private fun GrantBanner() {
        val current = health.collectAsState().value ?: return
        val neverSeen = current.lastNotificationAt == 0L

        when {
            // First, because it is the only state here where capture is certainly dead
            // rather than suspected dead -- and the only one every other signal
            // contradicts. The heartbeat is written before the first database call, so a
            // phone in this state reads as granted, recently active and not stale while
            // storing nothing.
            //
            // No action button. Both spec 11.1 remedies -- start fresh, or restore from a
            // JSON export -- destroy or replace data, and neither belongs behind a single
            // tap on a banner.
            //
            // The copy says the data is intact and cannot be read, rather than pointing at
            // a Settings screen that does not exist: `MainActivity` is the only Activity
            // and the allow-list the only screen in it.
            current.storageUnavailable -> CaptureBanner(
                label = "CAPTURE STOPPED" + Separator + "CANNOT OPEN YOUR DATA",
                body = "Pinged cannot open its database: the key that unlocks it " +
                    "is gone from this phone. This happens after some device-to-" +
                    "device transfers. Nothing new is being recorded, and nothing " +
                    "already saved has been deleted -- but until the key comes " +
                    "back, none of it can be read either.",
            )
            !current.granted -> CaptureBanner(
                label = "CAPTURE OFF" + Separator + "NO NOTIFICATION ACCESS",
                body = "Pinged cannot see your bank's notifications yet. Nothing " +
                    "has been recorded, and Android cannot go back for what it " +
                    "missed.",
                actionLabel = "Turn on notification access",
                onAction = ::openListenerSettings,
            )
            neverSeen -> CaptureBanner(
                label = "CAPTURE ON" + Separator + "NOTHING SEEN YET",
                body = "Notification access is granted and nothing has reached " +
                    "Pinged yet. On a new install that is expected until an app " +
                    "on this phone posts something.",
                actionLabel = "Check notification access",
                onAction = ::openListenerSettings,
            )
            current.looksDead -> CaptureBanner(
                label = "CAPTURE STOPPED" + Separator + "NOTHING SEEN IN A DAY",
                body = "Access is granted but no notification of any kind has " +
                    "reached Pinged for over a day. Your phone may have shut it " +
                    "down. Nothing you spent since then was recorded.",
                actionLabel = "Check notification access",
                onAction = ::openListenerSettings,
            )
        }
    }

    /**
     * Spec 10.4: prefer the API 30 screen that opens this app's own toggle, fall
     * back to the list.
     *
     * Wrapped against `Throwable`, not `ActivityNotFoundException`: OEM settings
     * activities are frequently `exported="false"`, which makes `resolveActivity`
     * return non-null and `startActivity` then throw `SecurityException` -- a
     * different exception from a different call, and catching only the obvious one
     * is how this crashes on exactly the ROMs spec 10 exists for.
     */
    private fun openListenerSettings() {
        val detail = Intent(ACTION_LISTENER_DETAIL).putExtra(
            EXTRA_LISTENER_COMPONENT,
            my.pinged.capture.PingedComponents.listener(this).flattenToString(),
        )
        if (start(detail)) return
        if (start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        // Both refused. Saying nothing here would leave a button that does
        // nothing, which is worse than a screen that admits it cannot get you
        // there; the banner stays up and the log names the reason.
        Log.w(TAG, "Neither notification-access screen would open on this device")
    }

    private fun start(intent: Intent): Boolean =
        runCatching { startActivity(intent); true }
            .onFailure { Log.w(TAG, "Could not open ${intent.action}", it) }
            .getOrDefault(false)

    private companion object {
        private const val TAG = "PingedMain"

        /**
         * `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` and its extra, by
         * value. Both were added in API 30 and this compiles against 36, so the
         * constants resolve -- but `minSdk` is 27, and referencing the symbol would be
         * a lint error there while the string is just an intent action older Settings
         * apps do not answer. The fallback covers them, so the literal keeps one code
         * path instead of a version branch.
         */
        private const val ACTION_LISTENER_DETAIL =
            "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"
        private const val EXTRA_LISTENER_COMPONENT =
            "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
    }
}
