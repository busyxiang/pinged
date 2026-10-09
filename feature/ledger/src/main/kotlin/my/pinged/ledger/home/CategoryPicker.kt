package my.pinged.ledger.home

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper

/** The sheet's heading, named so the screen and its test cannot spell it differently. */
internal const val PICKER_HEADING = "PUT THIS TRANSACTION IN"

/**
 * What the chooser offers to teach: the merchant it would teach, and where the
 * switch starts (see [teachSwitchDefault]). A null offer is a row with no
 * merchant key, which gets no switch.
 */
internal data class TeachOffer(
    val merchant: String?,
    val startsOn: Boolean,
    /**
     * The name of the category the shop's rule files under, set only when the
     * shop is merged and its identity already has a rule: teaching on any key
     * of it then rewrites the one rule every key is read through (#35).
     */
    val mergedRuleCategory: String? = null,
)

/** What the chooser says before the tap when the merged shop already has a rule. */
internal fun mergedRuleNote(category: String): String =
    "This shop is merged. Its rule files every key of it under $category."

/**
 * The switch's label once [category] is picked, named so the screen and its
 * test cannot spell it differently. Before a pick there is no category to
 * name, and the label says so rather than naming a default.
 */
internal fun alwaysLabel(category: String?): String = "Always call this ${category ?: "category"}"

/** What the switch does, in the mono the sheet's other small print uses. */
internal fun alwaysNote(merchant: String?): String =
    "EVERY PAYMENT FROM ${merchant?.uppercase() ?: "THIS MERCHANT"}, UNDER WHICHEVER KEY IT ARRIVES"

/** Said instead of the switch, on a row with no merchant key. */
internal const val NO_MERCHANT_NOTE =
    "This payment has no merchant, so nothing can be learned from it. " +
        "Choosing a category changes only this payment."

/** The sheet's save control; nothing is written until it is tapped. */
internal const val SAVE_LABEL = "Save"

/**
 * The line under the switch while it is on and a category is picked (#49):
 * how many past payments of the merchant the save will also file there.
 */
internal fun fixLine(movable: Int): String =
    "AND FIX THE $movable PAST ${if (movable == 1) "ONE" else "ONES"} FROM THIS MERCHANT"

/**
 * The line that replaces [fixLine] when the user has set past payments of the
 * merchant to another category by hand (#49): it names them, and says the
 * fix is off while the rule is not. [categoryNames] are those categories'.
 */
internal fun conflictLine(count: Int, categoryNames: List<String>): String {
    val where = categoryNames.singleOrNull() ?: "other categories"
    return "You've set $count of these to $where yourself. The rule applies to new payments only."
}

/**
 * §9.1's category chooser, **pick then save** (#49): a tap on a category only
 * selects it, and [onSave] writes. The switch's label names the category it
 * is about to teach and the count under it describes that choice, so neither
 * can be shown before there is one.
 *
 * What it is handed is in `sort_order`; which categories those are is the
 * caller's decision -- `LedgerScreenContent` drops the uncategorized one, for
 * a reason recorded there. [selected] starts as [initial], the row's own
 * category when it has one, so reopening a filed row and flipping the switch
 * is a save.
 *
 * **A save teaches when the switch is on** (#45, #48): [onSave] reports the
 * category and the switch's position, and the caller writes a learned rule
 * for the merchant with it on and a one-off with it off. The switch starts
 * where [TeachOffer.startsOn] says, and a null [teach] draws no switch and
 * reports `false`. The sheet writes nothing itself.
 *
 * While the switch is on and a category is selected the sheet asks [preview]
 * for what teaching it would also fix, again on every change of either, and
 * draws the answer only once it is the answer for the current selection. The
 * save re-checks in its own transaction; this is what the user is told, not
 * what is enforced.
 *
 * §4's category editing is out, which is why this only chooses from what the
 * seed provides.
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
    teach: TeachOffer?,
    initial: Long?,
    preview: suspend (categoryId: Long) -> RetroPreview,
    onSave: (categoryId: Long, teach: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var always by remember(teach) { mutableStateOf(teach?.startsOn ?: false) }
    var selected by remember { mutableStateOf(initial?.takeIf { id -> categories.any { it.id == id } }) }
    // Which selection the answer is for, so a count is never drawn beside a
    // category it was not computed for while the next one is on its way.
    var answer by remember { mutableStateOf<Pair<Long, RetroPreview>?>(null) }
    val picked = selected
    LaunchedEffect(picked, always, teach != null) {
        answer = null
        if (teach != null && always && picked != null) answer = picked to preview(picked)
    }
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
        if (teach != null) {
            AlwaysSwitch(teach.merchant, categories.firstOrNull { it.id == picked }?.name, always) { always = it }
            teach.mergedRuleCategory?.let { category ->
                Text(
                    mergedRuleNote(category),
                    fontFamily = Body,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            val shown = answer?.takeIf { it.first == picked }?.second
            if (always && shown != null) {
                val names = shown.conflicts.map { c -> categories.firstOrNull { it.id == c.categoryId }?.name ?: "another category" }
                val pad = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                if (shown.conflicts.isEmpty()) {
                    Text(fixLine(shown.movable), style = MonoLabel, color = Muted, modifier = pad)
                } else {
                    Text(
                        conflictLine(shown.conflicts.sumOf { it.count }, names),
                        fontFamily = Body,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Muted,
                        modifier = pad,
                    )
                }
            }
        } else {
            Text(
                NO_MERCHANT_NOTE,
                fontFamily = Body,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Muted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
        // A `LazyColumn` because the sheet is scrollable and that many rows do
        // not fit on a short phone. The sheet's own slot is a `ColumnScope`,
        // which does not scroll. `fill = false` leaves the save control its
        // own room beneath, instead of the list taking all of the sheet.
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(categories, key = { it.id }) { category ->
                CategoryRow(category, chosen = category.id == picked) { selected = it }
            }
        }
        Button(
            onClick = { picked?.let { onSave(it, teach != null && always) } },
            enabled = picked != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 44.dp),
        ) {
            Text(SAVE_LABEL)
        }
    }
}

/**
 * "Always call this": one toggleable row, so a screen reader announces the
 * label and the state together and the whole row is the 44dp target.
 */
@Composable
private fun AlwaysSwitch(merchant: String?, category: String?, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                alwaysLabel(category),
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = Ink,
            )
            Text(alwaysNote(merchant), style = MonoLabel, color = Muted)
        }
        // Drawn only: the row above owns the click and the semantics.
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * One category: the icon it is drawn with, and its name. A tap selects it;
 * the sheet's save control is what writes.
 *
 * The icon is decorative here and carries a null `contentDescription`: the
 * name is beside it in the same control, so announcing the icon as well would
 * read the row twice. [categoryIcon] returns null for a key this build does
 * not bundle, and the row is then the name alone -- see its KDoc for why that
 * is the right answer rather than a stand-in glyph.
 */
@Composable
private fun CategoryRow(category: Category, chosen: Boolean, onChoose: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // A labelled control taking the 44dp target every other tappable
            // thing in this feature takes.
            .selectable(selected = chosen, role = Role.RadioButton) { onChoose(category.id) }
            .background(if (chosen) Ink.copy(alpha = 0.08f) else Color.Transparent)
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
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            color = Ink,
        )
    }
}
