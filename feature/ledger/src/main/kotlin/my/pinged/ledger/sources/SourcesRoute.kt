package my.pinged.ledger.sources

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import my.pinged.ledger.AppViewModelFactory

/** Spec 9.6's allow-list, with [AskForNotifications] as it opens. */
@Composable
fun SourcesRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sources: SourcesViewModel = viewModel(
        factory = remember { AppViewModelFactory(context.applicationContext as Application, ::SourcesViewModel) },
    )
    AskForNotifications()
    SourcesScreen(viewModel = sources, onBack = onBack, modifier = modifier)
}

/**
 * Spec 9.6's just-in-time prompt is a notification, so from API 33 it needs
 * `POST_NOTIFICATIONS`. Asked here, on the list of sources, where what it is
 * for is on screen, and once per visit: saved, so a rotation does not ask
 * again. After two refusals the system answers for the user without a
 * dialog, which is the only limit on asking -- and what keeps this from
 * nagging.
 */
@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
