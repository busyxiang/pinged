package my.pinged.charts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.MerchantRest
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.LeaderLine
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.SheetHeader
import my.pinged.ui.theme.dottedRule

/**
 * The `N more merchants` sheet (#344), the `Everything else` sheet's kind:
 * one line per merchant [rest] folds, in the ranking's order, each with its
 * name, `N×` and signed amount as a `WHO GOT IT` line draws them, though a
 * long name gives way to the amount as on Breakdown's lines. The
 * net-negative merchants are not on it; they have rows of their own. A tap on
 * a line calls [onOpen] with its identity, which closes the sheet and opens
 * that merchant's month.
 *
 * Everything on it is the ranking's own read, so it sums to the row. The
 * figures grey in an untrusted month and the names stay in ink (#73).
 * [month] is the read's, in `monthName`'s capitals.
 */
@Composable
internal fun MoreMerchantsSheet(
    month: String,
    rest: MerchantRest,
    greyed: Boolean,
    onOpen: (identityKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ReceiptSheet(onDismissRequest = onDismiss) { MoreMerchantsContent(month, rest, greyed, onOpen, onDismiss) }
}

/** [MoreMerchantsSheet] inside its frame, which a screenshot draws without the modal window around it. */
@Composable
internal fun MoreMerchantsContent(
    month: String,
    rest: MerchantRest,
    greyed: Boolean,
    onOpen: (identityKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val n = rest.merchants.size
    val figure = if (greyed) Muted else Ink
    Column {
        SheetHeader(
            MORE_MERCHANTS,
            onClose = onDismiss,
            subtitle = month + Separator + "$n ${if (n == 1) "MERCHANT" else "MERCHANTS"}" + Separator + rest.amount,
        )
        Column {
            rest.merchants.forEach { row ->
                // The 44dp a sheet's line takes, as the `Everything else`
                // sheet's do.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button, onClickLabel = merchantLabel(row.name)) { onOpen(row.identityKey) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LeaderLine {
                        Text(row.name, style = MerchantName, color = Ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Text(row.count, style = MonoNumerals.copy(fontSize = 11.sp), color = figure, maxLines = 1, softWrap = false)
                        Box(Modifier.dottedRule())
                        Text(row.amount, style = MonoNumerals.copy(fontSize = 14.sp, lineHeight = 20.sp), color = figure, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** The sheet's title. */
internal const val MORE_MERCHANTS = "More merchants"
