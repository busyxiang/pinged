package my.pinged.ledger.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp

/** `design/Export.dc.html`'s title, over the sheet's close X. */
const val EXPORT_TITLE = "Export"

/**
 * [ExportSheet] in [ReceiptSheet] -- the app's one sheet chrome, see that
 * KDoc -- under the artboard's title and close X. Here rather than in
 * `MainActivity`'s host, so a test in this module can draw what ships.
 */
@Composable
fun ExportReceiptSheet(state: SettingsState, onPick: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    ReceiptSheet(onDismissRequest = onDismiss, title = EXPORT_TITLE) {
        ExportSheet(state = state, onPick = onPick, onCancel = onCancel, onDismiss = onDismiss)
    }
}

/**
 * `design/Export.dc.html`, minus the half that is CSV.
 *
 * **The artboard's range picker and exclusion toggles are absent because they
 * are wrong on this card, not only because CSV is deferred.** A JSON export
 * filtered to one month rebuilds nothing, and `ImportJson.verifyReferences`
 * refuses it outright -- the transactions it kept name captures the filter
 * removed. They return with CSV, on its side.
 *
 * The idle state's "Export" is a button, not a caption: [onPick] runs
 * only when the user asks for it, so backing out of the system document
 * picker leaves this sheet with something to tap again, not nothing at all.
 * Above it, the count of what will be written; nothing where the screen has
 * no count it can stand behind ([SettingsState.exportRows]).
 *
 * Running offers Cancel ([onCancel]), which deletes the partial document;
 * dismissing the sheet only hides it. Done names the document as its
 * provider does, which is the name the user typed.
 *
 * **Assumes it is already inside a vertical container with its own
 * padding** -- `MainActivity`'s `ExportSheetHost` supplies that by calling
 * this from inside `ReceiptSheet` (the app's one sheet chrome; see that
 * KDoc), the same way `SettingsScreen`'s `RestoreConfirmSheet` and
 * `OutcomeSheet` do. A `Column` with padding of its own here would double
 * `ReceiptSheet`'s.
 */
@Composable
fun ExportSheet(state: SettingsState, onPick: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    // The board's chosen card, since it is the only one: 1.5dp of Stamp with
    // its tag in Stamp. 4dp on `SheetHeader`'s 14 is the board's 18.
    Column(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .border(1.5.dp, Stamp, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row {
            Text(
                "Everything",
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.5.sp,
                color = Ink,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            Text("JSON", style = MonoLabel, color = Stamp, modifier = Modifier.alignByBaseline())
        }
        Text(
            "Transactions, the original notifications, your learned merchants and " +
                "categories. Enough to rebuild Pinged from scratch.",
            fontFamily = Body,
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
    // The export's own job only: an outcome `Transfers` keeps for another
    // operation is not this sheet's to report, and would hide its button.
    when (val job = state.jobOf(Operation.EXPORT)) {
        is TransferJob.Running -> {
            Text("${job.operation.doing}${Separator}${grouped(job.rows)} ROWS", style = MonoLabel, color = Muted)
            TapTarget("Cancel", onCancel)
        }
        is TransferJob.Exported -> {
            Text("SAVED${Separator}${grouped(job.rows)} ROWS", style = MonoLabel, color = Muted)
            job.savedAs?.let { name ->
                Text("Saved as $name", fontFamily = Body, fontSize = 14.sp, color = Ink)
            }
            job.unrecorded?.let { reason ->
                Text(unrecordedExport(reason), fontFamily = Body, fontSize = 14.sp, color = Ink)
            }
        }
        is TransferJob.Failed ->
            Text(job.message, fontFamily = Body, fontSize = 14.sp, color = Ink)
        else -> {
            state.exportRows?.let { rows ->
                Text("${grouped(rows)} ROWS TO WRITE", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 16.dp))
            }
            PrimaryButton("Export", onPick)
        }
    }
    Text(
        "YOU CHOOSE WHERE IT GOES" + Separator + "PINGED CANNOT UPLOAD IT ANYWHERE",
        style = MonoLabel,
        color = Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
    )
}

/** The board's action: 52dp, margin to margin, filled in Stamp with its label in Paper. */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Stamp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel, color = Paper)
    }
}

/** The running sheet's Cancel, which the board does not draw. */
@Composable
private fun TapTarget(label: String, onClick: () -> Unit) = Text(
    label,
    style = ButtonLabel,
    color = Ink,
    modifier = Modifier
        .padding(top = 16.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 44.dp)
        .wrapContentHeight(),
)

/**
 * What [TransferJob.Exported.unrecorded] says to the user: the file is whole,
 * and the one thing that did not happen is the backup nudge hearing of it.
 */
internal fun unrecordedExport(reason: String) =
    "The file is complete and is a full backup. Pinged could not note when it was saved " +
        "($reason), so it may go on reminding you to back up."
