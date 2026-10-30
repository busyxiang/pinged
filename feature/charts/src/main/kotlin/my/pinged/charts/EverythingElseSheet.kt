package my.pinged.charts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.FoldedCategory
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.SheetHeader
import my.pinged.ui.theme.dottedRule

/**
 * The `Everything else` sheet (#342, `design/EverythingElse.dc.html`): one
 * line per category [folded], which sum to [amount], the bar's own figure, its name and signed amount, largest first,
 * with no bar track. A tap on a line calls [onOpen] with its category, which
 * closes the sheet and opens that category's Breakdown.
 *
 * Everything on it is the bar's own read. The
 * amounts grey in an untrusted month, as the bar's does, and the names stay
 * in ink (#73). [month] is the read's, in `monthName`'s capitals.
 */
@Composable
internal fun EverythingElseSheet(
    month: String,
    folded: List<FoldedCategory>,
    amount: String,
    greyed: Boolean,
    onOpen: (categoryId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ReceiptSheet(onDismissRequest = onDismiss) { EverythingElseContent(month, folded, amount, greyed, onOpen, onDismiss) }
}

/** [EverythingElseSheet] inside its frame, which a screenshot draws without the modal window around it. */
@Composable
internal fun EverythingElseContent(
    month: String,
    folded: List<FoldedCategory>,
    amount: String,
    greyed: Boolean,
    onOpen: (categoryId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    Column {
        SheetHeader(
            EVERYTHING_ELSE,
            onClose = onDismiss,
            subtitle = month + Separator + "${folded.size} ${if (folded.size == 1) "CATEGORY" else "CATEGORIES"}" + Separator + amount,
        )
        Column {
            folded.forEachIndexed { i, category ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button, onClickLabel = breakdownLabel(category.name)) { onOpen(category.categoryId) }
                        .then(if (i < folded.lastIndex) Modifier.dottedRule() else Modifier)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        category.name,
                        fontFamily = Body,
                        fontSize = 15.5.sp,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                    )
                    Text(category.amount, style = MonoNumerals.copy(fontSize = 13.sp), color = if (greyed) Muted else Ink)
                }
            }
        }
    }
}

/** The sheet's title, as the board names it. */
internal const val EVERYTHING_ELSE = "Everything else"
