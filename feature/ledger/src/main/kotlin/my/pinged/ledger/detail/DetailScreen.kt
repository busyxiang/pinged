package my.pinged.ledger.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.home.EDIT_MERCHANT
import my.pinged.ledger.home.HeroSerif
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantSheet
import my.pinged.ledger.home.MerchantSheetState
import my.pinged.ledger.home.RemoveSheet
import my.pinged.ledger.home.RowCategoryChooser
import my.pinged.ledger.home.UnderlinedField
import my.pinged.ledger.home.chooserChoices
import my.pinged.ledger.home.heroText
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle

/**
 * One captured transaction (#153, `design/Detail.dc.html`): the amount and
 * merchant, when and where it came from, the notification Pinged read word
 * for word, and the rule and pack that read it.
 *
 * Captured rows only. A manual entry's tap is its edit screen (§9.4), and it
 * has no notification to quote or rule to name.
 *
 * Re-read on each foreground and when a delete or restore rewrites the
 * ledger, as the `Day` screen is. [onBack] is also where Delete this entry
 * leaves to: the row it removed is no longer in any list.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val read by viewModel.read.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()
    val merchantSheet by viewModel.merchantSheet.collectAsState()
    DetailScreenContent(
        read = read,
        storageUnavailable = unavailable,
        onBack = onBack,
        actions = remember(viewModel, onBack) {
            DetailActions(
                assign = viewModel::assignCategory,
                retroPreview = viewModel::retroPreview,
                saveNote = { viewModel.saveNote(it) },
                notSpending = { viewModel.notSpending() },
                countAsSpending = { viewModel.countAsSpending() },
                remove = { viewModel.remove(then = onBack) },
            )
        },
        merchantSheet = merchantSheet,
        merchantActions = remember(viewModel) {
            MerchantActions(
                open = viewModel::openMerchant,
                rename = viewModel::renameMerchant,
                merge = viewModel::mergeMerchant,
                separate = viewModel::separateMerchant,
                close = viewModel::closeMerchant,
            )
        },
        modifier = modifier,
    )
}

/** What the screen's controls call; [None] for a test that is not about them. */
internal class DetailActions(
    val assign: (categoryId: Long, teach: Boolean) -> Unit,
    val retroPreview: suspend (categoryId: Long) -> RetroPreview,
    val saveNote: (String) -> Unit,
    val notSpending: () -> Unit,
    val countAsSpending: () -> Unit,
    val remove: () -> Unit,
) {
    companion object {
        val None = DetailActions({ _, _ -> }, { RetroPreview(0, emptyList()) }, {}, {}, {}, {})
    }
}

/** [DetailScreen] without its holder, so a test can draw any state. */
@Composable
internal fun DetailScreenContent(
    read: DetailRead?,
    storageUnavailable: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: DetailActions = DetailActions.None,
    merchantSheet: MerchantSheetState? = null,
    merchantActions: MerchantActions = MerchantActions.None,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var categorizing by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val row = read?.row
    val chooser = read?.chooser
    val choices = remember(chooser) { chooser?.let(::chooserChoices).orEmpty() }

    Column(modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState())) {
        Header(onBack)
        when {
            // First: a row drawn under a ledger that cannot be read would be a
            // claim about money no read supports.
            storageUnavailable -> Notice(CANNOT_READ_YOUR_DATA, Stamp)
            read == null -> Notice("READING", Muted)
            row == null -> Notice(GONE, Muted)
            else -> DetailBody(
                row = row,
                zone = zone,
                canCategorize = choices.isNotEmpty() && !row.row.txn.isExcluded,
                onCategorize = { categorizing = true },
                onRemove = { removing = true },
                actions = actions,
                merchantActions = merchantActions,
            )
        }
    }

    if (categorizing && row != null && chooser != null && choices.isNotEmpty()) {
        RowCategoryChooser(
            row = row.row,
            chooser = chooser,
            retroPreview = { _, categoryId -> actions.retroPreview(categoryId) },
            onSave = { categoryId, teach ->
                categorizing = false
                actions.assign(categoryId, teach)
            },
            onDismiss = { categorizing = false },
        )
    }
    if (removing && row != null) {
        RemoveSheet(
            row = row.row,
            onConfirm = {
                removing = false
                actions.remove()
            },
            onDismiss = { removing = false },
        )
    }
    if (merchantSheet != null) MerchantSheet(merchantSheet, merchantActions)
}

/** The chevron, and the artboard's eyebrow at the right. */
@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .size(44.dp)
                .semantics { contentDescription = DETAIL_BACK },
            contentAlignment = Alignment.Center,
        ) {
            Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
        }
        Spacer(Modifier.weight(1f))
        Text(EYEBROW, style = MonoLabel, color = Muted)
    }
}

