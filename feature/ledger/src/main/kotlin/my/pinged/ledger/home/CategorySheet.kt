package my.pinged.ledger.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.CategoryDao
import my.pinged.data.entity.Category
import my.pinged.ledger.ChangedLine
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.settings.grouped
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.ReceiptSheetFrame
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.SheetHeader
import my.pinged.ui.theme.Stamp

/** The sheet's two headings, named so the screens and their tests cannot spell them differently. */
internal const val EDIT_CATEGORY = "Edit category"
internal const val NEW_CATEGORY = "New category"

/** The name field, as a test finds it. */
internal const val CATEGORY_NAME_TAG = "category-name"

/** The delete control, offered only when nothing uses the category. */
internal const val DELETE_CATEGORY = "Delete this category"

/** An icon the sheet offers, without having to know which drawable it is. */
internal fun iconTag(key: String) = "category-icon-$key"

/**
 * What a save would change on [count] transactions, before it is made (spec 4,
 * `design/Category.dc.html`): a rename and a new icon both reach every row
 * filed there, in every month, because neither is stored on the row. Said
 * from the moment the sheet opens, as the board draws it, so before any edit
 * it speaks of the name; ICON once only the icon has changed. Null when
 * nothing is filed there.
 */
internal fun reachLine(count: Int, renamed: Boolean, reIconed: Boolean): String? {
    if (count == 0) return null
    val what = when {
        renamed && reIconed -> "NAME AND ICON"
        reIconed -> "ICON"
        else -> "NAME"
    }
    val rows = if (count == 1) "1 TRANSACTION" else "${grouped(count)} TRANSACTIONS"
    return "$rows WILL SHOW THE NEW $what"
}

/**
 * Why there is no Delete (spec 4's "delete only when unused", #155), naming
 * what uses it. Null when nothing does, and Delete is offered instead. A
 * learned rule counts as a use: `merchant_rule.category_id` is `RESTRICT` too.
 */
internal fun inUseLine(transactions: Int, rules: Int): String? {
    val uses = buildList {
        if (transactions > 0) add(if (transactions == 1) "1 TRANSACTION" else "${grouped(transactions)} TRANSACTIONS")
        if (rules > 0) add(if (rules == 1) "1 LEARNED MERCHANT" else "${grouped(rules)} LEARNED MERCHANTS")
    }
    if (uses.isEmpty()) return null
    return "USED BY ${uses.joinToString(" AND ")}, SO IT CANNOT BE DELETED"
}

/**
 * Spec 4's suggestion beside a rename's reach: right for fixing a label,
 * wrong for repurposing one, which would rewrite history.
 */
internal const val REPURPOSE_NOTE = "TO FILE SOMETHING ELSE HERE FROM NOW ON, MAKE A NEW CATEGORY INSTEAD"

/** Said under the name while another category holds it, compared ignoring case. */
internal const val NAME_TAKEN = "THERE IS ALREADY A CATEGORY BY THAT NAME"

/** The icon a new category starts on: the generic one, from "Anything else". */
internal const val NEW_CATEGORY_ICON = "tag"

/**
 * Spec 4's one sheet for a category's name and icon (#155), drawn as
 * `design/Category.dc.html`: the serif heading and its X, the NAME field, the
 * reach of the change in [Stamp] under it, then the icon picker in
 * [PICKER_GROUPS]' order, then Save. One sheet for both because a renamed
 * category with a stale icon is worse than no icon.
 *
 * [existing] null is "New category", from the Categories list or the chooser.
 * Otherwise it is that category, which is never the protected one: the list
 * does not open a sheet for Uncategorized. [transactions] and [rules] are what
 * points at it, read with it.
 *
 * Below Save, for an existing category: Delete when nothing uses it, and
 * otherwise the line saying what does. The board's "Move its N transactions
 * and delete" is spec 4's merge, which this sheet does not offer yet.
 *
 * [otherNames] are every other category's names, so the clash is said before
 * the tap; the DAO refuses it again in its own transaction. Save is disabled
 * while the name is blank or taken, and for an existing category until
 * something has changed.
 *
 * While [busy] runs a ledger replacement the sheet says so under its header,
 * and Save and Delete are off (#331); what was typed is kept for when it ends.
 *
 * Drawn in [ReceiptSheetFrame] with only the icons scrolling, so the name and
 * Save stay on a 640dp screen with all nine groups between them, as the board
 * keeps them with "SEVEN MORE GROUPS BELOW".
 */
