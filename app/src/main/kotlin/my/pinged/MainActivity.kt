package my.pinged

import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.defaultPredictivePopTransitionSpec
import androidx.navigationevent.NavigationEvent
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import my.pinged.capture.CaptureAlerts
import my.pinged.charts.ChartsScreen
import my.pinged.charts.ChartsViewModel
import my.pinged.ledger.day.DayScreen
import my.pinged.ledger.day.DayViewModel
import my.pinged.ledger.detail.DetailScreen
import my.pinged.ledger.detail.DetailViewModel
import my.pinged.ledger.search.SearchRoute
import my.pinged.ledger.search.SearchStart
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.entry.ManualEntryScreen
import my.pinged.ledger.entry.ManualEntryViewModel
import my.pinged.capture.SourcePrompt
import my.pinged.capture.Graph
import my.pinged.tracker.BuildConfig
import my.pinged.data.Databases
import my.pinged.ledger.AppViewModelFactory
import my.pinged.ledger.home.BackupReminder
import my.pinged.ledger.home.LedgerScreen
import my.pinged.ledger.home.LedgerViewModel
import my.pinged.ledger.onboarding.OnboardingScreen
import my.pinged.ledger.corrections.CorrectionsScreen
import my.pinged.ledger.corrections.CorrectionsViewModel
import my.pinged.ledger.categories.CategoriesScreen
import my.pinged.ledger.categories.CategoriesViewModel
import my.pinged.ledger.learned.LearnedScreen
import my.pinged.ledger.learned.LearnedViewModel
import my.pinged.ledger.teach.TeachScreen
import my.pinged.ledger.teach.TeachViewModel
import my.pinged.ledger.unread.UnreadScreen
import my.pinged.ledger.unread.UnreadViewModel
import my.pinged.ledger.licences.LicencesScreen
import my.pinged.ledger.review.ReviewScreen
import my.pinged.ledger.review.ReviewViewModel
import my.pinged.ledger.settings.SettingsRoute
import my.pinged.ledger.sources.CaptureBanner
import my.pinged.ledger.sources.SourcesRoute
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.HeldBy
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.PingedTheme

/**
 * The only Activity. It hosts spec 9.1's ledger, which is home; spec 9.5's
 * settings; and spec 9.6's allow-list, reachable from settings. One banner
 * strip sits above all three, for spec 10.2's capture states; the backup
 * reminder is Spending's alone, and only where the strip is empty
 * ([backupReminderShows]). The bottom bar sits under the
 * two tab roots and not under a pushed screen. On first run, until the
 * notification-access grant, onboarding is drawn in place of all of it.
 */
class MainActivity : ComponentActivity() {

    /**
     * The banner, the heard line, the backup reminder, the tab bar's count
     * and onboarding, decided outside this Activity, which renders them and
     * hands in what only it sees. Held by a ViewModel, so a rotation keeps it.
     */
    private val status: AppStatus by lazy {
        ViewModelProvider(this, AppViewModelFactory(application, ::AppStatusViewModel))[AppStatusViewModel::class.java].status
    }

