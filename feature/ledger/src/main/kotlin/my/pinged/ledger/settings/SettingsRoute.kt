package my.pinged.ledger.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import my.pinged.capture.CaptureAlerts
import my.pinged.ledger.AppViewModelFactory

/**
 * Spec 9.5's settings with what [SettingsScreen] hands off: the restore,
 * rescue and export pickers, and spec 10.2's `POST_NOTIFICATIONS` ask.
 *
 * The app's status stays the host's: [alertsOn] is whether the daily check
 * could post, which the host reads on each resume; [onAlertsChanged] is the
 * permission dialog having answered, for the host to read it again; and
 * [onStorageRead] is each read this screen makes of storage, for the banner
 * above it.
 *
 * [exportAsked] is Spending's backup reminder asking for the export sheet
 * (#252). The host keeps it in saved state, since this entry's holder may
 * not exist when Spending asks, and [onExportTaken] clears it as the sheet
 * opens here, so Back from the sheet lands on settings.
 */
@Composable
fun SettingsRoute(
    alertsOn: Boolean?,
    onAlertsChanged: () -> Unit,
    onStorageRead: () -> Unit,
    exportAsked: Boolean,
    onExportTaken: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenCorrections: () -> Unit,
    onOpenLearned: () -> Unit,
    onOpenUnread: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenLicences: () -> Unit,
    buildVersion: String,
    packVersion: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val settings: SettingsViewModel = viewModel(
        factory = remember { AppViewModelFactory(context.applicationContext as Application, ::SettingsViewModel) },
    )
    var exportOpen by remember { mutableStateOf(false) }
    LaunchedEffect(exportAsked) {
        if (exportAsked) {
            onExportTaken()
            exportOpen = true
        }
    }
    // The banner above says "Settings says more"; each time settings reads
    // storage, the banner reads it again, so the two cannot disagree for the
    // rest of the visit -- a full disk that has cleared, say, with nothing
    // resuming. **Keyed on the read, not on what it found**: see the host's
    // `AppStatus`.
    val read by settings.state.collectAsState()
    LaunchedEffect(read.reads) {
        if (read.reads > 0) onStorageRead()
    }
    // `OpenDocument`, not `GetContent`: `GetContent` hands back a transient
    // URI a provider may revoke the moment this callback returns, and
    // `Restore.replaceEverything` needs the stream to survive being copied to
    // a cache file first.
    //
    // `arrayOf("*/*")`, not `"application/json"` -- spec 12: MIME filtering
    // is unreliable across document providers, so the picker accepts
    // anything and `Restore` validates what comes back before it destroys
    // anything (Task 6).
    //
    // A null `Uri` -- the user backed out of the picker -- starts nothing.
    // One the provider will not open is opened by the restore, off this
    // thread, and reported as its refusal: `openInputStream` throws for it,
    // and here, on the main thread, that would kill the app.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) settings.restoreFrom { context.contentResolver.openInputStream(uri) }
    }
    // The damaged state's rescue, into a document the user creates as
    // export's is: no sheet of its own, since the recovery notice has already
    // said what it is for.
    val rescueLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) settings.salvageTo(DocumentSink(context.contentResolver, uri)) }
    // Spec 10.2's permission, asked only from its own row. Refused with no
    // rationale left to show is a denial the system will not ask about again,
    // so the app's own notification settings are the only way left.
    val askToPost = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onAlertsChanged()
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) != true) {
            openAlertSettings(activity ?: context)
        }
    }
    SettingsScreen(
        viewModel = settings,
        alertsOn = alertsOn,
        onAlerts = {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askToPost.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAlertSettings(activity ?: context)
            }
        },
        onOpenSources = onOpenSources,
        onOpenCorrections = onOpenCorrections,
        onOpenLearned = onOpenLearned,
        onOpenUnread = onOpenUnread,
        onOpenCategories = onOpenCategories,
        onOpenLicences = onOpenLicences,
        onExport = { exportOpen = true },
        // Reached only after `SettingsScreen`'s own confirmation -- see
        // `RestoreConfirmSheet` -- so the picker never opens ahead of the
        // user knowing what a restore costs.
        onRestore = { restoreLauncher.launch(arrayOf("*/*")) },
        onRescue = { rescueLauncher.launch(RESCUE_FILE_NAME) },
        buildVersion = buildVersion,
        packVersion = packVersion,
        modifier = modifier,
    )
    if (exportOpen) {
        val state = read
        ExportSheetHost(
            state = state,
            onExport = { uri -> settings.exportTo(DocumentSink(context.contentResolver, uri)) },
            onSpreadsheet = { uri -> settings.spreadsheetTo(DocumentSink(context.contentResolver, uri)) },
            onChooseSpreadsheet = { settings.chooseSpreadsheet(it) },
            onCountSpreadsheet = { settings.readSpreadsheet() },
            onCancel = settings::cancelExport,
            // Dismissing the sheet is having seen what it said about the last
            // export, so the next one opens on its button.
            onDismiss = {
                exportOpen = false
                state.outcome
                    ?.takeIf { it.operation.isExport }
                    ?.let(settings::acknowledge)
            },
        )
    }
}

