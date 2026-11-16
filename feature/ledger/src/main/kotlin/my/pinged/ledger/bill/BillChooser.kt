package my.pinged.ledger.bill

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import my.pinged.data.dao.BillChoice
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Magnifier
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.ReceiptSheetFrame
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.SheetHeader
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule

/**
 * Spec 7.4's bill chooser, *Which bill?*, over the payback it picks for
 * (`design/BillChooser.dc.html`, `BillChooserSearch`, `BillChooserEmpty`).
 * Every way into a payback opens it: the inbox card's `PAYS BACK`, the
 * detail screen's `+ BILL` and Pays back chip, and manual entry's
 * `PAYS BACK`.
 *
 * **A tap picks and closes**: [onPick] reports the bill's id, or null for
 * No bill, which is always first. There is no Save, so nothing here writes;
 * the caller's write holds the pick to the guards the list was drawn by,
 * since a bill offered a minute ago may have stopped counting.
 *
 * [offer] reads what the sheet lists for a search (blank for none), null
 * when the ledger would not give it, which closes the sheet as the category
 * chooser closes on a read it cannot make. A search is read once typing
 * pauses for [SEARCH_PAUSE_MS], and an answer for text no longer typed is
 * dropped. [current] is the bill picked now, ticked in [Stamp] wherever the
 * list draws it, and No bill when null.
 *
 * Drawn in [ReceiptSheetFrame] rather than `ReceiptSheet`, for the reason
 * `CategoryPicker` gives: the list is lazy, and the frame lets it weight.
 */
@Composable
internal fun BillChooser(
    payback: BillFor,
    current: Long?,
    offer: suspend (query: String) -> BillOffer?,
    onPick: (billId: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // The answer, with the text it answers, so a list is never drawn under
    // text it was not read for.
    var offered by remember { mutableStateOf<Pair<String, BillOffer>?>(null) }
    val read by rememberUpdatedState(offer)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(SEARCH_PAUSE_MS)
        val answer = read(query)
        if (answer == null) dismiss() else offered = query to answer
    }
    ReceiptSheetFrame(onDismissRequest = onDismiss) {
        SheetHeader(WHICH_BILL, onDismiss, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
        PaybackStrip(payback)
        NoBillRow(chosen = current == null) { onPick(null) }
        SearchField(query) { query = it }
        val shown = offered?.takeIf { it.first == query }?.second
        LazyColumn(
            Modifier.weight(1f, fill = false).fillMaxWidth().testTag(BILL_LIST),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
        ) {
            shown?.let { chooserLines(it, payback) }.orEmpty().forEach { line ->
                when (line) {
                    is ChooserLine.Heading -> item(key = "heading-" + line.text) {
                        Text(line.text, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = if (line.first) 16.dp else 18.dp))
                    }
                    is ChooserLine.Bill -> item(key = "bill-" + line.bill.txn.id) {
                        BillRow(line.bill, chosen = line.bill.txn.id == current) { onPick(line.bill.txn.id) }
                    }
                    is ChooserLine.Nothing -> item(key = "nothing") { Nothing(line.text) }
                }
            }
        }
        val empty = (shown as? BillOffer.Found)?.bills?.isEmpty() == true
        Text(
            if (empty) EMPTY_FOOTNOTE else chooserFootnote(payback.day, searched = query.isNotBlank()),
            style = MonoLabel.copy(lineHeight = 18.sp, letterSpacing = 0.4.sp),
            color = Muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 26.dp),
        )
    }
}

/**
 * The board's strip: the payback's name in Karla and its amount, signed as
 * money received, in a dotted box on [Quote]. Karla rather than the
 * category chooser's mono: this is a person's name, not a notification's
 * text (spec 7.4's words).
 */
