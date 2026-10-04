package my.pinged.ledger.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Separator

/**
 * `design/Export.dc.html`, minus the half that is CSV.
 *
 * **The artboard's range picker and exclusion toggles are absent because they
 * are wrong on this card, not only because CSV is deferred.** A JSON export
 * filtered to one month rebuilds nothing, and `ImportJson.verifyReferences`
 * refuses it outright -- the transactions it kept name captures the filter
 * removed. They return with CSV, on its side.
 *
 * The idle state's "Export" is a tap target, not a caption: [onPick] runs
 * only when the user asks for it, so backing out of the system document
 * picker leaves this sheet with something to tap again, not nothing at all.
 *
 * **Assumes it is already inside a vertical container with its own
 * padding** -- `MainActivity`'s `ExportSheetHost` supplies that by calling
 * this from inside `ReceiptSheet` (the app's one sheet chrome; see that
 * KDoc), the same way `SettingsScreen`'s `RestoreConfirmSheet` and
 * `OutcomeSheet` do. A `Column` with padding of its own here would double
 * `ReceiptSheet`'s.
 */
@Composable
fun ExportSheet(state: SettingsState, onPick: () -> Unit, onDismiss: () -> Unit) {
    Text("Everything", fontFamily = Body, fontSize = 20.sp, color = Ink)
    Text(
        "Transactions, the original notifications, your learned merchants and " +
            "categories. Enough to rebuild Pinged from scratch.",
        fontFamily = Body,
        fontSize = 14.sp,
        color = Muted,
        modifier = Modifier.padding(top = 8.dp),
    )
    // The export's own job only: an outcome `Transfers` keeps for another
    // operation is not this sheet's to report, and would hide its button.
    when (val job = state.jobOf(Operation.EXPORT)) {
        is TransferJob.Running ->
            Text("${job.operation.doing}${Separator}${grouped(job.rows)} ROWS", style = MonoLabel, color = Muted)
        is TransferJob.Exported -> {
            Text("SAVED${Separator}${grouped(job.rows)} ROWS", style = MonoLabel, color = Muted)
            job.unrecorded?.let { reason ->
                Text(unrecordedExport(reason), fontFamily = Body, fontSize = 14.sp, color = Ink)
            }
        }
        is TransferJob.Failed ->
            Text(job.message, fontFamily = Body, fontSize = 14.sp, color = Ink)
        else -> Text(
            "Export",
            style = MonoLabel,
            color = Ink,
            modifier = Modifier
                .padding(top = 16.dp)
                .clickable(role = Role.Button, onClick = onPick),
        )
    }
    Text(
        "YOU CHOOSE WHERE IT GOES" + Separator + "PINGED CANNOT UPLOAD IT ANYWHERE",
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.padding(top = 24.dp),
    )
}

/**
 * What [TransferJob.Exported.unrecorded] says to the user: the file is whole,
 * and the one thing that did not happen is the backup nudge hearing of it.
 */
internal fun unrecordedExport(reason: String) =
    "The file is complete and is a full backup. Pinged could not note when it was saved " +
        "($reason), so it may go on reminding you to back up."
