package my.pinged.ledger.teach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import my.pinged.ledger.ChangedLine
import my.pinged.ledger.ListNotReadYet
import my.pinged.ledger.settings.Operation
import my.pinged.ledger.unread.postedOf
import my.pinged.ledger.unread.sourceOf
import my.pinged.parse.Direction
import my.pinged.parse.TaughtTemplate
import my.pinged.ui.CALENDAR
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule

/**
 * Spec 5.8's Teach screen, drawn from `design/Teach.dc.html`: the message as
 * Pinged saw it, tapped word by word for its amount and its merchant; what the
 * rule will look for; money out or money back; and "Save this rule".
 *
 * **No pattern is ever shown or typed.** The preview names the parts the user
 * chose and says every other word must match exactly, which is the whole of
 * what the rule does.
 */
@Composable
fun TeachScreen(viewModel: TeachViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    // Saved: back to the list, which no longer shows what the rule reads.
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    TeachContent(
        state = state,
        onBack = onBack,
        onTap = viewModel::tap,
        onNoMerchant = viewModel::noMerchant,
        onDirection = viewModel::direction,
        onSave = { viewModel.save() },
        modifier = modifier,
        busy = busy,
    )
}

/**
 * [TeachScreen] without its holder, so a test can draw any state. While
 * [busy] runs a ledger replacement the screen says so under its header and
 * Save is off; the taps and the direction, which write nothing, stay on (#331).
 */
@Composable
internal fun TeachContent(
    state: TeachState,
    onBack: () -> Unit,
    onTap: (Int) -> Unit,
    onNoMerchant: () -> Unit,
    onDirection: (Direction) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    busy: Operation? = null,
) {
    val capture = state.capture
    val selection = state.selection
    Column(modifier.fillMaxSize().background(Paper)) {
        Header(subline = capture?.let { sourceOf(it).uppercase(CALENDAR) + Separator + postedOf(it, zone) }, reading = !state.loaded, onBack = onBack)
        if (!state.loaded) {
            // The message's lines: what it says, and how long it is, are the read.
            ListNotReadYet(caption = null, icon = false)
            return@Column
        }
        if (!state.storageUnavailable && !state.replacing && busy != null) ChangedLine(busy)
        val notice = when {
            state.replacing -> REPLACING_YOUR_DATA
            state.storageUnavailable -> CANNOT_READ_YOUR_DATA
            capture == null || selection == null -> GONE
            else -> null
        }
        if (notice != null) {
            Text(notice, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        if (capture == null || selection == null) return@Column
        val derived = selection.derived()
        // `fill = false`: the content's own height, so the save bar sits
        // directly under a short capture, and at most the room left, so a
        // tall one scrolls behind the bar pinned at the bottom (#272).
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
                Text(stepLabel(selection.step), style = MonoLabel, color = Stamp)
                Message(selection, onTap, Modifier.padding(top = 11.dp))
                if (selection.amount != null) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).border(1.5.dp, Stamp))
                        Text(AMOUNT_TAPPED, style = MonoLabel, color = Faint, modifier = Modifier.padding(start = 7.dp))
                    }
                    Text(
                        NO_MERCHANT,
                        fontFamily = Body,
                        fontSize = 13.sp,
                        color = Stamp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = onNoMerchant)
                            .semantics { selected = selection.withoutMerchant }
                            .heightIn(min = 44.dp)
                            .padding(top = 12.dp),
                    )
                }
            }
            TornEdge(Modifier.padding(top = 22.dp))
            Column(Modifier.fillMaxWidth().testTag(TEACH_CARD).background(Card).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 22.dp)) {
                Text(LOOK_FOR, style = MonoLabel, color = Muted)
                Text(
                    preview(derived?.preview),
                    style = Quoted.copy(lineHeight = 23.sp),
                    color = if (derived == null) Faint else Muted,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .testTag(TEACH_PREVIEW)
                        .background(Paper)
                        .border(1.dp, Border)
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                )
                Text(EXACTLY, fontFamily = Body, fontSize = 12.5.sp, lineHeight = 19.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
                Text(MONEY_OUT_OR_IN, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 22.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(MONEY_OUT, state.direction == Direction.EXPENSE, { onDirection(Direction.EXPENSE) }, Modifier.weight(1f))
                    Choice(MONEY_BACK, state.direction == Direction.REFUND, { onDirection(Direction.REFUND) }, Modifier.weight(1f))
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().testTag(TEACH_SAVE_BAR).background(Paper).dottedRule(atTop = true).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(FIRST_THREE, style = MonoLabel, color = Faint)
            val ready = selection.step == TeachSelection.Step.READY && derived != null && busy == null
            val shape = RoundedCornerShape(2.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (ready) Stamp else Border, shape)
                    .clickable(enabled = ready, role = Role.Button, onClick = onSave)
                    .heightIn(min = 52.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(SAVE, style = ButtonLabel.copy(fontSize = 15.5.sp), color = if (ready) Paper else Muted)
            }
        }
    }
}