@Composable
private fun PaybackStrip(payback: BillFor) {
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
            payback.name,
            fontFamily = Body,
            fontSize = 13.sp,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Text(
            "+" + money(payback.amountSen, payback.currency, symbol = false),
            style = MonoNumerals,
            fontSize = 13.sp,
            color = Ink,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** No bill, always first: it takes a pick off, and the money counts as money received. */
@Composable
private fun NoBillRow(chosen: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
            .fillMaxWidth()
            .dottedRule()
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = 52.dp)
            .testTag(NO_BILL_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(NO_BILL, fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = if (chosen) Stamp else Ink)
            Text(NO_BILL_NOTE, fontFamily = Body, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
        }
        Tick(chosen)
    }
}

/**
 * The search field: a dashed [Rule] line with the magnifier and the
 * placeholder until typed into, then a solid [Ink] one with the clear X, as
 * `BillChooserSearch` draws it.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val typing = query.isNotEmpty()
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 10.dp)
            .fillMaxWidth()
            .then(if (typing) Modifier.solidUnderline() else Modifier.dashedUnderline())
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Magnifier(size = 16.dp, tint = if (typing) Ink else Faint)
        Box(Modifier.weight(1f)) {
            if (!typing) Text(SEARCH_BILLS, fontFamily = Body, fontSize = 14.sp, color = Muted)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontFamily = Body, fontSize = 14.sp, color = Ink),
                cursorBrush = SolidColor(Stamp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(BILL_SEARCH)
                    .semantics { contentDescription = SEARCH_BILLS },
            )
        }
        if (typing) {
            Box(
                Modifier
                    .clickable(role = Role.Button) { onQuery("") }
                    .size(44.dp)
                    .semantics { contentDescription = CLEAR_BILL_SEARCH },
                contentAlignment = Alignment.Center,
            ) { Cross(Muted) }
        }
    }
}

/**
 * One bill: its name in Karla over its date in the mono and its category,
 * then `PAID BACK · YOUR SHARE` once anything is paid back, and its amount.
 * The pick is drawn in [Stamp], name, amount and tick, as the category
 * chooser marks its pick. A long name gives way; the date is never cut.
 */
@Composable
private fun BillRow(bill: BillChoice, chosen: Boolean, onPick: () -> Unit) {
    val ink = if (chosen) Stamp else Ink
    Row(
        Modifier
            .fillMaxWidth()
            .dottedRule()
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp)
            .testTag(billRowTag(bill.txn.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                billName(bill),
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chooserDay(billDay(bill)), style = RowMono, color = Muted, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                bill.categoryName?.let {
                    Text(
                        "· $it",
                        fontFamily = Body,
                        fontSize = 12.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    )
                }
            }
            paidBackLine(bill)?.let { Text(it, style = RowMono, color = Muted, modifier = Modifier.padding(top = 3.dp)) }
        }
        Text(figure(bill.txn.amountSen, bill.txn.currency), style = MonoNumerals, fontSize = 15.sp, color = ink)
        Tick(chosen)
    }
}

@Composable
private fun Nothing(line: String) {
    Text(line, fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, color = Muted, modifier = Modifier.padding(top = 22.dp))
}

/** The board's tick, `M5 12.5l4.5 4.5L19 7.5` in [Stamp] at 18dp, or its room when not chosen. */
@Composable
private fun Tick(chosen: Boolean) {
    if (!chosen) {
        Spacer(Modifier.size(18.dp))
        return
    }
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(5f * u, 12.5f * u)
            lineTo(9.5f * u, 17f * u)
            lineTo(19f * u, 7.5f * u)
        }
        drawPath(path, Stamp, style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The clear X, `M6 6l12 12M18 6L6 18` at 16dp. */
@Composable
private fun Cross(tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val u = size.width / 24f
        drawLine(tint, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u), 1.7f * u, StrokeCap.Round)
        drawLine(tint, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u), 1.7f * u, StrokeCap.Round)
    }
}

/** The typed field's 1dp [Ink] rule beneath it. */
private fun Modifier.solidUnderline(): Modifier = drawBehind {
    val y = size.height - 0.5.dp.toPx()
    drawLine(Ink, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

/** The empty field's dashed [Rule] line: an offer, as `Perforation.kt` keeps dashes for. */
private fun Modifier.dashedUnderline(): Modifier = drawBehind {
    val y = size.height - 0.5.dp.toPx()
    val dash = 4.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(Rule, Offset(x, y), Offset(minOf(x + dash, size.width), y), 1.dp.toPx())
        x += dash * 2
    }
}

/**
 * A bill row's mono lines, its date and what is paid back, at the board's
 * 0.06em rather than [MonoLabel]'s 1sp: at the 12sp floor that tracking
 * broke `32.00 PAID BACK · YOUR SHARE 64.00` in two at the board's 390dp.
 */
private val RowMono = MonoLabel.copy(letterSpacing = 0.6.sp)

/**
 * How long typing pauses before a search is read: one read per word rather
 * than per letter, and short enough that the list follows the field.
 */
internal const val SEARCH_PAUSE_MS = 250L

/** What a test finds the list, the search field, No bill and a bill's row by. */
internal const val BILL_LIST = "bill-chooser-list"
internal const val BILL_SEARCH = "bill-chooser-search"
internal const val NO_BILL_ROW = "bill-chooser-no-bill"
internal fun billRowTag(id: Long): String = "bill-chooser-bill-$id"

/**
 * A bill as spec 7.4 writes it, `Yuen Kee · 18 Sep`, in Karla medium at
 * [fontSize]: the name gives way at a narrow width and the date is never
 * cut. The inbox card's and manual entry's `PAYS BACK` value.
 */
@Composable
internal fun BillLabel(
    bill: BillChoice,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Row(modifier.semantics(mergeDescendants = true) {}) {
        Text(
            billName(bill),
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            Separator.trimEnd() + " " + shortDay(billDay(bill)),
            fontFamily = Body,
            fontWeight = FontWeight.Medium,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
