package my.pinged.ledger.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.Databases
import my.pinged.data.LocalDates
import my.pinged.data.entity.Txn
import my.pinged.ledger.Withheld
import my.pinged.ledger.busyLine
import my.pinged.ledger.settings.Operation
import my.pinged.parse.Direction
import my.pinged.ui.dayMonthYear
import my.pinged.ui.money
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
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Spec 9.2's review inbox: one card per transaction awaiting review, newest
 * first. Pushed on whichever tab's stack the user came from, so back returns
 * there.
 *
 * Re-read on each foreground and when a delete or restore rewrites the
 * ledger, as the `Day` screen is.
 *
 * [focus] is the `txn.id` whose card the list opens on, or null for the top.
 */
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, focus: Long? = null) {
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val cards by viewModel.inbox.cards.collectAsState()
    val unavailable by viewModel.inbox.storageUnavailable.collectAsState()
    val busy by viewModel.inbox.busy.collectAsState()
    val undoable by viewModel.inbox.undoable.collectAsState()
    ReviewInboxScreenContent(
        cards = cards,
        storageUnavailable = unavailable,
        busy = busy,
        onBack = onBack,
        onAnswer = { card, answer -> viewModel.answer(card, answer) },
        undoable = undoable,
        onUndo = { viewModel.inbox.undo() },
        modifier = modifier,
        focus = focus,
        onAcceptAll = { viewModel.inbox.acceptAll() },
    )
}

/**
 * [ReviewScreen] without its holder, so a test can draw any state.
 *
 * [cards] null is the first read still in flight; empty is an inbox with
 * nothing in it, which stays open with a line saying so rather than closing
 * under the user. [busy] disables every answer, with one line naming the
 * operation, and Undo with them.
 *
 * [undoable] is the last answer, drawn as a bar pinned to the bottom with
 * [onUndo] on it. It stays until the next answer replaces it, it is used, or
 * a restore or a delete-everything withdraws it: no timeout, so the Undo for the card that emptied the inbox is still there
 * however long the user looks at the empty screen.
 *
 * [focus] scrolls the list to that transaction's card once, on the first read
 * that has cards; a card no longer awaiting review leaves the list at the top.
 * Once, and saved: a re-read after each answer, or a rotation, must not drag
 * the list back to a card the user has scrolled away from.
 *
 * [onAcceptAll] is the Accept all button, drawn at the header's right while any is
 * batchable ([AcceptAll.of]); a swipe on a batchable card reports [ReviewAnswer.ACCEPT]
 * through [onAnswer], as its button does.
 */
@Composable
fun ReviewInboxScreenContent(
    cards: List<ReviewCard>?,
    storageUnavailable: Boolean,
    busy: Operation?,
    onBack: () -> Unit,
    onAnswer: (ReviewCard, ReviewAnswer) -> Unit,
    modifier: Modifier = Modifier,
    undoable: Undoable? = null,
    onUndo: () -> Unit = {},
    focus: Long? = null,
    onAcceptAll: () -> Unit = {},
) {
    val acceptAll = cards?.let(AcceptAll::of)
    val list = rememberLazyListState()
    var focused by rememberSaveable(focus) { mutableStateOf(focus == null) }
    LaunchedEffect(focus, cards, storageUnavailable) {
        if (focused || cards == null || storageUnavailable) return@LaunchedEffect
        focused = true
        val at = cards.indexOfFirst { it.txn.id == focus }
        // Items above the cards: the header, which holds Accept all, and the busy line.
        if (at >= 0) list.scrollToItem(1 + (if (busy != null) 1 else 0) + at)
    }
    // The board's two grounds: the header on Paper, the list beneath it on Card.
    Box(modifier.fillMaxSize().background(Card)) {
        Cards(
            cards, storageUnavailable, busy, onBack, onAnswer, list,
            bottom = if (undoable != null) UNDO_BAR_ROOM else 0.dp,
            acceptAll = acceptAll,
            onAcceptAll = onAcceptAll,
        )
        if (undoable != null) {
            UndoBar(undoable, enabled = busy == null, onUndo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Cards(
    cards: List<ReviewCard>?,
    storageUnavailable: Boolean,
    busy: Operation?,
    onBack: () -> Unit,
    onAnswer: (ReviewCard, ReviewAnswer) -> Unit,
    list: LazyListState,
    bottom: Dp,
    acceptAll: AcceptAll?,
    onAcceptAll: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom + CARD_GAP)) {
        item {
            val offer = acceptAll.takeIf { !storageUnavailable && !cards.isNullOrEmpty() }
            Header(cards?.size, onBack, offer, enabled = busy == null, onAcceptAll)
        }
        if (busy != null) item { Notice(busyLine(busy, Withheld.ANSWERED), Stamp) }
        when {
            // First, for the reason `DayScreenContent` gives: stale cards
            // under a ledger that cannot be read would be answers to nothing.
            storageUnavailable -> item { Notice(CANNOT_READ_YOUR_DATA, Stamp) }
            cards == null -> item { Notice("READING", Muted) }
            cards.isEmpty() -> item { Notice(NOTHING_AWAITING_REVIEW, Muted) }
            else -> {
                itemsIndexed(cards, key = { _, card -> "review-" + card.txn.id }) { index, card ->
                    // The board's list: 14 above the first card, 12 between, 16 at the sides.
                    val margin = PaddingValues(start = 16.dp, end = 16.dp, top = if (index == 0) 14.dp else CARD_GAP)
                    SwipeToAccept(card, margin, enabled = busy == null, onAccept = { onAnswer(card, ReviewAnswer.ACCEPT) }) {
                        ReviewCardView(card, margin, enabled = busy == null, onAnswer = { onAnswer(card, it) })
                    }
                }
            }
        }
    }
}

/**
 * The snackbar: what the last answer did, and Undo. Disabled with the answers
 * while a restore or a delete runs.
 */
@Composable
private fun UndoBar(undoable: Undoable, enabled: Boolean, onUndo: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Ink, RoundedCornerShape(2.dp))
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(undoable.said, style = MonoLabel, color = Paper, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .clickable(enabled = enabled, role = Role.Button, onClick = onUndo)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(UNDO, style = ButtonLabel.copy(fontSize = 14.sp), color = if (enabled) Paper else Muted)
        }
    }
}