/** The title, and under it the capture's source and time, which are dashes while [reading]. */
@Composable
private fun Header(subline: String?, reading: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().dottedRule().padding(start = 8.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .size(44.dp)
                    .semantics { contentDescription = TEACH_BACK },
                contentAlignment = Alignment.Center,
            ) {
                Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
            }
            Text(TEACH_TITLE, fontFamily = Display, fontSize = 22.sp, color = Ink, modifier = Modifier.padding(start = 2.dp))
        }
        if (subline != null) Text(subline, style = MonoLabel, color = Faint, modifier = Modifier.padding(start = 46.dp, top = 2.dp))
        if (reading) {
            ReadingLayout {
                Text(NOT_READ_YET + Separator + NOT_READ_YET, style = MonoLabel, color = Muted, modifier = Modifier.padding(start = 46.dp, top = 2.dp))
            }
        }
    }
}

/**
 * The message, a word to a tap: the title's words on their own line, then
 * the body's. The amount boxed in [Stamp], the merchant's words underlined
 * in it, and any other number dashed, as the board marks `1234`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Message(selection: TeachSelection, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    val words = selection.words
    Column(
        modifier
            .fillMaxWidth()
            .background(Quote)
            .dottedOutline()
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        val lines = listOf(0 until words.titleWords, words.titleWords until words.words.size).filter { !it.isEmpty() }
        lines.forEach { line ->
            FlowRow {
                for (i in line) Word(words.words[i], i == selection.amount, selection.merchant?.contains(i) == true) { onTap(i) }
            }
        }
    }
}

@Composable
private fun Word(text: String, amount: Boolean, merchant: Boolean, onTap: () -> Unit) {
    val number = !amount && !merchant && text.any(Char::isDigit)
    Box(
        Modifier
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { selected = amount || merchant }
            .heightIn(min = 36.dp)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Quoted,
            color = if (amount || merchant) Stamp else Muted,
            textDecoration = if (merchant) TextDecoration.Underline else null,
            modifier = Modifier
                .then(if (amount) Modifier.background(Paper).border(1.5.dp, Stamp) else Modifier)
                .then(if (number) Modifier.dashedUnder() else Modifier)
                .padding(horizontal = if (amount) 5.dp else 1.dp, vertical = 3.dp),
        )
    }
}

/** The board's `border-bottom: 1px dashed` under a number nobody tapped, at `dashedEdge`'s dash. */
private fun Modifier.dashedUnder(): Modifier = drawWithCache {
    val weight = 1.dp.toPx()
    val dashes = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
    onDrawWithContent {
        drawContent()
        val y = size.height - weight / 2f
        drawLine(Faint, Offset(0f, y), Offset(size.width, y), weight, pathEffect = dashes)
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .then(if (on) Modifier.background(Stamp, shape) else Modifier.border(1.dp, Border, shape))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 14.sp), color = if (on) Paper else Muted)
    }
}

/** The preview: literal text as it reads, and each chosen part as the board's filled chip. */
private fun preview(parts: List<TaughtTemplate.Part>?) = buildAnnotatedString {
    if (parts == null) {
        append(TAP_FIRST)
        return@buildAnnotatedString
    }
    for (part in parts) {
        when (part) {
            is TaughtTemplate.Part.Literal -> append(part.text)
            TaughtTemplate.Part.Amount -> withStyle(SpanStyle(background = Stamp, color = Paper)) { append(" amount ") }
            TaughtTemplate.Part.Merchant -> withStyle(SpanStyle(background = Stamp, color = Paper)) { append(" merchant ") }
            TaughtTemplate.Part.AnyNumber -> withStyle(SpanStyle(background = Rule, color = Muted)) { append(" any number ") }
        }
    }
}

internal fun stepLabel(step: TeachSelection.Step): String = when (step) {
    TeachSelection.Step.AMOUNT -> "STEP 1 OF 2 · TAP THE AMOUNT"
    TeachSelection.Step.MERCHANT -> "STEP 2 OF 2 · NOW TAP THE MERCHANT"
    TeachSelection.Step.READY -> "BOTH DONE · CHECK WHAT PINGED WILL LOOK FOR"
}

/** The notification as Pinged saw it, as the unread list quotes one. */
private val Quoted = MonoLabel.copy(letterSpacing = 0.sp, lineHeight = 18.sp, fontSize = 12.sp)

const val TEACH_TITLE = "Teach Pinged to read this"
internal const val TEACH_BACK = "Back"
internal const val TEACH_PREVIEW = "teach-preview"

/** What a test finds the card under the torn edge by: the last of the scrolling content. */
internal const val TEACH_CARD = "teach-card"

/** What a test finds the save bar by: [FIRST_THREE] and [SAVE] under their dotted rule. */
internal const val TEACH_SAVE_BAR = "teach-save-bar"
internal const val AMOUNT_TAPPED = "AMOUNT, TAPPED · TAP AGAIN TO CHANGE"
internal const val NO_MERCHANT = "This message has no merchant in it"
internal const val LOOK_FOR = "WHAT PINGED WILL LOOK FOR"
internal const val EXACTLY = "Every other word has to match exactly, so this rule cannot pick up a message it was not meant for."
internal const val TAP_FIRST = "Tap the amount in the message above."
internal const val MONEY_OUT_OR_IN = "IS THIS MONEY OUT, OR IN?"
internal const val MONEY_OUT = "Money out"
internal const val MONEY_BACK = "Money back"
internal const val FIRST_THREE = "THE FIRST THREE MATCHES WILL ASK YOU TO CHECK THE AMOUNT"
internal const val SAVE = "Save this rule"
internal const val GONE = "Pinged has read this notification since, or you said it is never a transaction. There is nothing to teach."
