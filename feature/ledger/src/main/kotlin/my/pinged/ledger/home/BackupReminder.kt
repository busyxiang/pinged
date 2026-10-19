package my.pinged.ledger.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule

/**
 * What Spending's backup reminder (#252) is handed: whether the ledger is
 * damaged, which decides what its action says, and where each of its two
 * controls leads. `MainActivity` decides whether there is one at all
 * (`backupReminderShows`) and owns both destinations.
 */
class BackupReminder(
    val damaged: Boolean,
    /** Settings: its export sheet, or on a damaged ledger its recovery notice. */
    val onAction: () -> Unit,
    /** Silences it for a week; see `MainActivity`'s `NUDGE_DISMISSED_FOR_MILLIS`. */
    val onDismiss: () -> Unit,
)

/** The reminder's line, uppercased in the string so a screen reader says what is drawn. */
internal const val BACKUP_REMINDER: String = "NO BACKUP" + Separator + "ONLY ON THIS PHONE"

/** The action, which leads to settings' export sheet. */
internal const val REMINDER_EXPORT: String = "Export"

/** The action on a damaged ledger, which leads to settings' recovery notice. */
internal const val REMINDER_RESCUE: String = "Rescue"

/** The X's content description: what it does, since an X alone says "close". */
internal const val REMIND_ME_IN_A_WEEK: String = "Remind me in a week"

/**
 * `design/Backup.dc.html`'s strip: one line, an action and an X, as the
 * Spending list's first item so it scrolls away with the list.
 *
 * **"Rescue" on a damaged ledger, not "Export".** The action leads to
 * settings, which withdraws its export there (design 6) and offers the
 * recovery notice's rescue instead, so the action names what the user will
 * find.
 *
 * The X's 48dp box is the whole row's height at its minimum, so the target is
 * the board's and not the 15dp glyph.
 */
@Composable
internal fun BackupReminderRow(reminder: BackupReminder, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Paper)
            .dottedRule()
            .heightIn(min = 48.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The board's 7px hollow square, the strip's only mark.
        Spacer(Modifier.size(7.dp).border(1.5.dp, Stamp))
        Text(
            BACKUP_REMINDER,
            style = MonoLabel,
            color = Stamp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = reminder.onAction)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (reminder.damaged) REMINDER_RESCUE else REMINDER_EXPORT,
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = Stamp,
                textDecoration = TextDecoration.Underline,
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = reminder.onDismiss)
                .semantics { contentDescription = REMIND_ME_IN_A_WEEK },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(15.dp)) {
                // The boards' `M6 6l12 12M18 6L6 18` in a 24 box.
                val a = size.width * 6f / 24f
                val b = size.width * 18f / 24f
                // The board's 1.8 is in the 24 box, so it scales with it.
                val stroke = 1.8f * size.width / 24f
                drawLine(Faint, Offset(a, a), Offset(b, b), stroke, StrokeCap.Round)
                drawLine(Faint, Offset(b, a), Offset(a, b), stroke, StrokeCap.Round)
            }
        }
    }
}
