package my.pinged.ledger

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.pinged.ledger.settings.Operation
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Stamp

/**
 * `RESTORING A BACKUP · NOTHING CAN BE CHANGED UNTIL IT FINISHES` (#331): what
 * a screen or sheet whose writes [LedgerBusy] refuses says while [busy] runs,
 * in [Stamp], as the review inbox's and the entry screen's lines are. At the
 * top of Detail and of each Settings sub-screen, and under a sheet's header.
 */
@Composable
internal fun ChangedLine(busy: Operation, padding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp)) {
    Text(busyLine(busy, Withheld.CHANGED), style = MonoLabel, color = Stamp, modifier = Modifier.padding(padding))
}