@Composable
internal fun CategorySheet(
    existing: Category?,
    transactions: Int,
    rules: Int,
    otherNames: List<String>,
    onSave: (name: String, iconKey: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    busy: Operation? = null,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var icon by rememberSaveable(existing?.id) { mutableStateOf(existing?.iconKey ?: NEW_CATEGORY_ICON) }
    val clean = name.trim()
    val taken = otherNames.any { it.equals(clean, ignoreCase = true) }
    val changed = existing == null || clean != existing.name || icon != existing.iconKey
    val margins = Modifier.padding(start = 20.dp, end = 20.dp)

    ReceiptSheetFrame(onDismissRequest = onDismiss) {
        SheetHeader(
            if (existing == null) NEW_CATEGORY else EDIT_CATEGORY,
            onDismiss,
            Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        )
        busy?.let { ChangedLine(it, SHEET_LINE) }
        Column(margins.padding(top = 6.dp)) {
            Text("NAME", style = MonoLabel, color = Muted)
            UnderlinedField(
                value = name,
                onValueChange = { name = it.take(CategoryDao.MAX_NAME_LENGTH) },
                placeholder = "What you spend on",
                tag = CATEGORY_NAME_TAG,
                capitalization = KeyboardCapitalization.Sentences,
            )
            val note = when {
                taken -> NAME_TAKEN
                existing != null -> reachLine(transactions, clean != existing.name, icon != existing.iconKey)
                else -> null
            }
            if (note != null) Text(note, style = MonoLabel, color = Stamp, modifier = Modifier.padding(top = 8.dp))
            if (!taken && existing != null && transactions > 0 && clean != existing.name) {
                Text(REPURPOSE_NOTE, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp),
        ) {
            PICKER_GROUPS.forEachIndexed { index, group ->
                val label = group.title.uppercase()
                Text(
                    if (index == 0) "ICON$Separator$label" else label,
                    style = MonoLabel,
                    color = Muted,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 16.dp, bottom = 10.dp),
                )
                IconRow(group.keys, selected = icon) { icon = it }
            }
        }
        SaveButton(
            enabled = clean.isNotEmpty() && !taken && changed && busy == null,
            onClick = { onSave(clean, icon) },
            margins = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = if (existing == null) 24.dp else 11.dp),
        )
        if (existing != null) {
            val blocked = inUseLine(transactions, rules)
            if (blocked == null && onDelete != null) {
                DeleteButton(enabled = busy == null, onDelete, margins.padding(bottom = 24.dp))
            } else if (blocked != null) {
                Text(
                    blocked,
                    style = MonoLabel,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = margins.fillMaxWidth().padding(top = 2.dp, bottom = 24.dp),
                )
            }
        }
    }
}

/**
 * Six to a row, as the board lays every group out; a short last row keeps its
 * tiles the width of the others. Each is a radio button announced by the
 * icon's name with its hyphens as spaces, since the picture is the whole
 * control.
 */
@Composable
private fun IconRow(keys: List<String>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        keys.chunked(COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { key -> IconTile(key, key == selected, onPick, Modifier.weight(1f)) }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The board's tile: square, a 2dp corner, [Border] at 1dp around a [Muted]
 * icon, or [Stamp] at 1.5dp around a [Stamp] one once chosen. The 20dp glyph
 * of the board in a tile that is never under 44dp at CI's 320dp width.
 */
@Composable
private fun IconTile(key: String, chosen: Boolean, onPick: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(2.dp)
    val drawable = categoryIcon(key) ?: return
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(if (chosen) 1.5.dp else 1.dp, if (chosen) Stamp else Border, shape)
            .selectable(selected = chosen, role = Role.RadioButton) { onPick(key) }
            .semantics { contentDescription = key.replace('-', ' ') }
            .testTag(iconTag(key)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(drawable), contentDescription = null, tint = if (chosen) Stamp else Muted, modifier = Modifier.size(20.dp))
    }
}

/**
 * The board's secondary control below Save: a 44dp box outlined in [Border]
 * with its label in [Stamp], here the delete an unused category is allowed.
 * Nothing is lost by it -- no money is in a category row -- so it asks no
 * confirmation, and the category can be made again. Off, its label [Muted],
 * while a ledger replacement runs.
 */
@Composable
private fun DeleteButton(enabled: Boolean, onDelete: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onDelete)
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(DELETE_CATEGORY, fontFamily = Body, fontSize = 14.sp, color = if (enabled) Stamp else Muted)
    }
}

private const val COLUMNS = 6
