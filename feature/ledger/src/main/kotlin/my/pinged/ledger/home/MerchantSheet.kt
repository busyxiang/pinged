package my.pinged.ledger.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantMember
import my.pinged.data.dao.MerchantTotal
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.LocalHeldBy
import my.pinged.ui.theme.Magnifier
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.ReceiptSheetFrame
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.SheetHeader
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule
import java.time.YearMonth
import java.time.format.TextStyle as DateStyle
import java.util.Locale

/** The sheet's labels, named so the screen and its test cannot spell them differently. */
internal const val NAME_HEADING = "NAME"
internal const val SAVE_NAME = "Save name"
internal const val SAME_SHOP_HEADING = "SAME SHOP AS…"
internal const val ALSO_PAID_AS = "ALSO PAID AS"
internal const val SUGGESTED = "SUGGESTED"
internal const val SEPARATE = "Separate"

/**
 * The heading over the candidates when [SameShop][my.pinged.parse.SameShop]
 * suggests nothing: the [RECENT_COUNT] most recently paid merchants. The board
 * draws only the suggested case; without this, a merchant with no suggestion
 * would open on an empty list until something was typed.
 */
internal const val RECENT = "RECENT"
internal const val RECENT_COUNT = 5

/**
 * What Separate says when the key it frees has no learned rule of its own while
 * the merged shop has one: the key goes back to Uncategorized going forward
 * (#35). Rules it already filed stay as they are.
 */
internal const val SEPARATE_NO_RULE = "No rule of its own: new payments go back to Uncategorized."

internal const val NAME_FIELD_TAG = "merchant-name"
internal const val FILTER_FIELD_TAG = "merchant-filter"

/** The sheet's list, which a test scrolls to reach an item not yet composed. */
internal const val MERCHANT_LIST_TAG = "merchant-list"

/** The key of the list item holding the search field, which typing scrolls to the top. */
private const val SEARCH_ITEM = "search"

/**
 * What spec 6.4's merchant sheet shows for one row, read in one piece by
 * `RowActions.openMerchant`.
 *
 * Two kinds of figure, on purpose. [month] and [monthTotals] describe the
 * month of the row the sheet was opened from, so the header agrees with the
 * screen behind it; they are Charts' `WHO GOT IT` row for this merchant
 * (`TxnDao.merchantTotals`), counted rows only. [txnCount], each choice's
 * count and each member's are all-time, because they say what a rename or a
 * merge touches, and that is every transaction of the merchant.
 *
 * @property ownKey the row's own `merchant_key`.
 * @property identityKey the merchant [ownKey] resolves to, which a rename names.
 * @property name what the merchant is shown as now.
 * @property derivedName what it would be shown as with no stored name; saving
 *   this deletes the stored one (`MerchantIdentityDao.rename`).
 * @property txnCount the transactions a rename reaches.
 * @property mergedInto the name of the merchant [ownKey] is merged into, or null
 *   when [ownKey] is canonical itself.
 * @property members the keys merged into [identityKey], when [ownKey] is it.
 * @property others every other merchant, [SameShop][my.pinged.parse.SameShop]'s
 *   suggestions first.
 * @property suggested which of [others] are suggestions.
 * @property ruleCategory the category the merchant [identityKey]'s learned rule
 *   files under, or null when it has none.
 * @property noOwnRule whether [ownKey] has no learned rule of its own, which
 *   is what Separate then gives it back.
 * @property month the month of the row the sheet was opened from, or null for
 *   no header line.
 * @property monthTotals the merchant's counted payments in [month], one per
 *   currency; empty when none counted.
 */
data class MerchantSheetState(
    val ownKey: String,
    val identityKey: String,
    val name: String,
    val derivedName: String?,
    val txnCount: Int,
    val mergedInto: String?,
    val members: List<MerchantMember>,
    val others: List<MerchantChoice>,
    val suggested: Set<String>,
    val ruleCategory: String? = null,
    val noOwnRule: Boolean = false,
    val month: YearMonth? = null,
    val monthTotals: List<MerchantTotal> = emptyList(),
)

