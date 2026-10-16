package my.pinged.ledger.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.time.format.TextStyle
import my.pinged.data.LocalDates
import my.pinged.data.SearchFilter
import my.pinged.ui.CALENDAR
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.Toggle
import my.pinged.ui.theme.dottedRule

/** `design/Export.dc.html`'s title, over the sheet's close X. */
const val EXPORT_TITLE = "Export"

/** The spreadsheet card's name. */
const val SPREADSHEET = "Spreadsheet"

/** The backup card's name. */
const val EVERYTHING = "Everything"

/** The spreadsheet card's format tag. */
const val CSV_TAG = "CSV"

/** The backup card's format tag. */
const val JSON_TAG = "JSON"

/**
 * The spreadsheet card's choices (#201): `design/Export.dc.html`'s HOW MUCH
 * and WHAT TO LEAVE OUT. [month] null is "Everything so far". Read as a
 * [SearchFilter] with no words, the same query search's rows come from.
 */
data class SpreadsheetChoice(
    val month: YearMonth? = YearMonth.now(),
    val leaveOutExcluded: Boolean = false,
    val leaveOutPending: Boolean = false,
) {
    fun filter(): SearchFilter = SearchFilter(
        from = month?.let { LocalDates.of(it.atDay(1)) },
        to = month?.let { LocalDates.of(it.atEndOfMonth()) },
        leaveOutExcluded = leaveOutExcluded,
        leaveOutPending = leaveOutPending,
    )
}

/**
 * What the spreadsheet card counts for [choice], all three from
 * `TxnDao.searchTotals` over [SpreadsheetChoice.filter], so the button's
 * count and the file's rows are one predicate. [rows] is what the export
 * writes; [excluded] and [pending] are the month's (or all time's) rows that
 * each leave-out would drop, whatever the toggles say.
 *
 * One predicate, not one moment: the count is read when the sheet opens or a
 * choice changes, and the file in its own transaction after the picker, so a
 * payment captured in between is in the file and not in the number.
 */
data class SpreadsheetCounts(val choice: SpreadsheetChoice, val rows: Int, val excluded: Int, val pending: Int)

/**
 * The spreadsheet card's half of [ExportSheet]: what is chosen, what was
 * counted for it (null while unread, or for an earlier choice), and the two
 * ways to act on it.
 */
class SpreadsheetPanel(
    val choice: SpreadsheetChoice,
    val counts: SpreadsheetCounts?,
    val onChoose: (SpreadsheetChoice) -> Unit,
    val onPick: () -> Unit,
)

/**
 * [ExportSheet] in [ReceiptSheet] -- the app's one sheet chrome, see that
 * KDoc -- under the artboard's title and close X. Here rather than in
 * `MainActivity`'s host, so a test in this module can draw what ships.
 */
@Composable
fun ExportReceiptSheet(
    state: SettingsState,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    spreadsheet: SpreadsheetPanel? = null,
) {
    ReceiptSheet(onDismissRequest = onDismiss, title = EXPORT_TITLE) {
        ExportSheet(state = state, onPick = onPick, onCancel = onCancel, onDismiss = onDismiss, spreadsheet = spreadsheet)
    }
}

