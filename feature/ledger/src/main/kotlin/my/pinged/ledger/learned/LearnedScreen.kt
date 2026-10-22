package my.pinged.ledger.learned

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.dao.LearnedMerchant
import my.pinged.data.dao.RejectRuleRow
import my.pinged.data.dao.TaughtRuleRow
import my.pinged.ledger.BackChevron
import my.pinged.ledger.ListNotReadYet
import my.pinged.ledger.SETTINGS_BACK
import my.pinged.parse.Direction
import my.pinged.ui.CALENDAR
import my.pinged.ledger.settings.grouped
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule

/**
 * What the user has taught, and a way to forget it, drawn from
 * `design/Rules.dc.html`: learned merchants (#50) under MERCHANTS, spec 5.8's
 * taught messages under MESSAGES, and spec 5.7's "never a transaction" rules
 * under IGNORED. Read and delete only: a category is changed by tapping it
 * again in the chooser, and a message is taught again from the unread list.
 *
 * Read once per foreground, as `CorrectionsScreen` is, so a payment counted or
 * a merge made elsewhere shows in the PAYMENTS figure on return.
 */
@Composable
fun LearnedScreen(viewModel: LearnedViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LearnedContent(
        state,
        onBack,
        viewModel::delete,
        modifier,
        onDeleteIgnored = viewModel::deleteIgnored,
        onDeleteMessage = viewModel::deleteMessage,
    )
}

/**
 * The board's tabs, each with what it says when it lists nothing, how many
 * it lists, and its rows' count line while the read is in flight.
 */
internal enum class TaughtTab(val label: String, val nothing: String, val countIn: (LearnedState) -> Int, val notReadYet: String) {
    MERCHANTS("MERCHANTS", NOTHING_TAUGHT, { it.items.size }, paymentsLabel(NOT_READ_YET)),
    MESSAGES("MESSAGES", NO_MESSAGES, { it.messages.size }, "READ $NOT_READ_YET"),
    IGNORED("IGNORED", NOTHING_IGNORED, { it.ignored.size }, hidesLabel(NOT_READ_YET)),
}

/** [LearnedScreen] without its holder, so a test can draw any state. */
@Composable
internal fun LearnedContent(
    state: LearnedState,
    onBack: () -> Unit,
    onDelete: (LearnedMerchant) -> Unit,
    modifier: Modifier = Modifier,
    onDeleteIgnored: (RejectRuleRow) -> Unit = {},
    initialTab: TaughtTab = TaughtTab.MERCHANTS,
    onDeleteMessage: (TaughtRuleRow) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    if (!state.loaded) {
        Column(modifier.fillMaxSize().background(Paper)) {
            Header(onBack)
            ReadingLayout { Tabs(selected = tab, count = { NOT_READ_YET }, onSelect = { tab = it }) }
            ListNotReadYet(caption = tab.notReadYet, icon = false)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().background(Paper)) {
        item { Header(onBack) }
        item {
            Tabs(
                selected = tab,
                count = { if (state.storageUnavailable) null else grouped(it.countIn(state)) },
                onSelect = { tab = it },
            )
        }
        item {
            val notice = when {
                state.storageUnavailable -> CANNOT_READ_YOUR_DATA
                tab.countIn(state) == 0 -> tab.nothing
                else -> null
            }
            if (notice != null) {
                Text(notice, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        when (tab) {
            TaughtTab.MERCHANTS -> items(state.items, key = { it.identity }) { merchant ->
                LearnedRow(merchant, onDelete = { onDelete(merchant) })
            }
            TaughtTab.MESSAGES -> items(state.messages, key = { it.id }) { rule ->
                MessageRow(rule, onDelete = { onDeleteMessage(rule) })
            }
            TaughtTab.IGNORED -> items(state.ignored, key = { it.id }) { rule ->
                IgnoredRow(rule, onDelete = { onDeleteIgnored(rule) })
            }
        }
    }
}

/**
 * The board's chips, `MERCHANTS 38`: the selected one filled in [Stamp], the
 * other outlined. 30dp drawn, as the board sets them, in a 44dp target.
 * [count] null draws none, for `SettingsState.sourcesOn`'s reason.
 */
@Composable
private fun Tabs(selected: TaughtTab, count: (TaughtTab) -> String?, onSelect: (TaughtTab) -> Unit) {
    Row(Modifier.padding(start = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        TaughtTab.entries.forEach { tab ->
            val on = tab == selected
            val shape = RoundedCornerShape(2.dp)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Tab, onClick = { onSelect(tab) })
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label + (count(tab)?.let { " $it" } ?: ""),
                    style = MonoLabel,
                    color = if (on) Paper else Muted,
                    modifier = Modifier
                        .then(if (on) Modifier.background(Stamp, shape) else Modifier.border(1.dp, Border, shape))
                        .heightIn(min = 30.dp)
                        .wrapContentHeight()
                        .padding(horizontal = 9.dp),
                )
            }
        }
    }
}

/**
 * One "never a transaction" rule: the notification it was made from, word
 * for word, then its app and how many captures it hides now (spec 5.7).
 */
@Composable
private fun IgnoredRow(rule: RejectRuleRow, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = false)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(rule.sampleText, style = MonoLabel.copy(letterSpacing = 0.sp, lineHeight = 18.sp), color = Muted)
            Text(
                (rule.label ?: rule.sourcePackage).uppercase(CALENDAR) + Separator + hidesLabel(rule.hidden),
                style = MonoLabel,
                color = Faint,
            )
        }
        DeleteButton(onDelete)
    }
}