/**
 * The chevron inline with the title and the count beneath it, and Accept all
 * at the right when [acceptAll] is given: on Paper, above a dotted rule.
 *
 * The chevron is drawn at the board's 20dp inside a 44dp target, as
 * `SourcesScreen`'s is; the title is pulled 1dp into that target so the
 * drawn chevron sits the board's 11dp before it.
 *
 * A FlowRow, so Accept all drops under the title, still at the right, when
 * the two do not fit one line: the board's label is "ACCEPT ALL", and ours
 * carries the count and the net, which at CI's 320dp broke the title in two.
 */
@Composable
private fun Header(count: Int?, onBack: () -> Unit, acceptAll: AcceptAll?, enabled: Boolean, onAcceptAll: () -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .testTag(REVIEW_HEADER)
            .background(Paper)
            .dottedRule()
            .padding(start = 8.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.align(Alignment.Bottom)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .clickable(role = Role.Button, onClick = onBack)
                        .size(44.dp)
                        .semantics { contentDescription = REVIEW_BACK },
                    contentAlignment = Alignment.Center,
                ) {
                    Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
                }
                Text(
                    INBOX_TITLE,
                    fontFamily = Display,
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    color = Ink,
                    modifier = Modifier.offset(x = (-1).dp),
                )
            }
            if (count != null && count > 0) {
                // Muted rather than the board's Faint, which `Color.kt` keeps off text.
                Text(
                    awaitingReviewLabel(count),
                    style = MonoLabel,
                    color = Muted,
                    modifier = Modifier.padding(start = 43.dp, top = 3.dp),
                )
            }
        }
        if (acceptAll != null) {
            Box(Modifier.weight(1f).align(Alignment.Bottom), contentAlignment = Alignment.BottomEnd) {
                AcceptAllOffer(acceptAll, enabled, onAcceptAll)
            }
        }
    }
}

/**
 * One card: the notification as Pinged saw it, Pinged's reading of it, why
 * it is held, and the answers its [ReviewCard.kind] offers.
 */