/**
 * `design/Export.dc.html`: the spreadsheet card and the backup card, and
 * under the chosen one what it will write.
 *
 * **Opens on the backup card, not the spreadsheet the board draws chosen.**
 * The backup nudge sends a user here by way of Settings to make the one file
 * that rebuilds Pinged; a sheet that opened on CSV would hand them a
 * spreadsheet. Search's export opens on CSV instead ([SearchExportSheet]).
 * An export of either kind running, or one settled and not yet dismissed,
 * opens the sheet on its own card, so its progress or result is what is seen.
 *
 * **HOW MUCH and WHAT TO LEAVE OUT are the spreadsheet's only.** A JSON
 * export filtered to one month rebuilds nothing, and
 * `ImportJson.verifyReferences` refuses it outright -- the transactions it
 * kept name captures the filter removed. [spreadsheet] null draws the backup
 * card alone, for a host that offers nothing else.
 *
 * The idle state's "Export" is a button, not a caption: the pick callbacks
 * run only when the user asks for it, so backing out of the system document
 * picker leaves this sheet with something to tap again, not nothing at all.
 * Above the backup's button, the count of what will be written; nothing where
 * the screen has no count it can stand behind ([SettingsState.exportRows]).
 * The spreadsheet's count is on its button, and only once it was counted for
 * what is chosen now.
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
fun ExportSheet(
    state: SettingsState,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    spreadsheet: SpreadsheetPanel? = null,
) {
    val opensOn = state.running?.operation ?: state.outcome?.operation
    var format by rememberSaveable {
        mutableStateOf(if (spreadsheet != null && opensOn == Operation.SPREADSHEET) Operation.SPREADSHEET else Operation.EXPORT)
    }
    // An export of either kind still running is drawn whichever card is
    // chosen: the other card's button would only be refused.
    val running = state.running?.takeIf { it.operation.isExport }
    val job = running ?: state.jobOf(format)
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (spreadsheet != null) {
            FormatCard(
                SPREADSHEET,
                CSV_TAG,
                "One row per transaction. Opens in Excel, Numbers or Sheets.",
                chosen = format == Operation.SPREADSHEET,
                onChoose = { format = Operation.SPREADSHEET },
            )
        }
        FormatCard(
            EVERYTHING,
            JSON_TAG,
            "Transactions, the original notifications, your learned merchants and " +
                "categories. Enough to rebuild Pinged from scratch.",
            chosen = format == Operation.EXPORT,
            onChoose = if (spreadsheet != null) ({ format = Operation.EXPORT }) else null,
        )
    }
    if (format == Operation.SPREADSHEET && spreadsheet != null && job is TransferJob.Idle) {
        SpreadsheetChoices(spreadsheet)
    }
    if (isExportState(job)) {
        ExportJobState(job, onCancel)
    } else {
        if (format == Operation.SPREADSHEET && spreadsheet != null) {
            val counted = spreadsheet.counts?.takeIf { it.choice == spreadsheet.choice }
            PrimaryButton(exportRowsLabel(counted?.rows), spreadsheet.onPick)
        } else {
            state.exportRows?.let { rows ->
                Text("${grouped(rows)} ROWS TO WRITE", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 16.dp))
            }
            PrimaryButton("Export", onPick)
        }
    }
    Footnote()
}

/**
 * Search's "Export these N rows" (#201): the spreadsheet card alone, chosen,
 * and already narrowed to the search -- its words and its four filters, which
 * stand where the export sheet's HOW MUCH and WHAT TO LEAVE OUT do. There is
 * no backup card: a JSON export cannot be filtered.
 *
 * [rows] is the search's own count of what it found, from the totals the
 * search screen draws, so it is the number the user tapped; null draws the
 * button without one.
 */
@Composable
fun SearchExportSheet(rows: Int?, job: TransferJob, onPick: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    ReceiptSheet(onDismissRequest = onDismiss, title = EXPORT_TITLE) {
        FormatCard(
            SPREADSHEET,
            CSV_TAG,
            "The rows this search found, one per line. Opens in Excel, Numbers or Sheets.",
            chosen = true,
            onChoose = null,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (isExportState(job)) ExportJobState(job, onCancel) else PrimaryButton(exportRowsLabel(rows), onPick)
        Footnote()
    }
}

/**
 * One of the board's two cards: the chosen one 1.5dp of [Stamp] with its tag
 * in [Stamp], the other 1dp of [Border] with its tag in [Muted]. [onChoose]
 * null is a card that is the only choice, and takes no tap.
 */
@Composable
private fun FormatCard(
    name: String,
    tag: String,
    blurb: String,
    chosen: Boolean,
    onChoose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .border(if (chosen) 1.5.dp else 1.dp, if (chosen) Stamp else Border, RoundedCornerShape(2.dp))
            .then(
                if (onChoose == null) Modifier else Modifier.selectable(selected = chosen, role = Role.RadioButton, onClick = onChoose),
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row {
            Text(
                name,
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.5.sp,
                color = Ink,
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            Text(tag, style = MonoLabel, color = if (chosen) Stamp else Muted, modifier = Modifier.alignByBaseline())
        }
        Text(blurb, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(top = 5.dp))
    }
}

/** The board's HOW MUCH chips and WHAT TO LEAVE OUT toggles. */
@Composable
private fun SpreadsheetChoices(panel: SpreadsheetPanel) {
    val choice = panel.choice
    // Counted for an earlier choice is still the month's: the two leave-out
    // counts do not depend on the toggles, only on the range.
    val counts = panel.counts?.takeIf { it.choice.month == choice.month }
    SectionLabel("HOW MUCH")
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        // The chosen month's name, so a sheet left open past a month's end
        // still says which month it will write; tapping it moves to this one.
        val month = choice.month ?: YearMonth.now()
        RangeChip(month.month.getDisplayName(TextStyle.FULL, CALENDAR), choice.month != null) {
            panel.onChoose(choice.copy(month = YearMonth.now()))
        }
        RangeChip(EVERYTHING_SO_FAR, choice.month == null) { panel.onChoose(choice.copy(month = null)) }
    }
    if (counts == null || (counts.excluded == 0 && counts.pending == 0)) return
    SectionLabel("WHAT TO LEAVE OUT")
    if (counts.excluded > 0) {
        LeaveOut(
            LEFT_OUT_OF_TOTALS,
            "${rowsWord(counts.excluded)}${Separator}TRANSFERS, RELOADS AND NOT SPENDING",
            choice.leaveOutExcluded,
            ruled = counts.pending > 0,
        ) { panel.onChoose(choice.copy(leaveOutExcluded = it)) }
    }
    if (counts.pending > 0) {
        LeaveOut(
            awaitingReview(counts.pending),
            "NOT COUNTED YET, SO NOT USUALLY WANTED",
            choice.leaveOutPending,
            ruled = false,
        ) { panel.onChoose(choice.copy(leaveOutPending = it)) }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 20.dp))

/** A HOW MUCH chip: [Stamp] filled with [Paper] text when chosen, a [Border] outline when not. */
@Composable
private fun RangeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 34.dp)
                .then(
                    if (on) {
                        Modifier.background(Stamp, RoundedCornerShape(2.dp))
                    } else {
                        Modifier.border(1.dp, Border, RoundedCornerShape(2.dp))
                    },
                )
                .padding(horizontal = 11.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = Body,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (on) Paper else Muted,
            )
        }
    }
}