/**
 * What the row a merchant sheet is opened from already shows (#284): enough
 * to open the sheet on the tap, before its read.
 *
 * @property ownKey the row's own `merchant_key`.
 * @property name the merchant's name as the row draws it, the sheet's title
 *   until the read names it.
 * @property on the row's own `local_date`, whose month the header counts.
 */
data class MerchantOpening(val ownKey: String, val name: String, val on: LocalDate) {
    /** The month [on] falls in: the header's, which needs no read. */
    val month: YearMonth get() = YearMonth.from(LocalDates.calendarDay(on))
}

/**
 * A merchant sheet that is open: [opening] from the tap, and [read], spec
 * 6.4's read of it, null while that is in flight.
 */
data class OpenMerchantSheet(val opening: MerchantOpening, val read: MerchantSheetState? = null)

/** The sheet's writes, so `LedgerScreenContent` takes one parameter for all of them. */
internal class MerchantActions(
    val open: (MerchantOpening) -> Unit,
    val rename: (name: String) -> Unit,
    val merge: (targetKey: String) -> Unit,
    val separate: (key: String) -> Unit,
    val close: () -> Unit,
) {
    companion object {
        /** No sheet: a screen test that is not about merchants. */
        val None = MerchantActions({}, {}, {}, {}, {})
    }
}

/**
 * Spec 6.4's merchant sheet, `design/MerchantSheet.dc.html`: rename the
 * merchant, merge it into another, or separate a merge.
 *
 * Every write is the whole answer to one tap and closes the sheet; the feed
 * redraws itself through its table observer, and no total depends on which
 * merchant a row belongs to, so nothing else needs re-reading.
 *
 * **A merge is chosen, then committed.** A tap on a candidate only marks it;
 * the Card band beneath then says what moves and whose name and rule win, and
 * the one filled button commits. Nothing a merge does is lost -- Separate
 * undoes it here -- so the band is the whole confirmation.
 *
 * **The merge is this merchant into the chosen one**, as spec 6.4 and
 * `MerchantIdentityDao.merge` define it: the band and the button name the
 * chosen merchant as the one that survives, with its name and its rule.
 *
 * In [ReceiptSheetFrame] over a `LazyColumn` rather than in a `ReceiptSheet`,
 * for the reason `CategoryPicker`'s grid is lazy: a search reaches every
 * merchant in the ledger, and a scrolling `Column` would compose every one.
 */
@Composable
internal fun MerchantSheet(
    sheet: MerchantSheetState,
    actions: MerchantActions,
    modifier: Modifier = Modifier,
) {
    ReceiptSheetFrame(onDismissRequest = actions.close, modifier = modifier) { MerchantSheetRead(sheet, actions) }
}

/**
 * The merchant sheet from the tap (#284): [sheet]'s read once it lands, and
 * until then `design/LoadingSheet.dc.html`, drawn from what the row already
 * showed.
 *
 * One [ReceiptSheetFrame] for both, so the read landing redraws the sheet's
 * body in place rather than closing one sheet and opening another.
 */
@Composable
internal fun MerchantSheet(
    sheet: OpenMerchantSheet,
    actions: MerchantActions,
    modifier: Modifier = Modifier,
) {
    ReceiptSheetFrame(onDismissRequest = actions.close, modifier = modifier) {
        val read = sheet.read
        if (read != null) MerchantSheetRead(read, actions) else MerchantSheetNotReadYet(sheet.opening, actions.close)
    }
}

