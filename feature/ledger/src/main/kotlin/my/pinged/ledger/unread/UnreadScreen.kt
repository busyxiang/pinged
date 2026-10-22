package my.pinged.ledger.unread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import my.pinged.data.dao.UnreadCapture
import my.pinged.ledger.ListNotReadYet
import my.pinged.parse.FixtureText
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
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule

/**
 * Spec 5.6's unread list, drawn from `design/Unread.dc.html`: what Pinged
 * kept and no rule read, newest first, each word for word.
 *
 * Each card offers spec 5.8's "Teach Pinged this", filled, and 5.7's "Never a
 * transaction", outlined, in that order on every card, an FD maturity notice
 * too. Nothing here tells a non-transaction from a wording no pack knows yet,
 * and the two mistakes do not cost the same: a wrong "Teach" costs a tap, a
 * wrong "Never" writes a reject rule that drops that shape for good, found
 * again only under Things you taught. A word list to tell them apart would be
 * a second parser outside the packs.
 *
 * Read once per foreground, as `LearnedScreen` is, so a capture stage two
 * marked while the user was away is on the list when they come back.
 */
@Composable
fun UnreadScreen(
    viewModel: UnreadViewModel,
    onBack: () -> Unit,
    onTeach: (UnreadCapture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    UnreadContent(
        state = state,
        onBack = onBack,
        onNeverATransaction = viewModel::neverATransaction,
        onTeach = onTeach,
        onCopy = { copyAsFixture(context, it) },
        onEnd = { viewModel.loadMore() },
        modifier = modifier,
    )
}

/** [UnreadScreen] without its holder, so a test can draw any state. */
@Composable
internal fun UnreadContent(
    state: UnreadState,
    onBack: () -> Unit,
    onNeverATransaction: (UnreadCapture) -> Unit,
    onCopy: (UnreadCapture) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    onTeach: (UnreadCapture) -> Unit = {},
) {
    if (!state.loaded) {
        Column(modifier.fillMaxSize().background(Card)) {
            Header(onBack)
            // A card's first line: its source and when it was posted.
            ListNotReadYet(caption = NOT_READ_YET + Separator + NOT_READ_YET, icon = false)
        }
        return
    }
    LazyColumn(
        modifier.fillMaxSize().background(Card),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item { Header(onBack) }
        val notice = when {
            state.storageUnavailable -> CANNOT_READ_YOUR_DATA
            state.items.isEmpty() -> NOTHING_UNREAD
            else -> null
        }
        if (notice != null) {
            item { Text(notice, fontFamily = Body, fontSize = 14.sp, color = Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        items(state.items, key = { it.id }) { capture ->
            UnreadCard(
                capture,
                zone,
                onTeach = { onTeach(capture) },
                onNeverATransaction = { onNeverATransaction(capture) },
                onCopy = { onCopy(capture) },
            )
            // The last card composed is the cue for the next page, as a lazy
            // list composes only what is about to show.
            if (state.more && capture.id == state.items.last().id) LaunchedEffect(capture.id) { onEnd() }
        }
        item {
            Text(
                FOOTER,
                style = MonoLabel,
                color = Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Paper)
                    .dottedRule(atTop = true)
                    .padding(start = 20.dp, end = 20.dp, top = 13.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Paper).dottedRule().padding(start = 8.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .size(44.dp)
                    .semantics { contentDescription = UNREAD_BACK },
                contentAlignment = Alignment.Center,
            ) {
                Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
            }
            Text(UNREAD_TITLE, fontFamily = Display, fontSize = 22.sp, color = Ink, modifier = Modifier.padding(start = 2.dp))
        }
        Text(
            "Pinged saw these but no rule matched, so nothing was recorded. None of them count toward any total.",
            fontFamily = Body,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = Muted,
            modifier = Modifier.padding(start = 46.dp, top = 4.dp),
        )
    }
}

@Composable
private fun UnreadCard(
    capture: UnreadCapture,
    zone: ZoneId,
    onTeach: () -> Unit,
    onNeverATransaction: () -> Unit,
    onCopy: () -> Unit,
) {
    val shape = RoundedCornerShape(2.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .testTag(UNREAD_CARD)
            .background(Paper, shape)
            .border(1.dp, Border, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sourceOf(capture).uppercase(CALENDAR), style = MonoLabel, color = Muted, modifier = Modifier.weight(1f))
            Text(postedOf(capture, zone), style = MonoLabel, color = Faint)
            CopyButton(onCopy)
        }
        // Verbatim, title and body each as stage one stored them, as the
        // review inbox quotes a notification (§9.2): the user is deciding
        // about exactly what Pinged saw.
        Column(
            Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .testTag(UNREAD_VERBATIM)
                .background(Quote, shape)
                .dottedOutline()
                .padding(10.dp),
        ) {
            capture.title?.let { Text(it, style = Quoted, color = Muted) }
            capture.body?.let { Text(it, style = Quoted, color = Muted) }
        }
        Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardAction(TEACH_PINGED_THIS, filled = true, onTeach, Modifier.weight(1f))
            CardAction(NEVER_A_TRANSACTION, filled = false, onNeverATransaction, Modifier.weight(1f))
        }
    }
}

/** One of the card's two actions, the board's 44dp pair: [filled] in [Stamp], else outlined. */
@Composable
private fun CardAction(label: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .then(if (filled) Modifier.background(Stamp, shape) else Modifier.border(1.dp, Border, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 13.sp), color = if (filled) Paper else Muted)
    }
}

/**
 * The board's copy glyph, 15dp in [Faint] at its 1.6 stroke in a 24 box, in
 * a 44dp target offset so the glyph's edge sits on the card's inner margin.
 */
@Composable
private fun CopyButton(onCopy: () -> Unit) {
    Box(
        Modifier
            .offset(x = 14.5.dp)
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onCopy)
            .semantics { contentDescription = COPY_AS_FIXTURE },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(15.dp)) {
            // The board's `<rect x=9 y=9 w=11 h=11 rx=1.5/>` and `M15 5H5.5A1.5 1.5 0 0 0 4 6.5V16`.
            val u = size.width / 24f
            val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(Faint, Offset(9 * u, 9 * u), Size(11 * u, 11 * u), CornerRadius(1.5f * u), style = stroke)
            val back = Path().apply {
                moveTo(15 * u, 5 * u); lineTo(5.5f * u, 5 * u)
                quadraticTo(4 * u, 5 * u, 4 * u, 6.5f * u); lineTo(4 * u, 16 * u)
            }
            drawPath(back, Faint, style = stroke)
        }
    }
}

