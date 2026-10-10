package my.pinged.ledger.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.dao.MerchantChoice
import my.pinged.data.dao.MerchantMember
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import java.util.Locale

/** The sheet's labels, named so the screen and its test cannot spell them differently. */
internal const val MERCHANT_HEADING = "MERCHANT"
internal const val SAVE_NAME = "Save name"
internal const val SAME_SHOP_HEADING = "SAME SHOP AS…"
internal const val ALSO_PAID_AS = "ALSO PAID AS"
internal const val SUGGESTED = "SUGGESTED"
internal const val SEPARATE = "Separate"

/**
 * What Separate says when the key it frees has no learned rule of its own while
 * the merged shop has one: the key goes back to Uncategorized going forward
 * (#35). Rules it already filed stay as they are.
 */
internal const val SEPARATE_NO_RULE = "No rule of its own: new payments go back to Uncategorized."

/** What a merge row says when both halves have rules: the target's is the one that stays (#35). */
internal fun bothHaveRules(targetCategory: String) = "Both have a rule: new payments will be filed under $targetCategory."

internal const val NAME_FIELD_TAG = "merchant-name"
internal const val FILTER_FIELD_TAG = "merchant-filter"

/**
 * What spec 6.4's merchant sheet shows for one row, read in one piece by
 * `LedgerViewModel.openMerchant`.
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
)

/** The sheet's writes, so `LedgerScreenContent` takes one parameter for all of them. */
internal class MerchantActions(
    val open: (ownKey: String) -> Unit,
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
 * Spec 6.4's merchant sheet: rename the merchant, merge it into another, or
 * separate a merge.
 *
 * Every write is the whole answer to one tap and closes the sheet; the feed
 * redraws itself through its table observer, and no total depends on which
 * merchant a row belongs to, so nothing else needs re-reading.
 *
 * **A merge is one tap, and says what it does before it.** The line above the
 * list states that the merchant takes the chosen one's name and can be
 * separated again here -- which is also why no confirmation step follows:
 * unlike a category merge (spec 4), nothing a merge does is lost.
 *
 * A `ModalBottomSheet` over a `LazyColumn` rather than a `ReceiptSheet`, for
 * the reason `CategoryPicker` is one: the list of merchants grows with the
 * ledger, and a scrolling `Column` would compose every row of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MerchantSheet(
    sheet: MerchantSheetState,
    actions: MerchantActions,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(sheet.identityKey) { mutableStateOf(sheet.name) }
    var filter by rememberSaveable(sheet.identityKey) { mutableStateOf("") }
    val shown = remember(sheet.others, filter) { filtered(sheet.others, filter) }

    ModalBottomSheet(
        onDismissRequest = actions.close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Paper,
        modifier = modifier,
    ) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item(key = "name") {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(MERCHANT_HEADING, style = MonoLabel, color = Muted)
                    UnderlinedField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = sheet.derivedName.orEmpty(),
                        tag = NAME_FIELD_TAG,
                        capitalization = KeyboardCapitalization.Words,
                    )
                    Text(
                        reach(sheet.txnCount),
                        fontFamily = Body,
                        fontSize = 13.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SheetButton(
                        SAVE_NAME,
                        enabled = name.trim() != sheet.name,
                        onClick = { actions.rename(name) },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                    )
                }
            }

            sheet.mergedInto?.let { canonical ->
                item(key = "merged") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                        Text(
                            "Counted as $canonical.",
                            fontFamily = Body,
                            fontSize = 15.sp,
                            color = Ink,
                        )
                        SheetButton(
                            "$SEPARATE from $canonical",
                            enabled = true,
                            onClick = { actions.separate(sheet.ownKey) },
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                        )
                        if (sheet.noOwnRule && sheet.ruleCategory != null) RuleNote(SEPARATE_NO_RULE)
                    }
                }
            }

            if (sheet.members.isNotEmpty()) {
                item(key = "members") {
                    Text(
                        ALSO_PAID_AS,
                        style = MonoLabel,
                        color = Muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(sheet.members, key = { "member-" + it.merchantKey }) { member ->
                    MemberRow(
                        member,
                        warnNoRule = member.noOwnRule && sheet.ruleCategory != null,
                        onSeparate = { actions.separate(member.merchantKey) },
                    )
                }
            }

            if (sheet.others.isNotEmpty()) {
                item(key = "others") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                        Text(SAME_SHOP_HEADING, style = MonoLabel, color = Muted)
                        Text(
                            "Choose the merchant this one really is. Its payments are then " +
                                "counted there, under that name. You can separate them again here.",
                            fontFamily = Body,
                            fontSize = 13.sp,
                            color = Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        UnderlinedField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = "Search",
                            tag = FILTER_FIELD_TAG,
                            capitalization = KeyboardCapitalization.None,
                        )
                    }
                }
                items(shown, key = { "other-" + it.identityKey }) { choice ->
                    ChoiceRow(
                        choice,
                        suggested = choice.identityKey in sheet.suggested,
                        rulesNote = choice.ruleCategory?.takeIf { sheet.ruleCategory != null }?.let(::bothHaveRules),
                        onChoose = { actions.merge(choice.identityKey) },
                    )
                }
            }
        }
    }
}