@Composable
private fun ReviewCardView(card: ReviewCard, margin: PaddingValues, enabled: Boolean, onAnswer: (ReviewAnswer) -> Unit) {
    val txn = card.txn
    Column(
        Modifier
            .fillMaxWidth()
            .padding(margin)
            .testTag(reviewSurfaceTag(txn.id))
            .background(Paper, RoundedCornerShape(2.dp))
            .border(1.dp, Border, RoundedCornerShape(2.dp))
            // The board's 13 by 14, inside its 1px border.
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        // The board's caps label at MonoLabel's 12sp floor rather than its 9.5.
        Text(reasonSentence(txn.pendingReason), style = MonoLabel, color = Stamp)
        if (card.kind == CardKind.TRANSFER) {
            Text(
                transferQuestion(card),
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                color = Ink,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Verbatim: §9.2's hard requirement is that the user can see exactly
        // what Pinged saw. No trimming, no joining into one string. In the
        // mono, which is how spec 8 marks text quoted word for word; at the
        // 12sp floor rather than the artboard's 10.5, for [MonoLabel]'s reason.
        if (card.title != null || card.body != null) {
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .testTag(VERBATIM)
                    .background(Quote, RoundedCornerShape(2.dp))
                    .dottedOutline()
                    .padding(10.dp),
            ) {
                card.title?.let { Text(it, style = Quoted, color = Muted) }
                card.body?.let { Text(it, style = Quoted, color = Muted) }
            }
        }

        Column(Modifier.padding(top = 12.dp)) {
            Reading("AMOUNT", amountOf(card))
            Reading("MERCHANT", card.merchant ?: "No merchant")
            Reading("CATEGORY", card.category ?: "None")
            Reading("SOURCE", txn.sourceLabel ?: txn.sourcePackage ?: "Entered by hand")
            Reading("TIME", timeOf(card))
        }

        if (card.kind == CardKind.DUPLICATE) card.pair?.let { SideBySide(txn, it) }

        // The first two answers as the board's equal halves; a transfer
        // card's third gets a row of its own below them, since thirds at
        // 320dp (about 80dp each) break "Keep as expense" across two lines.
        val answers = card.kind.answers
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                answers.take(2).forEachIndexed { index, answer ->
                    AnswerButton(answer.label, primary = index == 0, enabled = enabled, Modifier.weight(1f).fillMaxHeight()) { onAnswer(answer) }
                }
            }
            answers.drop(2).forEach { answer ->
                AnswerButton(answer.label, primary = false, enabled = enabled, Modifier.fillMaxWidth()) { onAnswer(answer) }
            }
        }
    }
}

/**
 * A duplicate card's two rows (§9.2), each with its source, time and amount,
 * headed by what Merge does to it: the row awaiting review is the one it
 * dismisses, its pair the one it keeps.
 */
@Composable
private fun SideBySide(held: Txn, pair: Txn) {
    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple(held, "DISMISSED BY MERGE", DUPLICATE_HELD), Triple(pair, "KEPT BY MERGE", DUPLICATE_PAIR))
            .forEach { (row, heading, tag) ->
                Column(
                    Modifier
                        .weight(1f)
                        .testTag(tag)
                        .border(1.dp, Rule, RoundedCornerShape(2.dp))
                        .padding(10.dp),
                ) {
                    Text(heading, style = MonoLabel, color = Muted)
                    listOf(
                        row.sourceLabel ?: row.sourcePackage ?: "Entered by hand",
                        timeOf(ReviewCard(row)),
                        amountOf(ReviewCard(row)),
                    ).forEach {
                        Text(it, fontFamily = Body, fontSize = 14.sp, lineHeight = 19.sp, color = Ink, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
    }
}

/** One line of Pinged's reading: a mono label, then the value. */
@Composable
private fun Reading(label: String, value: String) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MonoLabel, color = Muted, modifier = Modifier.width(88.dp))
        Text(value, fontFamily = Body, fontSize = 14.sp, lineHeight = 19.sp, color = Ink)
    }
}

/**
 * A card's answer. The first a card offers is filled in Stamp; the rest are
 * outlined in Border and lettered in Ink, since Stamp is "this needs you" and
 * the board gives it to the reason line and the primary answer alone.
 * Disabled rather than hidden while [enabled] is false, so the card does not
 * change shape under a thumb when a restore starts.
 *
 * 8dp at the sides, not more: at CI's 320dp a half is 125dp and "Keep as
 * expense" is 106dp of it.
 */
@Composable
private fun AnswerButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .then(
                if (primary) Modifier.background(if (enabled) Stamp else Muted)
                else Modifier.border(1.dp, if (enabled) Border else Rule, shape),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (primary) Modifier.semantics { stateDescription = DEFAULT_ANSWER } else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ButtonLabel.copy(fontSize = 14.sp, textAlign = TextAlign.Center),
            color = when {
                primary -> Paper
                enabled -> Ink
                else -> Muted
            },
        )
    }
}

/** The notification as Pinged saw it: mono, without [MonoLabel]'s tracking, at its 1.5 leading. */
private val Quoted = MonoLabel.copy(letterSpacing = 0.sp, lineHeight = 18.sp)

@Composable
private fun Notice(text: String, color: Color) {
    Text(
        text,
        style = MonoLabel,
        color = color,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
    )
}

/** `RM650.00`, signed `+` for a refund as the list signs it. */
internal fun amountOf(card: ReviewCard): String =
    (if (card.txn.direction == Direction.REFUND) "+" else "") + money(card.txn.amountSen, card.txn.currency, symbol = true)

