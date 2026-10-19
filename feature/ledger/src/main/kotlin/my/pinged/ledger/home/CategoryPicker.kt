package my.pinged.ledger.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.ReceiptSheetFrame
import my.pinged.ui.theme.SheetHeader
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.Toggle
import my.pinged.ui.theme.PlusMark
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule

/** The sheet's heading, named so the screen and its test cannot spell it differently. */
internal const val PICKER_HEADING = "What was this?"

/**
 * What the chooser is filing, as its context strip shows it: the bill's name
 * as it arrived, which is the notification's text and so the mono's (spec 8),
 * and its amount.
 */
internal data class ChooserSubject(val bill: String, val amount: String)

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

/** The drawn half of "Always call this", so a test can read its pixels. */
internal const val ALWAYS_SWITCH_TAG = "always-switch"

/** The sheet's save control; nothing is written until it is tapped. */
internal const val SAVE_LABEL = "Save"

/**
 * The line under the switch while it is on and a category is picked (#49):
 * how many past payments of the merchant the save will also file there.
 */
internal fun fixLine(movable: Int): String =
    "AND FIX THE $movable PAST ${if (movable == 1) "ONE" else "ONES"} FROM THIS MERCHANT"

/**
 * The line in [fixLine]'s place when the past payments could not be counted
 * (#237): an unreadable ledger is not one with none of them to fix.
 */
