package my.pinged

import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigationevent.NavigationEvent
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import my.pinged.capture.CaptureReport
import my.pinged.capture.CaptureStorage
import my.pinged.capture.ListenerStatus
import my.pinged.data.Databases
import my.pinged.data.IntegrityStore
import my.pinged.ledger.AppViewModelFactory
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.settings.DocumentSink
import my.pinged.ledger.settings.ExportSheet
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.corrections.CorrectionsScreen
import my.pinged.ledger.corrections.CorrectionsViewModel
import my.pinged.ledger.learned.LearnedScreen
import my.pinged.ledger.learned.LearnedViewModel
import my.pinged.ledger.settings.SettingsScreen
import my.pinged.ledger.settings.SettingsState
import my.pinged.ledger.settings.SettingsViewModel
import my.pinged.ledger.sources.CaptureBanner
import my.pinged.ledger.sources.SourcesScreen
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.theme.EXPORT_EVERYTHING
import my.pinged.ledger.theme.RESCUE
import my.pinged.ledger.theme.ReceiptSheet
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.PingedTheme
import my.pinged.ledger.transfer.TransferStore

/**
 * The only Activity. It hosts spec 9.1's ledger, which is home; spec 9.5's
 * settings; and spec 9.6's allow-list, reachable from settings. One banner
 * strip sits above all three: spec 10.2's capture states, and the backup nudge
 * that [bannerFor] ranks below every one of them. The bottom bar sits under the
 * two tab roots and not under a pushed screen.
 */
class MainActivity : ComponentActivity() {

    private val health = MutableStateFlow<CaptureReport?>(null)

    /**
     * [exportIsOverdue]'s answer, alongside [health] because the banner strip
     * is one decision over both.
     *
     * Starts false, so the nudge is never the first thing drawn on a resume
     * that has not yet reached the database. `null` would be a third state the
     * banner has no wording for.
     *
     * Sampled in [onResume] and kept in step by [watchTheBackup] after that.
     */
    private val exportOverdue = MutableStateFlow(false)

