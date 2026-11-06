package my.pinged.ledger

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.pinged.read.LedgerAccess
import my.pinged.read.LedgerReplacement
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import my.pinged.ui.theme.Stamp

/**
 * `RESTORING A BACKUP · NOTHING CAN BE CHANGED UNTIL IT FINISHES` (#331): what
 * a screen or sheet whose writes [LedgerBusy] refuses says while [busy] runs,
 * in [Stamp], as the review inbox's and the entry screen's lines are. At the
 * top of Detail and of each Settings sub-screen, and under a sheet's header.
 */
@Composable
internal fun ChangedLine(busy: LedgerReplacement, padding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp)) {
    Text(busyLine(busy, Withheld.CHANGED), style = MonoLabel, color = Stamp, modifier = Modifier.padding(padding))
}

/**
 * The replacement a screen withholds its writes for, while it does (#376):
 * what its [ChangedLine] names, and what turns its writes off. Null for every
 * other [LedgerAccess], a refusal included, which draws none of the screen.
 */
internal val LedgerAccess.withholding: LedgerReplacement?
    get() = (this as? LedgerAccess.Withholding)?.replacement

/** The last read was refused: the screen draws [notice] in place of what it read. */
internal val LedgerAccess.refused: Boolean
    get() = this == LedgerAccess.BeingReplaced || this == LedgerAccess.Unreadable

/** What a screen says in place of what it read, for a refusal; null while it can draw it. */
internal val LedgerAccess.notice: String?
    get() = when (this) {
        LedgerAccess.BeingReplaced -> REPLACING_YOUR_DATA
        LedgerAccess.Unreadable -> CANNOT_READ_YOUR_DATA
        else -> null
    }