@Composable
private fun DetailBody(
    row: DetailRow,
    zone: ZoneId,
    canCategorize: Boolean,
    onCategorize: () -> Unit,
    onRemove: () -> Unit,
    actions: DetailActions,
    merchantActions: MerchantActions,
) {
    val txn = row.row.txn
    val name = row.row.displayName
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
        Text(if (txn.direction == Direction.REFUND) CAME_BACK else PAID, style = MonoLabel, color = Muted)
        Text(
            heroText(money(txn.amountSen, txn.currency, symbol = true)),
            style = HeroSerif,
            color = Ink,
            modifier = Modifier.padding(top = 4.dp).testTag(AMOUNT),
        )
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name ?: NO_MERCHANT,
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                color = if (name == null) Muted else Ink,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The artboard's pencil: spec 6.4's merchant sheet, which the
            // list reaches by a long press. A row with no merchant has
            // nothing to rename, so it is offered no pencil.
            row.row.txn.merchantKey?.let { key ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .clickable(role = Role.Button) { merchantActions.open(key) }
                        .size(44.dp)
                        .semantics { contentDescription = EDIT_MERCHANT },
                    contentAlignment = Alignment.Center,
                ) { Pencil() }
            }
        }
    }

    TornEdge(Modifier.padding(top = 16.dp))
    Column(Modifier.fillMaxWidth().background(Card).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(WHEN, whenOf(txn, zone))
            Field(CATEGORY, row.categoryName ?: NO_CATEGORY)
            Field(CAME_FROM, (txn.sourceLabel ?: txn.sourcePackage ?: "").uppercase(CALENDAR))
            // The raw acquirer string, only where it says something the name
            // above does not.
            txn.merchantRaw?.takeIf { it != name }?.let { Field(NAME_ON_THE_BILL, it) }
        }

        Text(WHAT_PINGED_READ, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 22.dp))
        // Verbatim, as §9.2's inbox quotes it: no trimming, no joining into
        // one string. Mono, which is how spec 8 marks text quoted word for
        // word, at MonoLabel's 12sp floor rather than the artboard's 10.5.
        val body = row.captureBigText?.takeIf { it.isNotEmpty() } ?: row.captureText
        Column(
            Modifier
                .padding(top = 9.dp)
                .fillMaxWidth()
                .testTag(VERBATIM)
                .background(Quote, RoundedCornerShape(2.dp))
                .dottedOutline()
                .padding(10.dp),
        ) {
            row.captureTitle?.let { Text(it, style = Quoted, color = Muted) }
            body?.let { Text(it, style = Quoted, color = Muted) }
            if (row.captureTitle == null && body == null) Text(NOTHING_READ, style = Quoted, color = Muted)
        }
        Text(provenance(row), style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 8.dp))

        NoteField(txn, actions.saveNote)
    }

    Actions(txn, canCategorize, onCategorize, onRemove, actions)
}

/**
 * The note: one line, saved by the keyboard's Done or by Save note, which is
 * drawn only while the line differs from what is stored. Seeded from the row
 * and re-seeded when a re-read brings a different note.
 */
@Composable
private fun NoteField(txn: Txn, save: (String) -> Unit) {
    val stored = txn.note.orEmpty()
    var typed by rememberSaveable(txn.id, stored) { mutableStateOf(stored) }
    val focus = LocalFocusManager.current
    val dirty = typed.trim() != stored
    val commit = {
        focus.clearFocus()
        if (dirty) save(typed)
    }
    Column(Modifier.padding(top = 12.dp)) {
        UnderlinedField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = ADD_A_NOTE,
            tag = NOTE_FIELD,
            capitalization = KeyboardCapitalization.Sentences,
            onDone = commit,
            // Faint is 3.12:1 on Card, short of text's 4.5:1; Muted is 4.76:1.
            placeholderColour = Muted,
        )
        if (dirty) {
            OutlinedAction(SAVE_NOTE, Ink, Border, Modifier.padding(top = 10.dp).fillMaxWidth(), dashed = false, onClick = commit)
        }
    }
}

/**
 * The band at the foot: Change category beside Not spending (or Count as
 * spending), and Delete this entry under them, each a full 44dp.
 *
 * Each is offered only where it can write, as `LedgerRow` gates its own
 * controls: the chooser not on an excluded row, which is in no total;
 * Not spending and Delete only on a committed row, since a held one is the
 * inbox's to answer; and Count as spending only on what the user excluded,
 * since a transfer's exclusion is the pack's and the inbox's Keep as expense
 * takes it back.
 */