    /**
     * Whether damage is on record, which decides what the backup nudge's
     * button says ([nudgeAction]). Kept in step by [watchTheBackup]; false
     * when the store will not read, which leaves the nudge saying what it
     * always said.
     */
    private val ledgerDamaged = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        watchTheBackup()
        // Built once, here, rather than per entry: the factory holds the
        // `Application` itself. See `AppViewModelFactory`.
        val sourcesFactory = AppViewModelFactory(application, ::SourcesViewModel)
        val ledgerFactory = AppViewModelFactory(application, ::LedgerViewModel)
        val settingsFactory = AppViewModelFactory(application, ::SettingsViewModel)
        val correctionsFactory = AppViewModelFactory(application, ::CorrectionsViewModel)
        val learnedFactory = AppViewModelFactory(application, ::LearnedViewModel)
        setContent {
            PingedTheme {
                // One saved stack per tab and the tab showing, kept in saved
                // state for the reason `Ledger` gives. `TabStacks` holds the
                // rules; see it for why `NavDisplay` is handed every tab's
                // entries and not the shown tab's.
                val selection = rememberSaveable { mutableStateOf(Tab.Spending) }
                val spendingStack = rememberNavBackStack(Tab.Spending.root)
                val settingsStack = rememberNavBackStack(Tab.Settings.root)
                val tabs = remember(selection, spendingStack, settingsStack) {
                    TabStacks(
                        mapOf(Tab.Spending to spendingStack, Tab.Settings to settingsStack),
                        selection,
                    )
                }
                val predictivePop = defaultPredictivePopTransitionSpec<NavKey>()
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
                    // The tab stacks live in this composition and not in the
                    // Activity, so the banners with somewhere to go are
                    // handed the destination rather than reaching for it.
                    // Both buttons lead to settings, from its root: `TabStacks`
                    // selects the tab and unwinds whatever is pushed over it.
                    // On settings' own root it would do nothing, so there is no
                    // button (ruling R35).
                    val onSettings = tabs.showingSettingsRoot
                    GrantBanner(
                        onExportEverything = tabs::showSettings.takeUnless { onSettings },
                        onOpenSettings = tabs::showSettings.takeUnless { onSettings },
                    )
                    NavDisplay(
                        backStack = tabs.entries,
                        // `entries` always holds both tabs' roots, so NavDisplay
                        // always takes Back; an unhandled one (`false`, on
                        // Spending's root) must leave the app, or the user is
                        // trapped. `moveTaskToBack` is what a launcher Activity's
                        // unhandled Back does on Android 12+.
                        onBack = { if (!tabs.back()) moveTaskToBack(true) },
                        // The chevron pops with `popTransitionSpec`, a cross-fade
                        // by default, and the back gesture with
                        // `predictivePopTransitionSpec`, a scale to 0.7 on a
                        // spring: two looks for one move (#56). The gesture's
                        // is the system's, so the chevron takes it. A tap has
                        // no swipe edge, hence `EDGE_NONE`.
                        // `BackTransitionTest` holds the two looks equal.
                        popTransitionSpec = { predictivePop(this, NavigationEvent.EDGE_NONE) },
                        predictivePopTransitionSpec = predictivePop,
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
                                    // Settings, not `Sources`: the
                                    // allow-list is a row inside settings
                                    // (spec 9.5).
                                    onOpenSettings = tabs::showSettings,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<Settings> {
                                val settings: SettingsViewModel =
                                    viewModel(factory = settingsFactory)
                                var exportOpen by remember { mutableStateOf(false) }
                                // The banner above says "Settings says more";
                                // each time settings reads storage, the banner
                                // reads it again, so the two cannot disagree
                                // for the rest of the visit -- a full disk that
                                // has cleared, say, with nothing resuming.
                                // **Keyed on the read, not on what it found**:
                                // a capture refused just as a gate lifts can
                                // raise the flag between a sample's probe and
                                // its report, over a ledger settings reads as
                                // healthy again and again. Each read re-samples,
                                // and the sample's probe clears it.
                                val read by settings.state.collectAsState()
                                LaunchedEffect(read.reads) {
                                    if (read.reads > 0) sampleHealth()
                                }
                                // `OpenDocument`, not `GetContent`: `GetContent`
                                // hands back a transient URI a provider may
                                // revoke the moment this callback returns, and
                                // `Restore.replaceEverything` needs the stream
                                // to survive being copied to a cache file first.
                                //
                                // `arrayOf("*/*")`, not `"application/json"` --
                                // spec 12: MIME filtering is unreliable across
                                // document providers, so the picker accepts
                                // anything and `Restore` validates what comes
                                // back before it destroys anything (Task 6).
                                //
                                // A null `Uri` -- the user backed out of the
                                // picker -- starts nothing. One the provider
                                // will not open is opened by the restore, off
                                // this thread, and reported as its refusal:
                                // `openInputStream` throws for it, and here,
                                // on the main thread, that would kill the app.
                                val restoreLauncher = rememberLauncherForActivityResult(
                                    ActivityResultContracts.OpenDocument(),
                                ) { uri ->
                                    if (uri != null) settings.restoreFrom { contentResolver.openInputStream(uri) }
                                }
                                // The damaged state's rescue, into a document
                                // the user creates as export's is: no sheet of
                                // its own, since the recovery notice has
                                // already said what it is for.
                                val rescueLauncher = rememberLauncherForActivityResult(
                                    ActivityResultContracts.CreateDocument("application/json"),
                                ) { uri -> if (uri != null) settings.salvageTo(DocumentSink(contentResolver, uri)) }
                                SettingsScreen(
                                    viewModel = settings,
                                    onOpenSources = { tabs.push(Sources) },
                                    onOpenCorrections = { tabs.push(Corrections) },
                                    onOpenLearned = { tabs.push(Learned) },
                                    onExport = { exportOpen = true },
                                    // Reached only after `SettingsScreen`'s own
                                    // confirmation -- see `RestoreConfirmSheet`
                                    // -- so the picker never opens ahead of the
                                    // user knowing what a restore costs.
                                    onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
                                    onRescue = { rescueLauncher.launch(RESCUE_FILE_NAME) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (exportOpen) {
                                    val state = read
                                    ExportSheetHost(
                                        state = state,
                                        onExport = { uri -> settings.exportTo(DocumentSink(contentResolver, uri)) },
                                        // Dismissing the sheet is having seen
                                        // what it said about the last export,
                                        // so the next one opens on its button.
                                        onDismiss = {
                                            exportOpen = false
                                            state.outcome
                                                ?.takeIf { it.operation == Operation.EXPORT }
                                                ?.let(settings::acknowledge)
                                        },
                                    )
                                }
                            }
                            entry<Corrections> {
                                val corrections: CorrectionsViewModel =
                                    viewModel(factory = correctionsFactory)
                                CorrectionsScreen(
                                    viewModel = corrections,
                                    onBack = tabs::pop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            entry<Learned> {
                                val learned: LearnedViewModel = viewModel(factory = learnedFactory)
                                LearnedScreen(
                                    viewModel = learned,
                                    onBack = tabs::pop,
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
                                    onBack = tabs::pop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (tabs.showsBar) TabBar(selected = tabs.selected, onSelect = tabs::select)
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
        //
        // **Neither line may throw.** This scope carries no
        // `CoroutineExceptionHandler`, so anything escaping either call reaches
        // the thread's default handler and kills the process on the home
        // screen, on every foreground, for as long as the fault lasts. Both
        // carry their own guard for that reason: [readExportOverdue] below, and
        // `ListenerStatus.report`, which answers with
        // `CaptureReport.healthUnreadable` rather than throwing or claiming
        // health.
        lifecycleScope.launch { sampleHealth() }
    }

    /**
     * [exportOverdue] and [health], read again: [readExportOverdue]'s
     * `CaptureStorage.guarded` open is the probe [onResume]'s KDoc says must
     * come first. Neither call may throw; see there.
     */
    private suspend fun sampleHealth() {
        exportOverdue.value = readExportOverdue(applicationContext)
        betweenProbeAndReport()
        health.value = ListenerStatus.report(applicationContext)
    }

    /**
     * Keep [exportOverdue] in step with the store for as long as the app is on
     * screen.
     *
     * **A watcher and not a second sample, because the one banner that carries
     * a button is the one whose button settles what it reports.** "Export
     * everything" pushes settings onto this Activity's own back stack, so the
     * export that follows happens inside one foreground: popping back is
     * navigation, not a resume, and the one resume that does fire -- the
     * save-file picker returning -- is before `Transfers.export`
     * records anything. A flag sampled only in [onResume] would go on telling a
     * user who has just exported that nothing is saved anywhere, for the rest
     * of the session.
     *
     * [TransferStore.lastExportAtChanges] and not a callback on the tap,
     * because the stored timestamp is what the banner is about: an export that
     * failed never records one, so nothing emits and the nudge stays up.
     * Nothing polls.
     *
     * **`Databases.rewrites` too, and it re-samples [health] as well.**
     * The nudge's other half is the ledger's row count: a delete or restore
     * that replaces the database and then fails before `TransferStore.forget`
     * -- a Keystore entry that will not delete, an import that does not
     * finish -- changes that count with no store write to announce it. And
     * the storage banner is about the database a restore has just replaced:
     * sampled only on resume, "Pinged cannot open its database" would stand
     * over settings' "BACKUP RESTORED" after a restore from key-gone for the
     * rest of the visit, because nothing in that visit resumes.
     *
     * Changes only, and no sample on subscription: the [onResume] that follows
     * every `onStart` reads the stored value for itself, alongside the database
     * probe that has to precede [ListenerStatus.report].
     * [TransferStore.lastExportAtChanges] is where that drop lives and why.
     */
    private fun watchTheBackup() {
        lifecycleScope.launch {
            // Tied to STARTED so the collection ends with the screen; a plain
            // `lifecycleScope.launch` per foreground would leave one collector
            // per foreground running until the Activity is destroyed.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // `drop(1)`: the value on subscription is not a change, for
                // the same reason the store's own first value is not. Its own
                // collection, so the store's failure below cannot end it.
                launch { Databases.rewrites.drop(1).collect { sampleHealth() } }
                // And on the stored flag changing, which is what clears a
                // banner sampled inside a refusal's window: the clear comes
                // from whichever open succeeds next, and only this hears it.
                launch {
                    ListenerStatus.storageUnavailableChanges(applicationContext)
                        .catch { thrown -> Log.w(TAG, "Could not watch the storage flag", thrown) }
                        .collect { sampleHealth() }
                }
                launch {
                    IntegrityStore.damagedUpdates(applicationContext)
                        .catch { thrown ->
                            Log.w(TAG, "Could not watch the integrity verdict", thrown)
                            ledgerDamaged.value = false
                        }
                        .collect { ledgerDamaged.value = it }
                }
                TransferStore.lastExportAtChanges(applicationContext)
                    .catch { thrown ->
                        // The store cannot be read at all. Answering false for
                        // the same reason [readExportOverdue] does: this scope
                        // carries no `CoroutineExceptionHandler`, so a throw
                        // here kills the process on the home screen.
                        //
                        // **`catch` is upstream-only and it ends the
                        // collection.** It sees what the store flow throws and
                        // nothing the `collect` lambda below throws -- that one
                        // guards itself -- and once it has run, this collection
                        // is over: nothing re-subscribes until the next STARTED,
                        // so the nudge stays down for the rest of this
                        // foreground however the store recovers. Acceptable
                        // because the next foreground re-enters
                        // `repeatOnLifecycle` and `onResume` samples again, and
                        // because down is the direction that says nothing rather
                        // than the direction that makes a claim.
                        Log.w(TAG, "Could not watch the last export", thrown)
                        exportOverdue.value = false
                    }
                    .collect { exportOverdue.value = readExportOverdue(applicationContext) }
            }
        }
    }

    /**
     * Copy for whichever kind [bannerFor] chose, and nothing else.
     *
     * The choosing is in [bannerFor] because it is the part with a safety
     * property; this half is wording, colour and a tap target, and needs a
     * device to see at all.
     *
     * [onExportEverything] and [onOpenSettings] are parameters rather than
     * methods on this class because they navigate, and the back stack belongs
     * to the composition in `onCreate`. Both go to settings today; they are
     * two because they answer different banners, and one of them moving
     * should not move the other. Null draws the banner without its button.
     */
    @Composable
    private fun GrantBanner(onExportEverything: (() -> Unit)?, onOpenSettings: (() -> Unit)?) {
        val current = health.collectAsState().value ?: return
        val damaged = ledgerDamaged.collectAsState().value
        val kind = bannerFor(current, exportOverdue.collectAsState().value) ?: return

        when (kind) {
            // The button navigates and does not act. Both spec 11.1 remedies --
            // start fresh, or restore from a JSON export -- destroy or replace
            // data, and neither belongs behind a single tap on a banner; each
            // lives in settings behind its own confirmation. Settings is also the
            // only screen that knows which failure this is:
            // `CaptureHealth.recordStorageUnavailable` keeps one boolean for the
            // whole `DatabaseUnavailableException` family, and
            // `SettingsViewModel.opened` tells them apart by type.
            //
            // So the body names no cause. A key gone, a full disk and the user's
            // own delete in progress all raise this flag, and it says nothing
            // about what is already saved either: mid-delete, "nothing has been
            // deleted" is exactly false.
            BannerKind.STORAGE_UNAVAILABLE -> CaptureBanner(
                label = "CAPTURE STOPPED" + Separator + "CANNOT OPEN YOUR DATA",
                body = "Pinged cannot open its database, so nothing new is being " +
                    "recorded. Settings says more about what has happened and " +
                    "what can be done.",
                actionLabel = "What can I do about this?",
                onAction = onOpenSettings,
            )
            BannerKind.NOT_GRANTED -> CaptureBanner(
                label = "CAPTURE OFF" + Separator + "NO NOTIFICATION ACCESS",
                body = "Pinged cannot see your bank's notifications yet. Nothing " +
                    "has been recorded, and Android cannot go back for what it " +
                    "missed.",
                actionLabel = "Turn on notification access",
                onAction = ::openListenerSettings,
            )
            // No action button: there is no remedy to offer. The capture
            // banners' button opens the notification-access screen, which
            // answers a question this state has not asked -- the grant is
            // present, and what is broken is a file in this app's own storage
            // that no settings screen touches. A button that changes nothing
            // would read as one that fixes this.
            BannerKind.HEALTH_UNREADABLE -> CaptureBanner(
                label = "CAPTURE UNKNOWN" + Separator + "CANNOT READ ITS OWN RECORD",
                body = "Notification access is granted, but Pinged cannot read the " +
                    "file where it notes what it has seen. So it cannot tell you " +
                    "whether capture is still working: it may be recording " +
                    "normally, or it may have stopped days ago. What is already " +
                    "in your ledger is unaffected -- that is kept elsewhere.",
            )
            BannerKind.NOTHING_SEEN -> CaptureBanner(
                label = "CAPTURE ON" + Separator + "NOTHING SEEN YET",
                body = "Notification access is granted and nothing has reached " +
                    "Pinged yet. On a new install that is expected until an app " +
                    "on this phone posts something.",
                actionLabel = "Check notification access",
                onAction = ::openListenerSettings,
            )
            BannerKind.STOPPED -> CaptureBanner(
                label = "CAPTURE STOPPED" + Separator + "NOTHING SEEN IN A DAY",
                body = "Access is granted but no notification of any kind has " +
                    "reached Pinged for over a day. Your phone may have shut it " +
                    "down. Nothing you spent since then was recorded.",
                actionLabel = "Check notification access",
                onAction = ::openListenerSettings,
            )
            // Settings, not the export sheet: the sheet is hosted there, and a
            // banner that opened a modal over whatever destination the user was
            // on would be the only control in the app that does that. Settings
            // offers the export on a healthy ledger and the rescue on a damaged
            // one (design 6), so the button names whichever it will find.
            BannerKind.NUDGE_EXPORT -> CaptureBanner(
                label = "NO BACKUP" + Separator + "NOTHING SAVED ANYWHERE ELSE",
                body = "Pinged has no internet permission, so this phone holds the " +
                    "only copy of what it has recorded. If the phone is lost or " +
                    "reset, so is all of it.",
                actionLabel = nudgeAction(damaged),
                onAction = onExportEverything,
                dismissLabel = NUDGE_DISMISS,
                onDismiss = ::dismissNudge,
            )
        }
    }

    /**
     * Silence the backup nudge for [NUDGE_DISMISSED_FOR_MILLIS].
     *
     * Re-reads rather than setting [exportOverdue] to false, so the banner
     * goes because the stored dismissal says so: a write that failed leaves it
     * up, which is the truth about what the next foreground will show.
     */
    private fun dismissNudge() {
        lifecycleScope.launch {
            try {
                TransferStore.recordNudgeDismissed(applicationContext, System.currentTimeMillis())
            } catch (thrown: IOException) {
                // No `CoroutineExceptionHandler` on this scope; see [onResume].
                Log.w(TAG, "Could not record the backup nudge's dismissal", thrown)
            }
            exportOverdue.value = readExportOverdue(applicationContext)
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
        // Fully qualified: `Settings` unqualified names this package's own
        // NavKey (spec 9.5), and shadows the framework class of the same
        // simple name that this fallback intent needs.
        if (start(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        // Both refused. The banner stays up, and the log names the reason
        // rather than leaving a button that silently does nothing.
        Log.w(TAG, "Neither notification-access screen would open on this device")
    }

    private fun start(intent: Intent): Boolean =
        runCatching { startActivity(intent); true }
            .onFailure { Log.w(TAG, "Could not open ${intent.action}", it) }
            .getOrDefault(false)

    private companion object {
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

/** One tag for this file: the functions below are `MainActivity`'s own. */
private const val TAG = "PingedMain"

/**
 * Tests only: run inside `MainActivity.sampleHealth` between its probe and
 * its report -- the moment a capture refused elsewhere can raise the storage
 * flag the probe has just cleared.
 */
@VisibleForTesting @Volatile internal var betweenProbeAndReport: suspend () -> Unit = {}

/**
 * **Thirty days is a guess**, with no evidence behind it, for a ledger that
 * gains roughly 112 transactions a month (spec 15).
 *
 * The 850 in the same sentence of spec 15 counts *captured notifications*, and
 * is not the figure this window is measured against: what [exportIsOverdue]
 * weighs is `TxnDao.countAll()` over the `txn` table, so thirty days here is
 * about a hundred transactions at risk rather than eight hundred rows.
 */
internal const val EXPORT_STALE_AFTER_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * How long dismissing the backup nudge silences it. Seven days because that is
 * what was asked for; like the thirty above, nothing measured it.
 */
internal const val NUDGE_DISMISSED_FOR_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * Whether to tell the user their data is not backed up anywhere.
 *
 * There has to be something to lose: a banner on an empty ledger is the app
 * inventing a problem.
 *
 * [txns] is `countAll()` and so includes `REJECTED` rows, unlike the delete
 * sheet's count, which excludes them to agree with the feed. The question is
 * different: the sheet itemises what the user would recognise losing, and this
 * asks only whether an export file would carry anything at all.
 */
internal fun exportIsOverdue(txns: Int, lastExportAt: Long, now: Long, nudgeDismissedAt: Long = 0L): Boolean {
    if (txns == 0) return false
    // `now >= nudgeDismissedAt`: a dismissal in the future is a clock set
    // back, and silencing until the clock catches up could be years.
    if (now >= nudgeDismissedAt && now - nudgeDismissedAt < NUDGE_DISMISSED_FOR_MILLIS) return false
    return now - lastExportAt > EXPORT_STALE_AFTER_MILLIS
}

/**
 * [exportIsOverdue] over the live numbers, or false if any part of reading them
 * fails.
 *
 * **The guard is around the whole computation, and it is wider than
 * [CaptureStorage.guarded]'s own.** That one catches
 * `DatabaseUnavailableException` and nothing else. `TxnDao.countAll` raises
 * `SQLiteException` from a scan that aborts mid-count;
 * [TransferStore.lastExportAt] is a DataStore read, which raises `IOException`
 * -- a `CorruptionException` on a truncated preferences file, or a plain one on
 * a disk that will not read -- and so does the flag-clearing write `guarded`
 * makes *after* the block returns, which is why the `try` is around the call
 * and not inside the block: a guard in there has already returned by the time
 * that write runs. `GuardedWriteFailureTest` is what holds that placement.
 * Every caller is a bare `lifecycleScope.launch` with no
 * `CoroutineExceptionHandler`, so anything escaping here does not fail the
 * nudge: it kills the process, on every foreground of the home screen. The
 * nudge is the least important thing on that screen and false is it saying
 * nothing; a database or a store that cannot be read is something the user
 * hears about from settings. Same hazard, same answer, as
 * `Transfers.deleteEverything`.
 *
 * **Catching outside [CaptureStorage.guarded] narrows what `guarded` does, and
 * that is a cost rather than a detail.** A `SQLiteException` from [countTxns]
 * aborts `guarded`'s own body, so the `CaptureHealth.clearStorageUnavailable`
 * it runs after a successful block never happens here: a flag some earlier
 * failure wrote survives a call that did in fact open the database, and the
 * "CANNOT OPEN YOUR DATA" banner outranks every other. It is not stuck, and the
 * only reason it is not is `LedgerScreen`'s `LifecycleResumeEffect`:
 * `LedgerViewModel.refresh` runs the same `guarded` over the same database and
 * clears the flag when its own read succeeds, on every foreground the ledger is
 * the destination for. Nothing enforces that, and it does not cover a
 * foreground spent entirely in settings; if the ledger ever stops probing on
 * resume, this has to clear the flag itself.
 *
 * **[countTxns] and [lastExportAt] are parameters because the guard is this
 * function's whole behaviour and nothing else can provoke it.** The `transfer`
 * store is one instance for the process and caches its first successful read,
 * so a test that corrupts the file afterwards is never read from disk again and
 * proves nothing. `ExportNudgeGuardTest` hands the failures in instead.
 */
internal suspend fun readExportOverdue(
    context: Context,
    countTxns: suspend () -> Int = { Databases.txnDao(context).countAll() },
    lastExportAt: suspend () -> Long = { TransferStore.lastExportAt(context) },
    nudgeDismissedAt: suspend () -> Long = { TransferStore.nudgeDismissedAt(context) },
): Boolean = try {
    CaptureStorage.guarded(
        context,
        what = "The ledger cannot be read",
        unavailable = { false },
        // A damaged index under this count is not capture stopping, and would
        // otherwise put that banner up on every foreground.
        damageStopsCapture = false,
    ) {
        exportIsOverdue(
            txns = countTxns(),
            lastExportAt = lastExportAt(),
            now = System.currentTimeMillis(),
            nudgeDismissedAt = nudgeDismissedAt(),
        )
    }
} catch (thrown: SQLException) {
    // [SQLException], the superclass, not `SQLiteException`. A count that
    // aborts on damage raises the latter. The count runs under `guarded`'s
    // lease, so a delete or restore resetting the database waits for it --
    // but only for `Databases.RESET_LEASE_WAIT_MILLIS`, and an instance closed
    // under a count past that answers "Error code: 21, message: connection is
    // closed" as [SQLException] itself (measured on emulator-5554), which a
    // `catch (SQLiteException)` never sees.
    Log.w(TAG, "Could not count the ledger for the backup nudge", thrown)
    false
} catch (thrown: IOException) {
    Log.w(TAG, "Could not read the last export for the backup nudge", thrown)
    false
}

/** The backup nudge's second button; see [NUDGE_DISMISSED_FOR_MILLIS]. */
internal const val NUDGE_DISMISS: String = "Remind me in a week"

/**
 * The backup nudge's button, which leads to settings: what settings will
 * offer there, by design 6's table. Export everything is withdrawn on a
 * damaged ledger, whose first damaged page it throws on, and the recovery
 * notice's rescue takes its place.
 */
internal fun nudgeAction(damaged: Boolean): String = if (damaged) RESCUE else EXPORT_EVERYTHING

/** What the banner strip says, or nothing at all. One is drawn at a time. */
internal enum class BannerKind {
    STORAGE_UNAVAILABLE,
    NOT_GRANTED,
    HEALTH_UNREADABLE,
    NOTHING_SEEN,
    STOPPED,
    NUDGE_EXPORT,
}

/**
 * Which banner the strip shows, as a value so the order can be asserted without
 * a device.
 *
 * Five of the six are about capture, and spec 10.2 -- "Detecting a dead
 * listener, honestly" -- is where they come from.
 * [BannerKind.HEALTH_UNREADABLE] is that section read strictly: a report that
 * could not be taken is not a report of health, and drawing nothing would be
 * the one outcome the section exists to prevent.
 *
 * [BannerKind.NUDGE_EXPORT] is
 * not one of them and has no section of its own: the spec says nothing about
 * backup staleness, so the threshold behind it ([EXPORT_STALE_AFTER_MILLIS])
 * and the decision to draw it in this strip at all are this file's, not the
 * spec's.
 *
 * The order is the safety property, not a formatting choice, and it is the
 * reason this is a function rather than a `when` inside the composable that
 * draws it: reordered by accident, a user whose listener is dead reads "NO
 * BACKUP" and concludes capture is fine.
 */
internal fun bannerFor(report: CaptureReport, exportOverdue: Boolean): BannerKind? {
    val neverSeen = report.lastNotificationAt == 0L
    return when {
        // First, because it is the only state here where capture is certainly dead
        // rather than suspected dead -- and the only one every other signal
        // contradicts. The heartbeat is written before the first database call, so a
        // phone in this state reads as granted, recently active and not stale while
        // storing nothing.
        report.storageUnavailable -> BannerKind.STORAGE_UNAVAILABLE
        !report.granted -> BannerKind.NOT_GRANTED
        // Below `granted`, which is still measured when this is true, and above
        // everything else, all of which is not. `CaptureReport.healthUnreadable`
        // means `capture_health` would not read, and the never-seen encoding the
        // report then carries would otherwise be drawn as one of the two
        // branches below -- telling a phone that has captured for months either
        // that it is a new install or that its listener died, from a file that
        // was never read.
        report.healthUnreadable -> BannerKind.HEALTH_UNREADABLE
        // Before `looksDead`, and that is why there are three capture states here
        // rather than two. `looksDead` is true the moment the grant is given and
        // before anything has arrived, which is correct as a *report* -- nothing has
        // been seen, and no API distinguishes a silent phone from a dead listener --
        // and wrong as a *banner*, where it claims a fresh install's phone shut Pinged
        // down and lost spending, both claims about a past that does not exist yet.
        // The "stopped" copy is reserved for a report that has seen something and then
        // stopped.
        neverSeen -> BannerKind.NOTHING_SEEN
        report.looksDead -> BannerKind.STOPPED
        // Last, and nothing below it. Every kind above is a statement that capture is
        // broken now; this one is a statement about the past, and an export can be
        // taken at any later moment while spending going unrecorded cannot be
        // recovered. `BannerPriorityTest` is what holds it here.
        exportOverdue -> BannerKind.NUDGE_EXPORT
        else -> null
    }
}

/**
 * [ExportSheet] wrapped in [ReceiptSheet] -- the app's one sheet chrome, see
 * that KDoc -- plus the one thing `ExportSheet` does not do for itself: hand
 * off to the system's save-file picker.
 *
 * **No auto-fire.** The picker does not launch as this host enters
 * composition: backing out of it would leave the sheet with nothing to tap.
 * [ExportSheet]'s idle "Export" is the tap target, so opening this sheet is
 * something the user did, and reaching the system dialog is a second,
 * separate something they did.
 */
@Composable
private fun ExportSheetHost(
    state: SettingsState,
    onExport: (android.net.Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onExport(uri) }
    ReceiptSheet(onDismissRequest = onDismiss) {
        ExportSheet(
            state = state,
            onPick = { launcher.launch(EXPORT_FILE_NAME) },
            onDismiss = onDismiss,
        )
    }
}

/** `CreateDocument`'s initial suggestion; the system picker lets the user rename it. */
private const val EXPORT_FILE_NAME = "pinged-backup.json"

/** [EXPORT_FILE_NAME]'s counterpart for a salvage, so the two files are not mistaken for each other. */
@VisibleForTesting internal const val RESCUE_FILE_NAME = "pinged-rescued.json"