/** A WHAT TO LEAVE OUT row: the whole row toggles, as the chooser's "Always" does. */
@Composable
private fun LeaveOut(title: String, note: String, on: Boolean, ruled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (ruled) Modifier.dottedRule(atTop = false) else Modifier)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .heightIn(min = 44.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Body, fontSize = 14.5.sp, color = Ink)
            Text(note, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        // Drawn only: the row owns the click and the semantics.
        Toggle(on = on)
    }
}

/** Whether [ExportJobState] draws [job]; for idle the caller draws its button. */
private fun isExportState(job: TransferJob) =
    job is TransferJob.Running || job is TransferJob.Exported || job is TransferJob.Spreadsheet || job is TransferJob.Failed

/** An export's running, done or failed state. */
@Composable
private fun ExportJobState(job: TransferJob, onCancel: () -> Unit) {
    when (job) {
        is TransferJob.Running -> {
            Text("${job.operation.doing}${Separator}${grouped(job.rows)} ROWS", style = MonoLabel, color = Muted)
            TapTarget("Cancel", onCancel)
        }
        is TransferJob.Exported -> {
            Saved(job.rows, job.savedAs)
            job.unrecorded?.let { reason ->
                Text(unrecordedExport(reason), fontFamily = Body, fontSize = 14.sp, color = Ink)
            }
        }
        is TransferJob.Spreadsheet -> Saved(job.rows, job.savedAs)
        is TransferJob.Failed ->
            Text(job.message, fontFamily = Body, fontSize = 14.sp, color = Ink)
        else -> Unit
    }
}

@Composable
private fun Saved(rows: Int, savedAs: String?) {
    Text("SAVED${Separator}${grouped(rows)} ROWS", style = MonoLabel, color = Muted)
    savedAs?.let { name ->
        Text("Saved as $name", fontFamily = Body, fontSize = 14.sp, color = Ink)
    }
}

@Composable
private fun Footnote() = Text(
    "YOU CHOOSE WHERE IT GOES" + Separator + "PINGED CANNOT UPLOAD IT ANYWHERE",
    style = MonoLabel,
    color = Muted,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
)

/** The HOW MUCH chip for no range. */
const val EVERYTHING_SO_FAR = "Everything so far"

/** The leave-out row for every `is_excluded` row, whatever its reason. */
const val LEFT_OUT_OF_TOTALS = "Left out of your totals"

/** The spreadsheet's button: the rows it will write, or no number before they are counted. */
internal fun exportRowsLabel(rows: Int?): String = when (rows) {
    null -> "Export"
    1 -> "Export 1 row"
    else -> "Export ${grouped(rows)} rows"
}

/**
 * The leave-out row for [n] rows awaiting review: the live count, which the
 * board's "The four" is one month's scenario of, and "The one" for a single row.
 */
internal fun awaitingReview(n: Int): String =
    if (n == 1) "The one still awaiting review" else "The ${grouped(n)} still awaiting review"

private fun rowsWord(n: Int) = if (n == 1) "1 ROW" else "${grouped(n)} ROWS"

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