/** [MerchantSheet]'s body once its read has landed. */
@Composable
private fun ColumnScope.MerchantSheetRead(sheet: MerchantSheetState, actions: MerchantActions) {
    var name by rememberSaveable(sheet.identityKey) { mutableStateOf(sheet.name) }
    var filter by rememberSaveable(sheet.identityKey) { mutableStateOf("") }
    var chosenKey by rememberSaveable(sheet.identityKey) { mutableStateOf<String?>(null) }
    val shown = remember(sheet.others, sheet.suggested, filter) { candidates(sheet.others, sheet.suggested, filter) }
    val chosen = sheet.others.firstOrNull { it.identityKey == chosenKey }

    SheetHeader(
        sheet.name,
        actions.close,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        subtitle = sheet.month?.let { monthLine(it, sheet.monthTotals) },
    )
    val list = rememberLazyListState()
    // Typing puts the search field at the top of the list, so what it
    // finds is in view beneath it. At CI's 320x640 with the keyboard up the
    // list is 229px tall and the field at rest is its bottom 28px, so with
    // no scroll every result is below the sheet's edge.
    //
    // Once per height, not once per keystroke: the first letter can land
    // while the keyboard is still rising. A run measured the list at 373px
    // then, with nothing below to scroll, so a single scroll moved 0px
    // and the keyboard then shrank the list to 229px, the field at its
    // bottom again: 4 runs of 17 at CI's size. The field leaves the viewport
    // for a frame as the list shrinks, so a height it is not laid out at
    // waits for the next layout. A drag ends it, so closing the keyboard
    // does not undo the user's own scroll.
    LaunchedEffect(filter) {
        if (filter.isBlank()) return@LaunchedEffect
        val dragged = launch { list.interactionSource.interactions.first { it is DragInteraction.Start } }
        snapshotFlow {
            val search = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == SEARCH_ITEM }
            search?.let { list.layoutInfo.viewportSize.height to it.index }
        }
            .filterNotNull()
            .takeWhile { dragged.isActive }
            .collect { (_, index) -> list.scrollToItem(index) }
    }
    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag(MERCHANT_LIST_TAG), state = list) {
        item(key = "name") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
                Text(NAME_HEADING, style = MonoLabel, color = Muted)
                UnderlinedField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = sheet.derivedName.orEmpty(),
                    tag = NAME_FIELD_TAG,
                    capitalization = KeyboardCapitalization.Words,
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(reach(sheet.txnCount), style = MonoLabel, color = Faint, modifier = Modifier.weight(1f))
                    SaveNameButton(enabled = name.trim() != sheet.name, onClick = { actions.rename(name) })
                }
            }
        }

        sheet.mergedInto?.let { canonical ->
            item(key = "merged") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                    Text("Counted as $canonical.", fontFamily = Body, fontSize = 15.sp, color = Ink)
                    OutlineButton(
                        "$SEPARATE from $canonical",
                        onClick = { actions.separate(sheet.ownKey) },
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    )
                    if (sheet.noOwnRule && sheet.ruleCategory != null) RuleNote(SEPARATE_NO_RULE)
                }
            }
        }

        if (sheet.others.isNotEmpty()) {
            item(key = "others") {
                Column(
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                        .dottedRule(atTop = true)
                        .padding(top = 14.dp),
                ) {
                    Text(SAME_SHOP_HEADING, style = MonoLabel, color = Muted)
                    Text(
                        "Choose the merchant this one really is. Its payments are then " +
                            "counted there, under that name. You can separate them again here.",
                        fontFamily = Body,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = SEARCH_ITEM) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    UnderlinedField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = "Search merchants",
                        tag = FILTER_FIELD_TAG,
                        capitalization = KeyboardCapitalization.None,
                        leading = { Magnifier(15.dp, Faint) },
                    )
                    shown.heading?.let { heading ->
                        Text(heading, style = MonoLabel, color = Faint, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            items(shown.choices, key = { "other-" + it.identityKey }) { choice ->
                ChoiceRow(choice, chosen = choice.identityKey == chosenKey) {
                    chosenKey = if (chosenKey == choice.identityKey) null else choice.identityKey
                }
            }
        }

        if (chosen != null) {
            item(key = "commit") {
                Column(Modifier.padding(top = 12.dp)) {
                    Column(
                        Modifier.fillMaxWidth().background(Card).padding(horizontal = 20.dp, vertical = 11.dp),
                    ) {
                        mergeBand(sheet, chosen).forEach { line -> Text(line, style = MonoLabel, color = Muted) }
                    }
                    FilledButton(
                        countItAs(chosen),
                        onClick = { actions.merge(chosen.identityKey) },
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth(),
                    )
                }
            }
        }

        if (sheet.members.isNotEmpty()) {
            item(key = "members") {
                Text(
                    ALSO_PAID_AS,
                    style = MonoLabel,
                    color = Faint,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                )
            }
            item(key = "member-own") {
                KeyRow(sheet.ownKey, sheet.txnCount - sheet.members.sumOf { it.txnCount })
            }
            items(sheet.members, key = { "member-" + it.merchantKey }) { member ->
                KeyRow(member.merchantKey, member.txnCount) {
                    OutlineButton(SEPARATE, onClick = { actions.separate(member.merchantKey) })
                }
                if (member.noOwnRule && sheet.ruleCategory != null) {
                    Box(Modifier.padding(horizontal = 20.dp)) { RuleNote(SEPARATE_NO_RULE) }
                }
            }
        }

        item(key = "end") { Spacer(Modifier.height(22.dp)) }
    }
}

/**
 * `design/LoadingSheet.dc.html`: the sheet before its read, its title and
 * NAME field the row's own name and every figure a dash.
 *
 * The title and the close are drawn at once, since the tap is the sheet's to
 * answer and the row already said the name; the rest waits in
 * [ReadingLayout] like any read in flight. The field is drawn and takes no
 * edit: a name typed now would be overwritten by the read's, and Save name
 * cannot say what it reaches yet.
 *
 * The board draws no status line. One is drawn while something holds the
 * database, in `design/LoadingWaiting.dc.html`'s words, because the read can
 * then take over a second and the dashes alone would not say why.
 */
@Composable
private fun MerchantSheetNotReadYet(opening: MerchantOpening, close: () -> Unit) {
    val heldBy = LocalHeldBy.current
    SheetHeader(
        opening.name,
        close,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
        below = {
            ReadingLayout {
                Column {
                    Text(monthLineNotReadYet(opening.month), style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    if (heldBy != null) ReadingStatus(Modifier.padding(top = 6.dp), heldBy)
                }
            }
        },
    )
    ReadingLayout(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
                Text(NAME_HEADING, style = MonoLabel, color = Muted)
                UnderlinedField(
                    value = opening.name,
                    onValueChange = {},
                    placeholder = "",
                    tag = NAME_FIELD_TAG,
                    capitalization = KeyboardCapitalization.Words,
                    readOnly = true,
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(REACH_NOT_READ_YET, style = MonoLabel, color = Muted, modifier = Modifier.weight(1f))
                    SaveNameButton(enabled = false, onClick = {})
                }
            }
            Column(
                Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                    .dottedRule(atTop = true)
                    .padding(top = 14.dp),
            ) {
                Text(SAME_SHOP_HEADING, style = MonoLabel, color = Muted)
                Text(SUGGESTED, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 10.dp))
                repeat(2) { ChoiceNotReadYet() }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

/** A candidate not read yet: the board's dotted box, its two lines dotted rules 55% and 70% wide. */
@Composable
private fun ChoiceNotReadYet() {
    Column(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .dottedOutline()
            .heightIn(min = 44.dp)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.55f).height(10.dp).dottedRule())
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.70f).height(9.dp).dottedRule())
    }
}