internal const val UNCOUNTED_LINE = "THE PAST ONES FROM THIS MERCHANT COULD NOT BE COUNTED"

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
 * The grid ends in the board's "New category" tile when [onAdd] is given
 * (#155): it opens [CategorySheet] over this one, and the category it makes is
 * picked here once [onAdd] answers its id. [allNames] are every category's,
 * Uncategorized's included, which [categories] leaves out, so the new sheet
 * can say a name is taken.
 *
 * Drawn as `design/Chooser.dc.html` (#167): the serif question and its X,
 * a dotted strip quoting the bill and its amount, the categories as a
 * two-column grid of outlined tiles, then the switch under a dotted rule, then
 * Save. A pick is drawn as the board draws it, a [Stamp] outline with the
 * icon and name in [Stamp], not as a fill.
 *
 * Two columns hold the seed's longest names: at CI's 320dp a tile has about
 * 89dp for its name at 13sp, so "Insurance & takaful" takes two lines, which
 * the 52dp tile holds. The grid is the part that scrolls, so Save stays on a
 * 640dp screen beside all fifteen (`ChooserArtboardTest`).
 *
 * With no [teach], [noSwitchNote] is said in the switch's place, or nothing
 * when it is null: manual entry's More (#272) picks for a draft, which has
 * nothing to teach and no merchant to say it lacks.
 *
 * Drawn in [ReceiptSheetFrame], the chrome every sheet shares; not in
 * `ReceiptSheet`, whose scrolling `Column` cannot hold this lazy grid.
 */
@Composable
internal fun CategoryPicker(
    categories: List<Category>,
    subject: ChooserSubject,
    teach: TeachOffer?,
    initial: Long?,
    preview: suspend (categoryId: Long) -> RetroPreview?,
    onSave: (categoryId: Long, teach: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    allNames: List<String> = categories.map { it.name },
    onAdd: (suspend (name: String, iconKey: String) -> Long?)? = null,
    noSwitchNote: String? = NO_MERCHANT_NOTE,
) {
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var always by remember(teach) { mutableStateOf(teach?.startsOn ?: false) }
    var selected by remember { mutableStateOf(initial?.takeIf { id -> categories.any { it.id == id } }) }
    // Which selection the answer is for, so a count is never drawn beside a
    // category it was not computed for while the next one is on its way.
    var answer by remember { mutableStateOf<Pair<Long, RetroPreview?>?>(null) }
    val picked = selected
    LaunchedEffect(picked, always, teach != null) {
        answer = null
        if (teach != null && always && picked != null) answer = picked to preview(picked)
    }
    // The frame opens fully expanded: the fifteen choices the seed leaves
    // after `Uncategorized` is dropped do not fit in a half sheet, and a
    // chooser that opens showing four of its options and has to be dragged is
    // a chooser that hides most of itself.
    ReceiptSheetFrame(onDismissRequest = onDismiss, modifier = modifier) {
        SheetHeader(PICKER_HEADING, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
        SubjectStrip(subject)
        // Lazy because fifteen tiles do not fit a short phone, and the sheet's
        // own slot is a `ColumnScope`, which does not scroll. `fill = false`
        // leaves what follows its own room beneath, instead of the grid
        // taking all of the sheet.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories, key = { it.id }) { category ->
                CategoryTile(category, chosen = category.id == picked) { selected = it }
            }
            if (onAdd != null) {
                item(key = NEW_CATEGORY_KEY) { NewCategoryTile { adding = true } }
            }
        }
        val note = Modifier.padding(start = 20.dp, end = 20.dp)
        if (teach != null) {
            AlwaysSwitch(teach.merchant, categories.firstOrNull { it.id == picked }?.name, always) { always = it }
            teach.mergedRuleCategory?.let { category ->
                Text(
                    mergedRuleNote(category),
                    fontFamily = Body,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Muted,
                    modifier = note.padding(bottom = 4.dp),
                )
            }
            val answered = answer?.takeIf { it.first == picked }
            val shown = answered?.second
            if (always && answered != null && shown == null) {
                Text(UNCOUNTED_LINE, style = MonoLabel, color = Muted, modifier = note)
            } else if (always && shown != null) {
                val names = shown.conflicts.map { c -> categories.firstOrNull { it.id == c.categoryId }?.name ?: "another category" }
                if (shown.conflicts.isEmpty()) {
                    Text(fixLine(shown.movable), style = MonoLabel, color = Stamp, modifier = note)
                } else {
                    Text(
                        conflictLine(shown.conflicts.sumOf { it.count }, names),
                        fontFamily = Body,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Muted,
                        modifier = note,
                    )
                }
            }
        } else if (noSwitchNote != null) {
            Text(
                noSwitchNote,
                fontFamily = Body,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Muted,
                modifier = note.padding(top = 18.dp),
            )
        }
        SaveButton(enabled = picked != null, onClick = { picked?.let { onSave(it, teach != null && always) } })
    }
    if (adding && onAdd != null) {
        CategorySheet(
            existing = null,
            transactions = 0,
            rules = 0,
            otherNames = allNames,
            onSave = { name, icon ->
                adding = false
                // Picked once written: the tile itself arrives with the
                // holder's re-read, and `selected` is an id, so it is drawn
                // chosen as soon as it is drawn at all.
                scope.launch { onAdd(name, icon)?.let { selected = it } }
            },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
}

/**
 * The board's context strip: the bill as it arrived and its amount, in a
 * dotted box on [Quote], as the review inbox quotes a notification. The bill
 * at the 12sp floor rather than the board's 10.5, for [MonoLabel]'s reason;
 * the amount 2sp above it, as the board sets it 1.5px above.
 */
@Composable
private fun SubjectStrip(subject: ChooserSubject) {
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp)
            .fillMaxWidth()
            .background(Quote, RoundedCornerShape(2.dp))
            .dottedOutline()
            .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            subject.bill,
            style = MonoLabel.copy(letterSpacing = 0.sp),
            color = Muted,
            maxLines = 1,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Text(subject.amount, style = MonoNumerals, fontSize = 14.sp, color = Ink, modifier = Modifier.alignByBaseline())
    }
}

/**
 * `design/Chooser.dc.html`'s Save: [Stamp] filled, 52dp, a 2dp corner, under
 * the artboards' 24dp bottom margin. Material's `Button` is a full pill
 * whatever the theme's shapes say (#164). While it would write nothing it is
 * outlined in [Muted], label too, as Cash entry's Save is: a solid slab reads
 * as enabled. The category sheet gives it its own [margins].
 */
@Composable
internal fun SaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
    margins: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
) {
    Box(
        Modifier
            .padding(margins)
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.background(Stamp) else Modifier.border(1.dp, Muted, RoundedCornerShape(2.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(SAVE_LABEL, fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, color = if (enabled) Paper else Muted)
    }
}

/**
 * "Always call this": one toggleable row, so a screen reader announces the
 * label and the state together and the whole row is the 44dp target. Under
 * the board's dotted rule, which separates it from the grid above.
 */
@Composable
private fun AlwaysSwitch(merchant: String?, category: String?, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 18.dp)
            .dottedRule(atTop = true)
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .heightIn(min = 44.dp)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(alwaysLabel(category), fontFamily = Body, fontSize = 14.sp, lineHeight = 18.sp, color = Ink)
            Text(alwaysNote(merchant), style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
        // Drawn only: the row above owns the click and the semantics.
        Toggle(on = on, modifier = Modifier.testTag(ALWAYS_SWITCH_TAG))
    }
}

/**
 * The board's last tile: a dashed [Muted] outline, the plus, and "New
 * category" in [Ink], drawn as an offer rather than a choice. A button, not a
 * radio: it opens a sheet and selects nothing itself.
 */
@Composable
private fun NewCategoryTile(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .dashedOutline(Muted)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        PlusMark(size = 18.dp, strokeWidth = 1.6.dp, tint = Muted)
        Text(NEW_CATEGORY, fontFamily = Body, fontSize = 13.sp, lineHeight = 17.sp, color = Ink)
    }
}

/** The New category tile's key in the grid, which no `category.id` can be. */
private const val NEW_CATEGORY_KEY = "new-category"

/**
 * One category, as the board's tile: its icon and its name in a 52dp box
 * outlined in [Border], or in [Stamp] at 1.5dp with the icon and name in
 * [Stamp] once [chosen]. A tap selects it; the sheet's save control is what
 * writes.
 *
 * The icon is decorative here and carries a null `contentDescription`: the
 * name is beside it in the same control, so announcing the icon as well would
 * read the tile twice. [categoryIcon] returns null for a key this build does
 * not bundle, and the tile is then the name alone -- see its KDoc for why that
 * is the right answer rather than a stand-in glyph.
 */
@Composable
private fun CategoryTile(category: Category, chosen: Boolean, onChoose: (Long) -> Unit) {
    val tint = if (chosen) Stamp else Muted
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (chosen) 1.5.dp else 1.dp, if (chosen) Stamp else Border, shape)
            .selectable(selected = chosen, role = Role.RadioButton) { onChoose(category.id) }
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val icon = categoryIcon(category.iconKey)
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            category.name,
            fontFamily = Body,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = if (chosen) Stamp else Ink,
        )
    }
}
