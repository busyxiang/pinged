package my.pinged.ledger.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.entity.Category
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper

/** The sheet's heading, named so the screen and its test cannot spell it differently. */
internal const val PICKER_HEADING = "PUT THIS TRANSACTION IN"

/**
 * §9.1's category chooser: what it is handed, in `sort_order`, one tap to
 * assign. Which categories those are is the caller's decision --
 * `LedgerScreenContent` drops the uncategorized one, for a reason
 * recorded there.
 *
 * **No "add a category" and no rule offered.** Manual assignment writes a
 * `category_id` and nothing else -- §6.1's learned rules are out of this
 * milestone -- so a sheet that offered to remember the choice would promise a
 * subsystem that is not there. §4's category editing is also out, which is why
 * this only chooses from what the seed provides.
 *
 * `@ExperimentalMaterial3Api` is required by [ModalBottomSheet] and
 * [rememberModalBottomSheetState] at material3 1.4.0, which is what this BOM
 * (2026.08.00) resolves -- checked in the artifact rather than assumed. The
 * opt-in is on this one composable rather than module-wide, so the next
 * experimental API has to be opted into deliberately too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPicker(
    categories: List<Category>,
    onChoose: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // `skipPartiallyExpanded`: the thirteen rows the seed leaves after the
        // row's own category is dropped do not fit in a half sheet, and a
        // chooser that opens showing four of its options and has to be dragged
        // is a chooser that hides most of itself.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
        modifier = modifier,
    ) {
        Text(
            PICKER_HEADING,
            style = MonoLabel,
            color = Muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        // A `LazyColumn` because the sheet is scrollable and that many rows do
        // not fit on a short phone. The sheet's own slot is a `ColumnScope`,
        // which does not scroll.
        LazyColumn(Modifier.fillMaxWidth()) {
            items(categories, key = { it.id }) { category ->
                CategoryRow(category, onChoose)
            }
        }
    }
}

/**
 * One category: the icon it is drawn with, and its name.
 *
 * The icon is decorative here and carries a null `contentDescription`: the
 * name is beside it in the same control, so announcing the icon as well would
 * read the row twice. [categoryIcon] returns null for a key this build does
 * not bundle, and the row is then the name alone -- see its KDoc for why that
 * is the right answer rather than a stand-in glyph.
 */
@Composable
private fun CategoryRow(category: Category, onChoose: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // A labelled control taking the 44dp target every other tappable
            // thing in this feature takes.
            .clickable(role = Role.Button) { onChoose(category.id) }
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = categoryIcon(category.iconKey)
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            category.name,
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            color = Ink,
        )
    }
}