/**
 * Spec 5.6's "copy as fixture", onto the clipboard as [FixtureText] writes
 * it. Marked sensitive where Android can say so (API 33), so the keyboard's
 * clipboard strip does not preview a bank's notification.
 */
private fun copyAsFixture(context: Context, capture: UnreadCapture) {
    val text = FixtureText.of(capture.sourcePackage, sourceOf(capture), capture.title, capture.text, capture.bigText)
    val clip = ClipData.newPlainText(COPY_AS_FIXTURE, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/** The card's source: the allow-list's name for the app, else its package. */
internal fun sourceOf(capture: UnreadCapture): String = capture.label ?: capture.sourcePackage

/** `30 SEP, 9.14 AM`, the board's stamp, in the device's zone. */
internal fun postedOf(capture: UnreadCapture, zone: ZoneId = ZoneId.systemDefault()): String =
    STAMP.format(Instant.ofEpochMilli(capture.postedAt).atZone(zone)).uppercase(CALENDAR)

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h.mm a", CALENDAR)

/** The notification as Pinged saw it, as the review inbox sets one. */
private val Quoted = MonoLabel.copy(letterSpacing = 0.sp, lineHeight = 18.sp)

/** The screen's title, and the settings row's label is its long form. */
const val UNREAD_TITLE = "Could not read"
const val UNREAD_ROW = "Notifications Pinged could not read"
internal const val UNREAD_BACK = "Back"
internal const val UNREAD_CARD = "unread-card"
internal const val UNREAD_VERBATIM = "unread-verbatim"
internal const val NEVER_A_TRANSACTION = "Never a transaction"
internal const val TEACH_PINGED_THIS = "Teach Pinged this"
internal const val COPY_AS_FIXTURE = "Copy as a test case"
internal const val NOTHING_UNREAD = "Nothing here. Every notification Pinged kept was read by a rule."
internal const val FOOTER = "TEACHING ONE TAKES TWO TAPS \u00B7 NOTHING HERE LEAVES YOUR PHONE"
