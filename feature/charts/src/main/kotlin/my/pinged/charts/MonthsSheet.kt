package my.pinged.charts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.MonthsItem
import my.pinged.charts.model.monthsSheet
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule
import java.time.YearMonth

/** The `Months` sheet, read when it opens, so its rows are as of that moment. */
@Composable
internal fun MonthsSheetHost(viewModel: ChartsViewModel, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(viewModel) { viewModel.readMonths() }
    val read by viewModel.months.collectAsState()
    MonthsSheet(read?.let(::monthsSheet), onPick, onDismiss)
}

/**
 * The `Months` sheet (#81, `Months` sheet; the `Months` artboard): every month
 * to jump to, newest first, and the history-start divider.
 *
 * [items] is null while the read is in flight, drawn as `READING` rather than
 * as an empty list, which would claim there are no months.
 *
 * Public because Spending's header opens it too (#192), and search's month
 * filter (#154): `:feature:ledger` depends on this module, never the other
 * way (#81, Modules).
 *
 * [onAny] adds an `Any month` row above the months, for a caller to whom no
 * month is an answer; search's is the only one.
 */
@Composable
fun MonthsSheet(
    items: List<MonthsItem>?,
    onPick: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
    onAny: (() -> Unit)? = null,
) {
    ReceiptSheet(onDismissRequest = onDismiss, title = "Jump to") {
        // 4dp on `SheetHeader`'s 14 is the board's 18.
        Column(Modifier.padding(top = 4.dp)) {
            if (onAny != null) {
                Text(
                    ANY_MONTH,
                    fontFamily = Body,
                    fontSize = 15.5.sp,
                    color = Ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(onClick = onAny)
                        .dottedRule()
                        .padding(vertical = 12.dp),
                )
            }
            if (items == null) {
                Text("READING", style = MonoLabel, color = Muted)
            } else {
                items.forEach { item ->
                    when (item) {
                        is MonthsItem.Row -> MonthRow(item) { onPick(item.month) }
                        is MonthsItem.Divider -> Divider(item)
                    }
                }
            }
        }
    }
}

/**
 * One month: its name, `N DAYS NOT CAPTURED` under it in the accent, the net,
 * and `NOW`. An untrusted month greys its name and total, as the artboard's
 * May does, and the flag says why, so the grey is never colour alone.
 *
 * A flagged row is top-aligned, as the board's May is: centred, its total
 * would sit between the name and the flag and read as belonging to neither.
 */
@Composable
private fun MonthRow(row: MonthsItem.Row, onClick: () -> Unit) {
    val tone = if (row.greyed) Muted else Ink
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .dottedRule()
            .padding(vertical = 12.dp),
        verticalAlignment = if (row.notCaptured != null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                fontFamily = Body,
                fontSize = 15.5.sp,
                fontWeight = if (row.now) FontWeight.Medium else FontWeight.Normal,
                color = tone,
            )
            row.notCaptured?.let { Text(it, style = MonoLabel, color = Stamp, modifier = Modifier.padding(top = 3.dp)) }
        }
        Text(row.net, style = MonoNumerals, fontSize = 13.sp, color = tone)
        if (row.now) {
            Text(
                "NOW",
                style = MonoLabel,
                color = Stamp,
                modifier = Modifier
                    .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** `NOTHING BEFORE 4 APRIL 2026`: where the record stops (#65). */
@Composable
private fun Divider(divider: MonthsItem.Divider) {
    Text(
        divider.text,
        style = MonoLabel,
        color = Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

/** [MonthsSheet]'s row for no month at all. */
const val ANY_MONTH = "Any month"