/**
 * [ExportReceiptSheet], plus the one thing it does not do for itself: hand
 * off to the system's save-file picker, one per format.
 *
 * **No auto-fire.** The picker does not launch as this host enters
 * composition: backing out of it would leave the sheet with nothing to tap.
 * [ExportSheet]'s idle "Export" is the tap target, so opening this sheet is
 * something the user did, and reaching the system dialog is a second,
 * separate something they did.
 *
 * The spreadsheet card is counted once as the sheet opens, and again by
 * each choice ([onChooseSpreadsheet]).
 */
@Composable
private fun ExportSheetHost(
    state: SettingsState,
    onExport: (Uri) -> Unit,
    onSpreadsheet: (Uri) -> Unit,
    onChooseSpreadsheet: (SpreadsheetChoice) -> Unit,
    onCountSpreadsheet: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) onExport(uri) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_TYPE),
    ) { uri -> if (uri != null) onSpreadsheet(uri) }
    LaunchedEffect(Unit) { onCountSpreadsheet() }
    ExportReceiptSheet(
        state = state,
        onPick = { launcher.launch(EXPORT_FILE_NAME) },
        onCancel = onCancel,
        onDismiss = onDismiss,
        spreadsheet = SpreadsheetPanel(
            choice = state.spreadsheet,
            counts = state.spreadsheetCounts,
            onChoose = onChooseSpreadsheet,
            onPick = { csvLauncher.launch(SPREADSHEET_FILE_NAME) },
        ),
    )
}

/**
 * This app's notification settings, where alerts are turned off again, or
 * on once the permission prompt will no longer be shown. [context] is the
 * Activity wherever there is one; without one, `startActivity` refuses for
 * want of a new task and the refusal is logged like any other.
 *
 * Wrapped against `Throwable`, for the reason the host's
 * `openListenerSettings` gives: an OEM settings activity that is not
 * exported makes `startActivity` throw `SecurityException`.
 */
private fun openAlertSettings(context: Context) {
    CaptureAlerts.ensureChannel(context.applicationContext)
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
        .onFailure { Log.w(TAG, "This app's notification settings would not open", it) }
}

private const val TAG = "PingedSettings"

/** `CreateDocument`'s initial suggestion; the system picker lets the user rename it. */
private const val EXPORT_FILE_NAME = "pinged-backup.json"

/** [EXPORT_FILE_NAME]'s counterpart for the export sheet's spreadsheet. */
private const val SPREADSHEET_FILE_NAME = "pinged-transactions.csv"

/** RFC 4180's media type, which `CreateDocument` gives the provider. Search's export uses it too. */
internal const val CSV_TYPE = "text/csv"

/** [EXPORT_FILE_NAME]'s counterpart for a salvage, so the two files are not mistaken for each other. */
@VisibleForTesting const val RESCUE_FILE_NAME = "pinged-rescued.json"
