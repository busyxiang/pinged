package my.pinged.ledger.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.FeedRow
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper

/** The sheet's labels, named so the screen and its test cannot spell them differently. */
internal const val REMOVE_HEADING = "NOT A PAYMENT?"
internal const val REMOVE_CONFIRM = "Not a payment"
internal const val REMOVE_NOTE =
    "It leaves your list and every total. The notification is kept, so the app will not read it as a new payment."

/**
 * Not a payment, asked of a captured row already counted (#132). A sheet and
 * not an immediate write: a captured row is the bank's word, and nothing here
 * can put the row back, so a stray tap must not remove it. The confirm is
 * off unless [enabled]: a sheet already open when a ledger replacement starts
 * stays open, and confirms nothing until it ends (#331).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoveSheet(row: FeedRow, onConfirm: () -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
    ) {
        Text(
            REMOVE_HEADING,
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Text(
            row.displayName ?: NO_MERCHANT_NAME,
            fontFamily = Body,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = Ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Text(
            REMOVE_NOTE,
            fontFamily = Body,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        Button(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 44.dp),
        ) {
            Text(REMOVE_CONFIRM)
        }
    }
}

private const val NO_MERCHANT_NAME = "Payment"