/** The header before its read, as the board sets it: "— PAYMENTS · RM — IN SEPTEMBER". */
internal fun monthLineNotReadYet(month: YearMonth): String {
    val named = month.month.getDisplayName(DateStyle.FULL, CALENDAR).uppercase(Locale.ROOT)
    return "$NOT_READ_YET PAYMENTS${Separator}RM $NOT_READ_YET IN $named"
}

/** [reach] before the count is read. */
internal const val REACH_NOT_READ_YET = "$NOT_READ_YET TRANSACTIONS WILL SHOW A NEW NAME"

/** "11 TRANSACTIONS WILL SHOW A NEW NAME", the reach a rename states first (spec 6.4). */
internal fun reach(count: Int): String =
    if (count == 1) "1 TRANSACTION WILL SHOW A NEW NAME" else "$count TRANSACTIONS WILL SHOW A NEW NAME"

/**
 * The header: "11 PAYMENTS · RM268.15 IN SEPTEMBER", or "NO PAYMENTS COUNTED
 * IN SEPTEMBER" for a month with none, which is what a left-out row's month
 * can be. One figure per currency, as `merchantTotals` returns them.
 */
internal fun monthLine(month: YearMonth, totals: List<MerchantTotal>): String {
    val named = month.month.getDisplayName(DateStyle.FULL, CALENDAR).uppercase(Locale.ROOT)
    if (totals.isEmpty()) return "NO PAYMENTS COUNTED IN $named"
    val sums = totals.joinToString(" + ") { money(it.netSen, it.currency, symbol = true) }
    return "${payments(totals.sumOf { it.txnCount })}$Separator$sums IN $named"
}