/**
 * One taught message: the notification it was taught on, word for word, then
 * its app, which way the money goes and how many it has read (spec 5.8).
 * Deleting it leaves those transactions as they are.
 */
@Composable
private fun MessageRow(rule: TaughtRuleRow, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = false)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(rule.sampleText, style = MonoLabel.copy(letterSpacing = 0.sp, lineHeight = 18.sp), color = Muted)
            Text(messageLabel(rule), style = MonoLabel, color = Faint)
        }
        DeleteButton(onDelete)
    }
}

@Composable
private fun LearnedRow(merchant: LearnedMerchant, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = false)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Unfilled weight: a long name wraps rather than push the chip off the row.
                Text(
                    merchant.name,
                    fontFamily = Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    color = Ink,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CategoryChip(merchant.categoryName)
            }
            Text(paymentsLabel(merchant.payments), style = MonoLabel, color = Muted)
        }
        DeleteButton(onDelete)
    }
}

/**
 * The board's category chip: a 1dp [Border] box. 12sp where the board sets
 * 11, the floor `MonoLabel`'s KDoc gives small print on these screens.
 */
@Composable
private fun CategoryChip(name: String) {
    Text(
        name,
        fontFamily = Body,
        fontSize = 12.sp,
        color = Muted,
        modifier = Modifier
            .border(1.dp, Border, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The board's trash can, 17dp in [Stamp] at its 1.6 stroke in a 24 box, in a
 * 44dp target. Offset 13.5dp, half the target's slack, so the glyph's own edge
 * sits on the 20dp margin, as `SheetHeader`'s X does.
 */
@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    Box(
        Modifier
            .offset(x = 13.5.dp)
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onDelete)
            .semantics { contentDescription = DELETE },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            // The board's `M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13`.
            val u = size.width / 24f
            val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawLine(Stamp, Offset(5 * u, 7 * u), Offset(19 * u, 7 * u), stroke.width, StrokeCap.Round)
            val lid = Path().apply {
                moveTo(9 * u, 7 * u); lineTo(9 * u, 5 * u); lineTo(15 * u, 5 * u); lineTo(15 * u, 7 * u)
            }
            val can = Path().apply {
                moveTo(7 * u, 7 * u); lineTo(8 * u, 20 * u); lineTo(16 * u, 20 * u); lineTo(17 * u, 7 * u)
            }
            drawPath(lid, Stamp, style = stroke)
            drawPath(can, Stamp, style = stroke)
        }
    }
}

/** The chevron beside the serif title, as on every screen Settings opens. */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackChevron(SETTINGS_BACK, onBack)
        Text(
            TAUGHT,
            fontFamily = Display,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            color = Ink,
        )
    }
}

/** The row's count, in the words the issue gives it. */
internal fun paymentsLabel(payments: Int): String = paymentsLabel(grouped(payments))

private fun paymentsLabel(payments: String): String = "PAYMENTS $payments"

/** The settings row's label and this screen's title, the board's. */
const val TAUGHT = "Things you taught Pinged"

/** A MESSAGES row's second line: `MAYBANK MAE · MONEY OUT · NO MERCHANT · READ 2`. */
internal fun messageLabel(rule: TaughtRuleRow): String = listOfNotNull(
    (rule.label ?: rule.sourcePackage).uppercase(CALENDAR),
    if (rule.direction == Direction.REFUND) "REFUND" else "MONEY OUT",
    "NO MERCHANT".takeIf { !rule.hasMerchantGroup },
    "READ ${grouped(rule.matchCount)}",
).joinToString(Separator)

/** The IGNORED row's count, in the board's voice. */
internal fun hidesLabel(hidden: Int): String = hidesLabel(grouped(hidden))

private fun hidesLabel(hidden: String): String = "HIDES $hidden"
internal const val DELETE = "Delete"
internal const val NOTHING_IGNORED =
    "Nothing ignored. Tap 'Never a transaction' on a notification Pinged could not read."
internal const val NO_MESSAGES =
    "No messages taught. Tap 'Teach Pinged this' on a notification Pinged could not read."
internal const val NOTHING_TAUGHT =
    "Nothing taught yet. Pick a category on a payment and leave 'Always call this' on."