/** `3 September 2026, 14:05`: the row's own day, and the clock time here. */
internal fun timeOf(card: ReviewCard, zone: ZoneId = ZoneId.systemDefault()): String =
    dayMonthYear(LocalDates.calendarDay(card.txn.localDate)) + ", " +
        CLOCK.format(Instant.ofEpochMilli(card.txn.occurredAt).atZone(zone))

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * A transfer card's question (§7.3): `Touch 'n Go eWallet RM100.00: leave
 * out of spending?`. The merchant when Pinged read one, else the source.
 */
internal fun transferQuestion(card: ReviewCard): String {
    val who = card.merchant ?: card.txn.sourceLabel ?: card.txn.sourcePackage ?: "This payment"
    return "$who ${amountOf(card)}: leave out of spending?"
}

/**
 * What TalkBack says of a card's primary button, the answer drawn filled:
 * the fill is the only other sign of it. Leave out on a transfer card.
 */
const val DEFAULT_ANSWER = "Default answer"

/** `3 AWAITING REVIEW`: the Spending list's pinned row and this screen's count. */
fun awaitingReviewLabel(count: Int): String = "$count AWAITING REVIEW"

/** An inbox with nothing in it, which stays open. */
const val NOTHING_AWAITING_REVIEW = "NOTHING AWAITING REVIEW"

/** The snackbar's button. */
const val UNDO = "Undo"

/** Between two cards, and under the last: the board's list gap. */
private val CARD_GAP = 12.dp

/** Room under the last card for the snackbar, so it never covers a card's buttons. */
private val UNDO_BAR_ROOM = 80.dp

/** The chevron's description, and what a test finds it by. */
const val REVIEW_BACK = "Back from the review inbox"

/**
 * Accept all (§9.2): every batchable card in one tap, named by its count and its
 * ringgit net. No confirm: one Undo takes the whole batch back.
 *
 * Drawn as an offer, the board's dashed Muted box, so it does not compete with
 * the answers' Stamp; lettered as a button (spec 8, #180). The 44dp target is
 * the whole box's height; the dashes are drawn at the board's 32.
 */
@Composable
private fun AcceptAllOffer(acceptAll: AcceptAll, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Muted else Rule
    Box(
        Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .dashedOutline(ink)
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Faint only while disabled, which WCAG 1.4.3 exempts.
            Text(acceptAll.label, style = ButtonLabel.copy(fontSize = 14.sp), color = if (enabled) Muted else Faint)
        }
    }
}

/**
 * A swipe to the right accepts [card] when it is batchable ([isBatchable]);
 * any other card is drawn as it is, with nothing to swipe. Off while
 * [enabled] is false, as the buttons are.
 *
 * Keyed on the card and [enabled], so a swipe whose answer wrote nothing --
 * the row moved and re-read as something else, or a restore started under
 * the thumb -- draws the card again rather than leaving it swiped away.
 */
@Composable
private fun SwipeToAccept(card: ReviewCard, margin: PaddingValues, enabled: Boolean, onAccept: () -> Unit, content: @Composable () -> Unit) {
    val tagged = Modifier.testTag(reviewCardTag(card.txn.id))
    if (!card.isBatchable) {
        Box(tagged) { content() }
        return
    }
    key(card, enabled) {
        val state = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(
            state = state,
            modifier = tagged,
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = false,
            gesturesEnabled = enabled,
            onDismiss = { if (it == SwipeToDismissBoxValue.StartToEnd) onAccept() },
            backgroundContent = {
                // Only under a swipe, and out of the semantics tree: a second
                // "ACCEPT" behind every card is not a button anyone can press.
                if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics {}
                            .padding(margin)
                            .background(Stamp, RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(ReviewAnswer.ACCEPT.label.uppercase(), style = MonoLabel, color = Paper, modifier = Modifier.padding(start = 20.dp))
                    }
                }
            },
        ) { content() }
    }
}

/** A card's test tag, which a swipe is aimed at. */
fun reviewCardTag(txnId: Long): String = "review-card-$txnId"

/** The painted card itself, inside [reviewCardTag]'s swipe wrapper. */
fun reviewSurfaceTag(txnId: Long): String = "review-surface-$txnId"

/** The screen's title. */
const val INBOX_TITLE = "Awaiting review"

/** The header: the chevron, the title, the count and Accept all. */
const val REVIEW_HEADER = "review-header"

/** The box that quotes a card's notification word for word. */
internal const val VERBATIM = "review-verbatim"

/** The test tag of a duplicate card's column for the row awaiting review. */
const val DUPLICATE_HELD = "duplicate-held"

/** The test tag of a duplicate card's column for its pair. */
const val DUPLICATE_PAIR = "duplicate-pair"