/** The one filled button, which commits the merge into [target]. */
internal fun countItAs(target: MerchantChoice): String = "Count it as ${target.displayName ?: target.identityKey}"

/**
 * The Card band above the commit: what moves, then whose name and rule win.
 * Rules are read through the identity (#35, `MerchantIdentityDao.carryRule`),
 * so a target's rule files both halves and this one's stays stored for
 * Separate, and a rule only this merchant has is carried onto the target.
 */
internal fun mergeBand(sheet: MerchantSheetState, target: MerchantChoice): List<String> {
    val targetName = (target.displayName ?: target.identityKey).uppercase(Locale.ROOT)
    val moves = "${payments(sheet.txnCount)} FROM ${sheet.name.uppercase(Locale.ROOT)} WILL COUNT AS $targetName"
    val filing = target.ruleCategory ?: sheet.ruleCategory
    return listOfNotNull(
        moves,
        if (sheet.ruleCategory != null && target.ruleCategory != null) {
            "ITS NAME AND RULE GIVE WAY TO $targetName'S"
        } else {
            "ITS NAME GIVES WAY TO $targetName'S"
        },
        filing?.let { "NEW PAYMENTS WILL BE FILED UNDER ${it.uppercase(Locale.ROOT)}" },
    )
}

/** What the candidate list shows, and the heading over it. */
internal data class Candidates(val heading: String?, val choices: List<MerchantChoice>)

/**
 * The candidates for [filter]. Blank, the suggestions under [SUGGESTED], or
 * the [RECENT_COUNT] most recently paid under [RECENT] when there are none:
 * the commit sits below the list, so a blank search lists a handful rather
 * than the ledger. Typed, every merchant whose name or raw key contains it,
 * ignoring case under `Locale.ROOT` so the match does not change with the
 * device language.
 */
internal fun candidates(others: List<MerchantChoice>, suggested: Set<String>, filter: String): Candidates {
    val needle = filter.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) {
        val likely = others.filter { it.identityKey in suggested }
        return if (likely.isNotEmpty()) Candidates(SUGGESTED, likely) else Candidates(RECENT, others.take(RECENT_COUNT))
    }
    return Candidates(
        heading = null,
        choices = others.filter {
            (it.displayName ?: it.identityKey).lowercase(Locale.ROOT).contains(needle) ||
                it.identityKey.lowercase(Locale.ROOT).contains(needle)
        },
    )
}

/**
 * A candidate: the board's bordered box, its name over "2 PAYMENTS ·
 * TNG*SPEEDMART99". Chosen, a 1.5dp [Stamp] edge, the name in [Stamp] and a
 * check; otherwise a [Border] edge and an empty ring.
 */
@Composable
private fun ChoiceRow(choice: MerchantChoice, chosen: Boolean, onChoose: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 7.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(if (chosen) 1.5.dp else 1.dp, if (chosen) Stamp else Border, shape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onChoose)
            .heightIn(min = 44.dp)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                choice.displayName ?: choice.identityKey,
                fontFamily = Body,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = if (chosen) Stamp else Ink,
            )
            Text(
                payments(choice.txnCount) + Separator + choice.identityKey,
                style = MonoLabel,
                color = Faint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (chosen) CheckMark() else Box(Modifier.size(16.dp).border(1.dp, Border, CircleShape))
    }
}