@Composable
private fun Actions(
    txn: Txn,
    canCategorize: Boolean,
    onCategorize: () -> Unit,
    onRemove: () -> Unit,
    actions: DetailActions,
) {
    val committed = txn.state == TxnState.COMMITTED
    val pair = buildList {
        if (canCategorize) add(CHANGE_CATEGORY to onCategorize)
        when {
            committed && txn.exclusionReason == ExclusionReason.USER -> add(COUNT_AS_SPENDING to actions.countAsSpending)
            committed && !txn.isExcluded -> add(NOT_SPENDING to actions.notSpending)
        }
    }
    if (pair.isEmpty() && !committed) return
    Column(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = true)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (pair.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                pair.forEach { (label, onClick) ->
                    OutlinedAction(label, Ink, Border, Modifier.weight(1f).fillMaxHeight(), dashed = false, onClick = onClick)
                }
            }
        }
        if (committed) OutlinedAction(DELETE, Stamp, Stamp, Modifier.fillMaxWidth(), dashed = true, onClick = onRemove)
    }
}

/** An outlined button of the band: [Border] solid, or the accent dashed for Delete as the artboard draws it. */
@Composable
private fun OutlinedAction(label: String, ink: Color, edge: Color, modifier: Modifier, dashed: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .then(if (dashed) Modifier.dashedOutline(edge) else Modifier.border(1.dp, edge, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 14.sp, textAlign = TextAlign.Center), color = ink)
    }
}

/** One line of the card: the label, a dotted leader, the value in the mono. */
@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, fontFamily = Body, fontSize = 13.5.sp, lineHeight = 18.sp, color = Muted)
        Box(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 4.dp).height(1.dp).dottedRule())
        Text(value, style = MonoLabel.copy(letterSpacing = 0.sp), color = Ink, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}

/** The artboard's pencil, in the accent: a stroke path on the 24-unit grid it is drawn on. */
@Composable
private fun Pencil() {
    Canvas(Modifier.size(15.dp)) {
        val unit = size.width / 24f
        val path = Path().apply {
            moveTo(4 * unit, 20 * unit)
            lineTo(8 * unit, 20 * unit)
            lineTo(18 * unit, 10 * unit)
            lineTo(14 * unit, 6 * unit)
            lineTo(4 * unit, 16 * unit)
            close()
        }
        drawPath(path, Stamp, style = Stroke(width = 1.7f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Text(text, style = MonoLabel, color = color, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
}

/**
 * `RULE TNG-PAYMENT-V1 · PACK 7 · NOT EDITED`. EDITED once a person changed
 * what the parser decided (`user_edited`, a one-off category); a note and
 * Not spending are not that. A capture no rule id names leaves the rule out
 * rather than inventing one.
 */
internal fun provenance(row: DetailRow): String = listOfNotNull(
    row.matchedRuleId?.let { "RULE $it" },
    row.packVersion?.let { "PACK $it" },
    if (row.row.txn.userEdited) "EDITED" else "NOT EDITED",
).joinToString(" · ")

/** `30 SEP, 7.42 PM`: the row's own `local_date`, and its time in [zone] as the inbox reads it. */
internal fun whenOf(txn: Txn, zone: ZoneId): String {
    val day = LocalDates.calendarDay(txn.localDate)
    return day.dayOfMonth.toString() + " " +
        day.month.getDisplayName(JavaTextStyle.SHORT, CALENDAR).uppercase(CALENDAR) + ", " +
        CLOCK.format(Instant.ofEpochMilli(txn.occurredAt).atZone(zone))
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h.mm a", CALENDAR)

/** The notification as Pinged saw it, as the inbox sets it. */
private val Quoted = MonoLabel.copy(letterSpacing = 0.sp, lineHeight = 18.sp)

internal const val EYEBROW = "CAPTURED, NOT TYPED"
internal const val PAID = "PAID"
internal const val CAME_BACK = "CAME BACK"
internal const val WHEN = "When"
internal const val CATEGORY = "Category"
internal const val CAME_FROM = "Came from"
internal const val NAME_ON_THE_BILL = "Name on the bill"
internal const val WHAT_PINGED_READ = "WHAT PINGED READ"
internal const val NOTHING_READ = "The notification had no text."
internal const val ADD_A_NOTE = "Add a note"
internal const val SAVE_NOTE = "Save note"
internal const val CHANGE_CATEGORY = "Change category"
internal const val NOT_SPENDING = "Not spending"
internal const val COUNT_AS_SPENDING = "Count as spending"
internal const val DELETE = "Delete this entry"
internal const val GONE = "THIS PAYMENT IS NO LONGER IN YOUR LIST"
private const val NO_MERCHANT = "No merchant"
private const val NO_CATEGORY = "None"

/** The chevron's description, and what a test finds it by. */
const val DETAIL_BACK = "Back"

/** What a test finds the quoted notification by. */
internal const val VERBATIM = "detail-verbatim"

/** What a test finds the hero amount by. */
internal const val AMOUNT = "detail-amount"

/** What a test finds the note field's wrapper by; the editable node is its child. */
internal const val NOTE_FIELD = "detail-note"
