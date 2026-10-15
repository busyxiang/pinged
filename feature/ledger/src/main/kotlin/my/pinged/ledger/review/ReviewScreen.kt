package my.pinged.ledger.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedOutline
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
 * [onAcceptAll] is the Accept all button, drawn above the cards while any is
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
        // Items above the cards: the header, the busy line and Accept all when drawn.
        if (at >= 0) list.scrollToItem(1 + (if (busy != null) 1 else 0) + (if (acceptAll != null) 1 else 0) + at)
    }
    Box(modifier.fillMaxSize().background(Paper)) {
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
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottom)) {
        item { Header(cards?.size, onBack) }
        if (busy != null) item { Notice(busyLine(busy, Withheld.ANSWERED), Stamp) }
        when {
            // First, for the reason `DayScreenContent` gives: stale cards
            // under a ledger that cannot be read would be answers to nothing.
            storageUnavailable -> item { Notice(CANNOT_READ_YOUR_DATA, Stamp) }
            cards == null -> item { Notice("READING", Muted) }
            cards.isEmpty() -> item { Notice(NOTHING_AWAITING_REVIEW, Muted) }
            else -> {
                if (acceptAll != null) item { AcceptAllButton(acceptAll, enabled = busy == null, onAcceptAll) }
                items(cards, key = { "review-" + it.txn.id }) { card ->
                    SwipeToAccept(card, enabled = busy == null, onAccept = { onAnswer(card, ReviewAnswer.ACCEPT) }) {
                        ReviewCardView(card, enabled = busy == null, onAnswer = { onAnswer(card, it) })
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

/** The chevron, the title and the count. */
@Composable
private fun Header(count: Int?, onBack: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp, top = 12.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onBack)
            .size(44.dp)
            .semantics { contentDescription = REVIEW_BACK },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
    }
    Text(
        "Awaiting review",
        fontFamily = Display,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        color = Ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
    if (count != null && count > 0) {
        Text(
            awaitingReviewLabel(count),
            style = MonoLabel,
            color = Stamp,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp),
        )
    }
}

/**
 * One card: the notification as Pinged saw it, Pinged's reading of it, why
 * it is held, and the answers its [ReviewCard.kind] offers.
 */
@Composable
private fun ReviewCardView(card: ReviewCard, enabled: Boolean, onAnswer: (ReviewAnswer) -> Unit) {
    val txn = card.txn
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Card, RoundedCornerShape(2.dp))
            .padding(16.dp),
    ) {
        Text(reasonSentence(txn.pendingReason), fontFamily = Body, fontSize = 15.sp, lineHeight = 20.sp, color = Stamp)
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

        // A FlowRow: a transfer card's three labels do not fit one line at
        // phone width, and a Row would squeeze the last into a sliver.
        FlowRow(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            card.kind.answers.forEachIndexed { index, answer ->
                AnswerButton(answer.label, primary = index == 0, enabled = enabled) { onAnswer(answer) }
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
 * A card's answer. The first a card offers is drawn filled, the rest outlined.
 * Disabled rather than hidden while [enabled] is false, so the card does not
 * change shape under a thumb when a restore starts.
 */
@Composable
private fun AnswerButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Stamp else Muted
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .then(if (primary) Modifier.background(ink) else Modifier.border(1.dp, ink, RoundedCornerShape(2.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (primary) Modifier.semantics { stateDescription = DEFAULT_ANSWER } else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 14.sp), color = if (primary) Paper else ink)
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

/** Room under the last card for the snackbar, so it never covers a card's buttons. */
private val UNDO_BAR_ROOM = 80.dp

/** The chevron's description, and what a test finds it by. */
const val REVIEW_BACK = "Back from the review inbox"

/**
 * Accept all (§9.2): every batchable card in one tap, named by its count and its
 * ringgit net. No confirm: one Undo takes the whole batch back.
 */
@Composable
private fun AcceptAllButton(acceptAll: AcceptAll, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        AnswerButton(acceptAll.label, primary = true, enabled = enabled, onClick = onClick)
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
private fun SwipeToAccept(card: ReviewCard, enabled: Boolean, onAccept: () -> Unit, content: @Composable () -> Unit) {
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
                            .padding(horizontal = 16.dp, vertical = 8.dp)
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

/** The box that quotes a card's notification word for word. */
internal const val VERBATIM = "review-verbatim"

/** The test tag of a duplicate card's column for the row awaiting review. */
const val DUPLICATE_HELD = "duplicate-held"

/** The test tag of a duplicate card's column for its pair. */
const val DUPLICATE_PAIR = "duplicate-pair"