/** "23 transactions will show the new name", the reach a rename states first (spec 6.4). */
internal fun reach(count: Int): String =
    if (count == 1) "1 transaction will show the new name" else "$count transactions will show the new name"

/**
 * [others] whose name contains [filter], ignoring case under `Locale.ROOT` so
 * the match does not change with the device language. A blank filter keeps
 * every row in the suggestions-first order it arrived in.
 */
internal fun filtered(others: List<MerchantChoice>, filter: String): List<MerchantChoice> {
    val needle = filter.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return others
    return others.filter { (it.displayName ?: it.identityKey).lowercase(Locale.ROOT).contains(needle) }
}

@Composable
private fun ChoiceRow(choice: MerchantChoice, suggested: Boolean, rulesNote: String?, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onChoose)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                choice.displayName ?: choice.identityKey,
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = Ink,
            )
            Text(
                listOfNotNull(SUGGESTED.takeIf { suggested }, payments(choice.txnCount)).joinToString(Separator),
                style = MonoLabel,
                color = if (suggested) Stamp else Muted,
            )
            if (rulesNote != null) RuleNote(rulesNote)
        }
    }
}

@Composable
private fun MemberRow(member: MerchantMember, warnNoRule: Boolean, onSeparate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(member.derivedName ?: member.merchantKey, fontFamily = Body, fontSize = 15.sp, color = Ink)
            if (warnNoRule) RuleNote(SEPARATE_NO_RULE)
        }
        SheetButton(SEPARATE, enabled = true, onClick = onSeparate)
    }
}

/** A line about what a merge or separate does to category rules, in the sheet's small print. */
@Composable
private fun RuleNote(text: String) {
    Text(text, fontFamily = Body, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
}

private fun payments(count: Int): String = if (count == 1) "1 PAYMENT" else "$count PAYMENTS"

/**
 * A bordered button, `WipeSheet`'s `Choice` at the sheet's own size. Disabled
 * reads as [Faint], the state `WipeSheet` gives a control that cannot act yet.
 */
@Composable
private fun SheetButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .border(1.dp, if (enabled) Ink else Faint, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Body, fontSize = 15.sp, color = if (enabled) Ink else Faint)
    }
}

/**
 * `WipeSheet`'s field: a `BasicTextField` over a [Faint] underline, drawn on a
 * wrapper for the reason that file gives, with the placeholder in [Faint] too.
 */
@Composable
private fun UnderlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tag: String,
    capitalization: KeyboardCapitalization,
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
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
            cursorBrush = SolidColor(Stamp),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = Faint))
                    field()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
