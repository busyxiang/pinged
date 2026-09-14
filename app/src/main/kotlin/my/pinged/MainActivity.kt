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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import my.pinged.capture.CaptureReport
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ListenerStatus
import my.pinged.ledger.AppViewModelFactory
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.sources.CaptureBanner
import my.pinged.ledger.sources.SourcesScreen
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.PingedTheme

/**
 * The only Activity. It hosts spec 9.1's ledger, which is home, and spec 9.6's
 * allow-list, with spec 10.2's capture-stopped banner above both.
 */
class MainActivity : ComponentActivity() {

    private val health = MutableStateFlow<CaptureReport?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Built once, here, rather than per entry: the factory holds the
        // `Application` itself. See `AppViewModelFactory`.
        val sourcesFactory = AppViewModelFactory(application, ::SourcesViewModel)
        val ledgerFactory = AppViewModelFactory(application, ::LedgerViewModel)
        setContent {
            PingedTheme {
                val backStack = rememberNavBackStack(Ledger)
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
                    // Above the NavDisplay, not inside a screen: capture being
                    // dead matters whichever destination is showing, and one
                    // holder computing it is one place to get it wrong.
                    GrantBanner()
                    NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.pop() },
                        // Both, spelled out, because naming this parameter
                        // replaces the default rather than adding to it --
                        // navigation3-ui 1.1.7 defaults to the saveable-state
                        // decorator alone. Dropping that one breaks
                        // `SavedStateHandle` inside an entry, which the
                        // ViewModel-store decorator depends on; dropping the
                        // ViewModel-store one scopes every `viewModel()` below
                        // to the Activity, so no holder is ever cleared when
                        // its destination goes.
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            entry<Ledger> {
                                val ledger: LedgerViewModel =
                                    viewModel(factory = ledgerFactory)
                                // fillMaxSize, not weight: `Modifier.weight` is
                                // a `ColumnScope` extension and this lambda is
                                // not in one. The NavDisplay is, so the weight
                                // belongs there.
                                LedgerScreen(
                                    viewModel = ledger,
                                    onOpenSources = { backStack.pushOnce(Sources) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<Sources> {
                                val sources: SourcesViewModel =
                                    viewModel(factory = sourcesFactory)
                                SourcesScreen(
                                    viewModel = sources,
                                    // The same expression NavDisplay's onBack
                                    // is given; see `pop`.
                                    onBack = { backStack.pop() },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Spec 10.1's third rebind path is installed process-wide by
        // ForegroundRebinder; this is the screen catching up with whatever the
        // user did in Settings while it was away.
        //
        // The probe first, the report second, and in that order:
        // `CaptureHealth`'s storage flag is written by whatever opens the
        // database, so reading the report first describes the *previous*
        // foreground.
        //
        // **`LedgerViewModel.refresh()` opens the database too, and this
        // explicit probe stays anyway.** The banner is drawn above every
        // destination, so making the flag behind it depend on which destination
        // is composed gets the first foreground of a fresh install wrong.
        lifecycleScope.launch {
            CaptureStorage.guarded(
                applicationContext,
                what = "The ledger cannot be read",
                unavailable = {},
            ) {}
            health.value = ListenerStatus.report(applicationContext)
        }
    }

    /**
     * Three states, not two, and the third is why this is not a straight rendering
     * of [CaptureReport.looksDead].
     *
     * `looksDead` is true the moment the grant is given and before anything has
     * arrived, which is correct as a *report* -- nothing has been seen, and no API
     * distinguishes a silent phone from a dead listener -- and wrong as a *banner*,
     * where it claims a fresh install's phone shut Pinged down and lost spending,
     * both claims about a past that does not exist yet. The "stopped" copy is
     * reserved for a report that has seen something and then stopped.
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
            // tap on a banner. Spec 9.5's settings screen is out of this milestone, so
            // there is nowhere to point at either.
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
        // Both refused. The banner stays up, and the log names the reason
        // rather than leaving a button that silently does nothing.
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
         * value. Both were added in API 30 and `minSdk` is 27, so referencing the
         * symbols would be a lint error while the string is just an intent action
         * older Settings apps do not answer. The fallback covers them, so the literal
         * keeps one code path instead of a version branch.
         */
        private const val ACTION_LISTENER_DETAIL =
            "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"
        private const val EXTRA_LISTENER_COMPONENT =
            "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
    }
}
