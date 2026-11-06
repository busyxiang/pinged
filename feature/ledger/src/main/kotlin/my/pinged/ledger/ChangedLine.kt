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
 * a screen or sheet whose read withholds its writes says while [busy] runs (#376),
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

/** What a screen cannot do while a restore or delete-everything runs, in [busyLine]. */
internal enum class Withheld(val word: String) {
    /** The review inbox's answers. */
    ANSWERED("ANSWERED"),

    /** The manual entry screen's Save and Delete (#108). */
    SAVED("SAVED"),

    /**
     * Every other write on a tap (#331): the category chooser's Save, the
     * merchant sheet's, Detail's and the Settings sub-screens'.
     */
    CHANGED("CHANGED"),
}

/**
 * The line a [busy] operation draws over a screen that refuses writes while
 * it runs: `RESTORING A BACKUP · NOTHING CAN BE ANSWERED UNTIL IT FINISHES`
 * over the review cards, `... SAVED ...` over the entry screen, and
 * `... CHANGED ...` everywhere else ([ChangedLine]).
 */
internal fun busyLine(busy: LedgerReplacement, withheld: Withheld): String {
    val doing = when (busy) {
        LedgerReplacement.RESTORE -> "RESTORING A BACKUP"
        LedgerReplacement.DELETE -> "DELETING EVERYTHING"
    }
    return "$doing · NOTHING CAN BE ${withheld.word} UNTIL IT FINISHES"
}
