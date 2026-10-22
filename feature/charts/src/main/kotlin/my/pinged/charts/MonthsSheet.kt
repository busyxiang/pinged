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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.MonthsItem
import my.pinged.charts.model.monthsSheet
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.DashLeader
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule
import my.pinged.ui.theme.UpdatingStatus
import my.pinged.ui.theme.rememberUpdating
import my.pinged.ui.theme.updating
import kotlinx.coroutines.Deferred
import java.time.YearMonth

/** The `Months` sheet, read when it opens, so its rows are as of that moment. */
@Composable
internal fun MonthsSheetHost(viewModel: ChartsViewModel, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val reading = rememberMonthsRead(viewModel) { viewModel.readMonths() }
    val read by viewModel.months.collectAsState()
    MonthsSheet(read?.let(::monthsSheet), onPick, onDismiss, reading = reading)
}

/**
 * The `Months` sheet (#81, `Months` sheet; the `Months` artboard): every month
 * to jump to, newest first, and the history-start divider.
 *
 * [items] is null while the read is in flight, drawn as the months not read
 * yet rather than as an empty list, which would claim there are no months.
 *
 * Public because Spending's header opens it too (#192), and search's month
 * filter (#154): `:feature:ledger` depends on this module, never the other
 * way (#81, Modules).
 *
 * [onAny] adds an `Any month` row above the months, for a caller to whom no
 * month is an answer; search's is the only one.
 *
 * [reading] is where this opening's read stands, from [rememberMonthsRead].
 * In flight, [items] are the last opening's: they stay, dimmed under
 * `UPDATING` after the delay every read waits (#285).
 */
@Composable
fun MonthsSheet(
    items: List<MonthsItem>?,
    onPick: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
    onAny: (() -> Unit)? = null,
    reading: MonthsReading = MonthsReading.Landed,
) {
    val updating = rememberUpdating(reading == MonthsReading.InFlight)
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
            if (reading == MonthsReading.Unreadable) {
                // At once, and in place of any months held: the last
                // opening's are not this one's, and drawn undimmed they would
                // read as current.
                Text(
                    CANNOT_READ_YOUR_DATA,
                    style = MonoLabel,
                    color = Stamp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
            } else if (items == null) {
                MonthsNotReadYet()
            } else {
                if (updating) UpdatingStatus(Modifier.padding(top = 12.dp, bottom = 6.dp))
                Column(Modifier.updating(updating)) {
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
}

/**
 * The months while their read is in flight (#281, `design/LoadingSheet`'s
 * treatment): after `ReadingLayout`'s delay, the status line and four of
 * [MonthRow]'s rows as a dotted leader and a dash, since which months there
 * are is a read too. Four stand for rows, not a count.
 *
 * The sheet opens at once, at the title's height, and grows when these or
 * the rows land: `ReceiptSheetFrame` re-expands to it (#213,
 * `MonthsSheetOpensExpandedTest`).
 */
@Composable
private fun MonthsNotReadYet() {
    ReadingLayout(Modifier.fillMaxWidth()) {
        Column {
            ReadingStatus(Modifier.padding(top = 4.dp, bottom = 4.dp))
            repeat(4) {
                DashLeader(
                    Modifier
                        .heightIn(min = 44.dp)
                        .dottedRule()
                        .padding(vertical = 12.dp),
                    labelStyle = TextStyle(fontFamily = Body, fontSize = 15.5.sp),
                )
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

/** Where an opening's read of the `Months` sheet stands: what [MonthsSheet] draws for it. */
enum class MonthsReading {
    /** This opening's read has not landed: the last opening's months, if any, are stale. */
    InFlight,

    /** The months drawn are this opening's. */
    Landed,

    /** This opening's read could not be made: no months drawn are current. */
    Unreadable,
}

/**
 * Starts this opening's read of the sheet, [read], once per [key], and says
 * where it stands: what [MonthsSheet]'s `reading` takes. [read] completes
 * true once it has published the months, false when they could not be read.
 * Every host opens the sheet through this, so a holder keeps its last read
 * published and the sheet draws it under `UPDATING` rather than `READING`
 * (#285).
 */
@Composable
fun rememberMonthsRead(key: Any?, read: () -> Deferred<Boolean>): MonthsReading {
    var reading by remember { mutableStateOf(MonthsReading.InFlight) }
    LaunchedEffect(key) {
        reading = MonthsReading.InFlight
        reading = if (read().await()) MonthsReading.Landed else MonthsReading.Unreadable
    }
    return reading
}