    /**
     * Whether spec 10.2's daily check could post, for its settings row. Read
     * on each resume: the user may have changed it in system settings.
     */
    private val alertsAllowed = MutableStateFlow<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Built once, here, rather than per entry: the factory holds the
        // `Application` itself. See `AppViewModelFactory`. Settings' and
        // Sources' are their routes' own.
        val correctionsFactory = AppViewModelFactory(application, ::CorrectionsViewModel)
        val learnedFactory = AppViewModelFactory(application, ::LearnedViewModel)
        val unreadFactory = AppViewModelFactory(application, ::UnreadViewModel)
        val categoriesFactory = AppViewModelFactory(application, ::CategoriesViewModel)
        val reviewFactory = AppViewModelFactory(application, ::ReviewViewModel)
        // Only a first creation's stack: a recreate or a process death hands
        // back the same intent, and `rememberNavBackStack` then restores the
        // saved stack and ignores this, so the entry is not routed twice.
        val spendingStart = spendingStackFor(addPayment = AddPaymentShortcut.opens(intent))
        val openSources = SourcePrompt.opensSources(intent)
        val settingsStart = settingsStackFor(openSources)
        setContent {
            // Collected while started, which is when its watchers run.
            val current by status.state.collectAsStateWithLifecycle()
            WithHolder {
                // One saved stack per tab and the tab showing, kept in saved
                // state for the reason `Ledger` gives. `TabStacks` holds the
                // rules; see it for why `NavDisplay` is handed every tab's
                // entries and not the shown tab's.
                val selection = rememberSaveable {
                    mutableStateOf(if (openSources) Tab.Settings else Tab.Spending)
                }
                val spendingStack = rememberNavBackStack(*spendingStart)
                val chartsStack = rememberNavBackStack(Tab.Charts.root)
                val settingsStack = rememberNavBackStack(*settingsStart)
                val tabs = remember(selection, spendingStack, chartsStack, settingsStack) {
                    TabStacks(
                        mapOf(
                            Tab.Spending to spendingStack,
                            Tab.Charts to chartsStack,
                            Tab.Settings to settingsStack,
                        ),
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
                    // First run (#156) is the whole window: no banner, no tab
                    // bar, nothing behind it for a screen reader to find.
                    // Null until the first sample, drawn as bare Paper: it is
                    // null only on an install without the grant, and there
                    // the ledger would flash before onboarding replaced it.
                    val opening = current.onboarding
                    // Saved, so a process killed while the user is in
                    // Android's settings still takes them on to sources.
                    var granting by rememberSaveable { mutableStateOf(false) }
                    // The backup reminder's Export (#252): set on Spending,
                    // cleared by the Settings entry as it opens the export
                    // sheet, which stays hosted there so Back from it lands
                    // on Settings. Saved, so a process death between the two
                    // still opens it.
                    var exportAsked by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(opening, granting) {
                        // The next step: sources, on the tab first run leaves
                        // the user on, so back from it lands on Spending --
                        // or on the entry screen, when the launcher's Add
                        // payment shortcut is what opened first run.
                        if (opening == false && granting) {
                            granting = false
                            tabs.push(Sources)
                        }
                    }
                    if (opening == true) {
                        OnboardingScreen(
                            onAllowAccess = {
                                granting = true
                                openListenerSettings()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (opening == false) {
                        // Above the NavDisplay, not inside a screen: capture being
                        // dead matters whichever destination is showing, and one
                        // holder computing it is one place to get it wrong.
                        // The tab stacks live in this composition and not in the
                        // Activity, so the banners with somewhere to go are
                        // handed the destination rather than reaching for it.
                        // Its button leads to settings, from its root: `TabStacks`
                        // selects the tab and unwinds whatever is pushed over it.
                        // On settings' own root it would do nothing, so there is no
                        // button (ruling R35).
                        GrantBanner(current.banner, onOpenSettings = tabs::showSettings.takeUnless { tabs.showingSettingsRoot })
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
                                    // The entry's own `createSavedStateHandle`, as
                                    // Charts': the picked month (#192) survives a
                                    // process death there.
                                    val ledger: LedgerViewModel = viewModel {
                                        LedgerViewModel(application, createSavedStateHandle())
                                    }
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
                                        // The pinned row passes null, a row's
                                        // badge its own id.
                                        onOpenReview = { focus -> tabs.push(Review(focus)) },
                                        onAddPayment = { tabs.push(ManualEntry()) },
                                        onOpenEntry = { target -> tabs.push(ManualEntry(target.id, target.createdAt)) },
                                        onOpenDetail = { target -> tabs.push(Detail(target.id, target.createdAt)) },
                                        onOpenSearch = { tabs.push(Search()) },
                                        onOpenToSort = { month, id -> tabs.push(Search(month.toString(), id)) },
                                        heard = current.heard,
                                        // Both lead to settings' root, where the
                                        // export sheet is hosted and the
                                        // recovery notice is drawn.
                                        backupReminder = current.backupReminder?.let { action ->
                                            BackupReminder(
                                                damaged = action == BackupReminderAction.Rescue,
                                                onAction = when (action) {
                                                    BackupReminderAction.Rescue -> tabs::showSettings
                                                    BackupReminderAction.Export -> {
                                                        {
                                                            exportAsked = true
                                                            tabs.showSettings()
                                                        }
                                                    }
                                                },
                                                onDismiss = status::dismissBackupReminder,
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                entry<Search> { key ->
                                    SearchRoute(
                                        start = key.month?.let { m ->
                                            key.categoryId?.let { SearchStart(YearMonth.parse(m), it) }
                                        },
                                        // The same expression NavDisplay's onBack
                                        // is given; see `pop`.
                                        onBack = tabs::pop,
                                        onOpenEntry = { target -> tabs.push(ManualEntry(target.id, target.createdAt)) },
                                        onOpenDetail = { target -> tabs.push(Detail(target.id, target.createdAt)) },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                entry<ManualEntry> { key ->
                                    // The entry's own `createSavedStateHandle`, as
                                    // Charts': the draft survives a process death
                                    // there (#108). A new entry starts from an
                                    // empty draft, a correction (#105) from the
                                    // row `txnId` and `createdAt` name.
                                    val target = key.txnId?.let { id -> key.createdAt?.let { EditTarget(id, it) } }
                                    val entry: ManualEntryViewModel = viewModel {
                                        ManualEntryViewModel(application, createSavedStateHandle(), target)
                                    }
                                    ManualEntryScreen(
                                        viewModel = entry,
                                        // The same expression NavDisplay's onBack
                                        // is given; see `pop`.
                                        onBack = tabs::pop,
                                        onSaved = tabs::pop,
                                        modifier = Modifier.fillMaxSize(),
                                        // Back to the list the deleted row was on (#106).
                                        onDeleted = tabs::pop,
                                    )
                                }
                                entry<Review> { key ->
                                    val review: ReviewViewModel = viewModel(factory = reviewFactory)
                                    ReviewScreen(
                                        viewModel = review,
                                        onBack = tabs::pop,
                                        modifier = Modifier.fillMaxSize(),
                                        focus = key.focus,
                                    )
                                }
                                entry<Charts> {
                                    // `createSavedStateHandle` is the entry's own:
                                    // the saveable-state decorator above gives each
                                    // entry a registry, which is where the selected
                                    // month survives a process death.
                                    val charts: ChartsViewModel = viewModel {
                                        ChartsViewModel(application, createSavedStateHandle())
                                    }
                                    ChartsScreen(
                                        viewModel = charts,
                                        modifier = Modifier.fillMaxSize(),
                                        onDayClick = { date -> tabs.push(Day(date.toString())) },
                                        onOpenReview = { tabs.push(Review()) },
                                        // Onto Charts' stack, as Day is: Back
                                        // returns to this month.
                                        onOpenUncategorized = { month, id -> tabs.push(Search(month.toString(), id)) },
                                    )
                                }
                                entry<Day> { key ->
                                    // Built from the key alone, so a holder rebuilt
                                    // after a process death reads the day afresh.
                                    val day: DayViewModel = viewModel {
                                        DayViewModel(application, LocalDate.parse(key.date))
                                    }
                                    DayScreen(
                                        viewModel = day,
                                        // The same expression NavDisplay's onBack
                                        // is given; see `pop`.
                                        onBack = tabs::pop,
                                        modifier = Modifier.fillMaxSize(),
                                        onOpenReview = { focus -> tabs.push(Review(focus)) },
                                        // Onto the current stack, Charts', as
                                        // Review is: back returns to this day.
                                        onOpenEntry = { target -> tabs.push(ManualEntry(target.id, target.createdAt)) },
                                        onOpenDetail = { target -> tabs.push(Detail(target.id, target.createdAt)) },
                                        // In place of this day, so Back from
                                        // any day stepped to is Charts.
                                        onStep = { date -> tabs.replaceTop(Day(date.toString())) },
                                    )
                                }
                                entry<Detail> { key ->
                                    // Built from the key alone, as Day's holder is.
                                    val detail: DetailViewModel = viewModel {
                                        DetailViewModel(application, key.txnId, key.createdAt)
                                    }
                                    DetailScreen(
                                        viewModel = detail,
                                        // The same expression NavDisplay's onBack
                                        // is given; see `pop`. Delete this entry
                                        // leaves by it too.
                                        onBack = tabs::pop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                entry<Settings> {
                                    SettingsRoute(
                                        alertsOn = alertsAllowed.collectAsState().value,
                                        onAlertsChanged = { alertsAllowed.value = CaptureAlerts.canPost(applicationContext) },
                                        onStorageRead = status::settingsRead,
                                        exportAsked = exportAsked,
                                        onExportTaken = { exportAsked = false },
                                        onOpenSources = { tabs.push(Sources) },
                                        onOpenCorrections = { tabs.push(Corrections) },
                                        onOpenLearned = { tabs.push(Learned) },
                                        onOpenUnread = { tabs.push(Unread) },
                                        onOpenCategories = { tabs.push(Categories) },
                                        onOpenLicences = { tabs.push(Licences) },
                                        buildVersion = BuildConfig.VERSION_NAME,
                                        packVersion = remember { Graph.parsePack().packVersion },
                                        modifier = Modifier.fillMaxSize(),
                                    )
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
                                entry<Unread> {
                                    val unread: UnreadViewModel = viewModel(factory = unreadFactory)
                                    UnreadScreen(
                                        viewModel = unread,
                                        onBack = tabs::pop,
                                        onTeach = { tabs.push(Teach(it.id)) },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                entry<Teach> { key ->
                                    // Built from the key alone, as Detail's holder is.
                                    val teach: TeachViewModel = viewModel { TeachViewModel(application, key.captureId) }
                                    TeachScreen(
                                        viewModel = teach,
                                        // Saving leaves by it too, back to the list.
                                        onBack = tabs::pop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                entry<Categories> {
                                    val categories: CategoriesViewModel = viewModel(factory = categoriesFactory)
                                    CategoriesScreen(
                                        viewModel = categories,
                                        onBack = tabs::pop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                entry<Licences> {
                                    LicencesScreen(onBack = tabs::pop, modifier = Modifier.fillMaxSize())
                                }
                                entry<Sources> {
                                    // The same expression NavDisplay's onBack is
                                    // given; see `pop`.
                                    SourcesRoute(onBack = tabs::pop, modifier = Modifier.fillMaxSize())
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // A foreground and a ledger rewrite read it again too;
                        // see `AppStatus.barMayShow`.
                        LaunchedEffect(tabs.selected, tabs.showsBar) { status.barMayShow() }
                        if (tabs.showsBar) {
                            // Null, the dash, while it is not read yet or
                            // unreadable: 0 would say nothing awaits review (#237).
                            val awaitingReview = current.awaitingReview.takeUnless { it.unavailable }?.answer
                            TabBar(selected = tabs.selected, onSelect = tabs::select, awaitingReview = awaitingReview)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        alertsAllowed.value = CaptureAlerts.canPost(applicationContext)
        // Spec 10.1's third rebind path is installed process-wide by
        // ForegroundRebinder; this is the screen catching up with whatever the
        // user did in Settings while it was away.
        status.resumed()
    }

    /**
     * Copy for whichever kind [bannerFor] chose, and nothing else.
     *
     * The choosing is in [bannerFor] because it is the part with a safety
     * property; this half is wording, colour and a tap target, and needs a
     * device to see at all.
     *
     * [onOpenSettings] is a parameter rather than a method on this class
     * because it navigates, and the back stack belongs to the composition in
     * `onCreate`. Null draws the banner without its button.
     */
    @Composable
    private fun GrantBanner(kind: BannerKind?, onOpenSettings: (() -> Unit)?) {
        when (kind ?: return) {
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

/** One tag for this file, `AppStatus.kt` and `FirstRun.kt`: what they log is `MainActivity`'s own. */
internal const val TAG = "PingedMain"

/**
 * [PingedTheme] with [LocalHeldBy] provided from `Databases.holder` (#279),
 * so every screen's status line names what its read is waiting behind
 * without each screen collecting it.
 */
@Composable
private fun WithHolder(content: @Composable () -> Unit) {
    val holder by Databases.holder.collectAsState()
    CompositionLocalProvider(LocalHeldBy provides heldBy(holder)) { PingedTheme(content) }
}

/**
 * `core:data`'s holder as `core:ui` names it: `:core:ui` may not depend on
 * `:core:data` (#81), so the two enums meet here.
 */
internal fun heldBy(holder: Databases.Holder?): HeldBy? = when (holder) {
    null -> null
    Databases.Holder.Export -> HeldBy.Export
    Databases.Holder.IntegrityCheck -> HeldBy.IntegrityCheck
}