/** The board's check, `M20 6L9 17l-5-5` in a 24 box at a 2.4 stroke, in [Stamp]. */
@Composable
internal fun CheckMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(20f * u, 6f * u)
            lineTo(9f * u, 17f * u)
            lineTo(4f * u, 12f * u)
        }
        drawPath(path, Stamp, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** One key under ALSO PAID AS, "TNG*99SPEEDMART · 9", over a dotted rule, with [trailing] beside it. */
@Composable
private fun KeyRow(key: String, count: Int, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .dottedRule(atTop = true)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("$key$Separator$count", style = MonoLabel, color = Muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A line about what a separate does to category rules, in the sheet's small print. */
@Composable
private fun RuleNote(text: String) {
    Text(text, fontFamily = Body, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
}

private fun payments(count: Int): String = if (count == 1) "1 PAYMENT" else "$count PAYMENTS"

/**
 * Save name: the board's outline in [Border] with its label in [Faint] while
 * the name is unchanged, then filled [Stamp], as Cash entry's Save is.
 */
@Composable
private fun SaveNameButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .clip(shape)
            .then(if (enabled) Modifier.background(Stamp) else Modifier.border(1.dp, Border, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(SAVE_NAME, style = ButtonLabel.copy(fontSize = 14.sp), color = if (enabled) Paper else Faint)
    }
}

/** The board's one filled [Stamp] button, 52dp, which commits the merge. */
@Composable
private fun FilledButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Stamp)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 15.sp), color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A bordered [Ink] button, `WipeSheet`'s `Choice` at the sheet's own size: Separate. */
@Composable
private fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .border(1.dp, Ink, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Body, fontSize = 15.sp, color = Ink)
    }
}

/**
 * `WipeSheet`'s field: a `BasicTextField` over a [Faint] underline, drawn on a
 * wrapper for the reason that file gives, with the placeholder in [Faint] too.
 * The entry screen's merchant and note are this field as well, so the app has
 * one idiom for a line of text. [tag] is on the wrapper; the editable node is
 * its child. [onDone] is the keyboard's Done key, which by default only
 * closes the keyboard. [placeholderColour] is [Faint] on [Paper]; a field on
 * another band passes its own. [leading] is drawn before the text, 8dp from
 * it: the merchant search's magnifier. [readOnly] draws the field and takes
 * no edit: the merchant sheet's name before its read.
 */
@Composable
internal fun UnderlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    capitalization: KeyboardCapitalization,
    onDone: (() -> Unit)? = null,
    placeholderColour: Color = Faint,
    leading: (@Composable () -> Unit)? = null,
    readOnly: Boolean = false,
) {
    val style = TextStyle(fontFamily = Body, fontSize = 17.sp, color = Ink)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(tag)
            .drawBehind {
                val weight = 1.5.dp.toPx()
                drawLine(
                    color = Faint,
                    start = Offset(0f, size.height - weight / 2f),
                    end = Offset(size.width, size.height - weight / 2f),
                    strokeWidth = weight,
                )
            }
            .padding(top = 8.dp, bottom = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            leading?.invoke()
            FieldLine(value, onValueChange, placeholder, style, capitalization, onDone, placeholderColour, readOnly)
        }
    }
}

/** [UnderlinedField]'s editable line. */
@Composable
private fun FieldLine(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    capitalization: KeyboardCapitalization,
    onDone: (() -> Unit)?,
    placeholderColour: Color,
    readOnly: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        // Disabled as well as read-only, so it takes no focus and offers
        // TalkBack no edit.
        enabled = !readOnly,
        readOnly = readOnly,
        singleLine = true,
        textStyle = style,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
        keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        cursorBrush = SolidColor(Stamp),
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = placeholderColour))
                field()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
