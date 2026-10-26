package my.pinged.ledger.search

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.settings.CSV_TYPE
import my.pinged.ledger.settings.DocumentSink

/**
 * [SearchScreen] with its export's save-file picker: "Export these N rows"
 * (#201) writes its CSV where the user creates it, as a backup does.
 *
 * The holder is built with the entry's own `createSavedStateHandle`, so the
 * words and filters survive a process death there. [start] is the month and
 * category a way in opened it on, or null for an empty search.
 */
@Composable
fun SearchRoute(
    start: SearchStart?,
    onBack: () -> Unit,
    onOpenEntry: (EditTarget) -> Unit,
    onOpenDetail: (EditTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val search: SearchViewModel = viewModel {
        SearchViewModel(context.applicationContext as Application, createSavedStateHandle(), start)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_TYPE),
    ) { uri -> if (uri != null) search.exportTo(DocumentSink(context.contentResolver, uri)) }
    SearchScreen(
        viewModel = search,
        onBack = onBack,
        modifier = modifier,
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
        onPickExport = { exportLauncher.launch(SEARCH_CSV_FILE_NAME) },
    )
}

/** The save-file picker's initial suggestion for a search's rows; the user may rename it. */
private const val SEARCH_CSV_FILE_NAME = "pinged-search.csv"
