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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.data.LocalDate
import my.pinged.ledger.notice
import my.pinged.ledger.refused
import my.pinged.ledger.withholding
import my.pinged.parse.TaughtTemplate
import my.pinged.read.LedgerAccess
import my.pinged.read.rememberReadState
import my.pinged.data.LocalDates
import my.pinged.data.Seed
import my.pinged.data.dao.BillChoice
import my.pinged.data.dao.DetailRow
import my.pinged.data.dao.FeedRow
import my.pinged.ledger.bill.PaidBackFooter
import my.pinged.ledger.bill.PaidBackList
import my.pinged.ledger.bill.onTheirOwnLine
import my.pinged.ledger.home.RowOpenings
import my.pinged.ledger.bill.BillChooser
import my.pinged.ledger.bill.BillFor
import my.pinged.ledger.bill.BillLabel
import my.pinged.ledger.bill.BillOffer
import my.pinged.ledger.bill.billDay
import my.pinged.ledger.bill.billLabel
import my.pinged.ledger.bill.shortDay
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ChangedLine
import my.pinged.ledger.home.CategoryActions
import my.pinged.ledger.home.EDIT_MERCHANT
import my.pinged.ledger.home.HeroSerif
import my.pinged.ledger.home.MerchantActions
import my.pinged.ledger.home.MerchantOpening
import my.pinged.ledger.home.RemoveSheet
import my.pinged.ledger.home.rememberRowHost
import my.pinged.ledger.home.UnderlinedField
import my.pinged.ledger.home.notSpendingBadge
import my.pinged.parse.Direction
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.DashLeader
import my.pinged.ui.theme.DottedCell
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ledger.home.HERO_NOT_READ_YET
import androidx.compose.ui.text.TextStyle
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Quote
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.ValueDash
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule
import my.pinged.ui.theme.heroFigure
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
 * An excluded row is drawn as `design/DetailExcluded.dc.html` (#229): the
 * eyebrow, hero, badge, Category row and small print all say it is in no
 * total, and offers Count as spending, filled, over Delete.
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
    openings: RowOpenings = RowOpenings.None,
) {
    // Reads on each resume and on `Databases.rewrites`, through `rememberReadState`.
    val read = viewModel.read.rememberReadState()
    val unlinked by viewModel.unlinked.collectAsState()
    DetailScreenContent(
        read = read.answer,
        ledger = read.ledger,
        onBack = onBack,
        categories = viewModel.rows,
        merchants = viewModel.rows,
        actions = remember(viewModel, onBack, openings) {
            DetailActions(
                saveNote = { viewModel.saveNote(it) },
                notSpending = { viewModel.notSpending() },
                countAsSpending = { viewModel.countAsSpending() },
                remove = { viewModel.remove(then = onBack) },
                bills = viewModel::bills,
                pickBill = { viewModel.pickBill(it) },
                notAPayback = { viewModel.notAPayback() },
                undo = { viewModel.undo() },
                openPayback = openings::open,
                addCashPayback = { openings.addCashPayback(viewModel.txnId) },
            )
        },
        modifier = modifier,
        unlinked = unlinked != null,
    )
}

/**
 * The screen's own writes, beside the row's halves `DetailScreenContent` is
 * handed apart; [None] for a test that is not about them.
 */
internal class DetailActions(
    val saveNote: (String) -> Unit,
    val notSpending: () -> Unit,
    val countAsSpending: () -> Unit,
    val remove: () -> Unit,
    /** What the bill chooser lists; see [DetailViewModel.bills]. */
    val bills: suspend (BillFor, String) -> BillOffer? = { _, _ -> null },
    /** The chooser's pick, null for No bill. */
    val pickBill: (Long?) -> Unit = {},
    val notAPayback: () -> Unit = {},
    /** Not a payback's Undo. */
    val undo: () -> Unit = {},
    /** A bill's payback's row: its own screen, as the list opens it. */
    val openPayback: (FeedRow) -> Unit = {},
    /** A bill's **Add a cash payback** (spec 7.4). */
    val addCashPayback: () -> Unit = {},
) {
    companion object {
        val None = DetailActions({}, {}, {}, {})
    }
}

/**
 * [DetailScreen] without its holder, so a test can draw any state.
 *
 * While [ledger] withholds for a ledger replacement the row says so at its top, and its
 * own writes are off (#331): Save note, Not spending, Count as spending and
 * Delete, and the confirm of a Not a payment sheet already open. Change
 * category and the pencil still open their sheets, which say so and turn
 * their own writes off.
 *
 * A refund offers spec 7.4's bill chooser (`design/DetailRefund.dc.html`,
 * `DetailPayback`): from `+ BILL`, or a payback's Pays back chip. [unlinked]
 * is a Not a payback whose Undo is on offer, drawn as a bar at the foot.
 */
@Composable
internal fun DetailScreenContent(
    read: DetailRead?,
    /** What the screen may do with the ledger: a refusal draws its notice in place of anything read (#328, #376). */
    ledger: LedgerAccess,
    onBack: () -> Unit,
    categories: CategoryActions,
    merchants: MerchantActions,
    modifier: Modifier = Modifier,
    actions: DetailActions = DetailActions.None,
    zone: ZoneId = ZoneId.systemDefault(),
    unlinked: Boolean = false,
) {
    val busy = ledger.withholding
    var removing by rememberSaveable { mutableStateOf(false) }
    var choosingBill by rememberSaveable { mutableStateOf(false) }
    val row = read?.row
    val host = rememberRowHost(
        chooser = read?.chooser,
        findRow = { id -> row?.row?.takeIf { it.txn.id == id } },
        categories = categories,
        merchants = merchants,
        // Detail offers no New category tile; whether it should is undecided (#307).
        offerNewCategory = false,
    )

    Box(modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = if (unlinked) UNDO_ROOM else 0.dp)) {
            Header(onBack)
            when {
                // First: a row drawn under a ledger that cannot be read would be a
                // claim about money no read supports.
                ledger.refused -> Notice(ledger.notice.orEmpty(), Stamp)
                read == null -> NotReadYet()
                row == null -> Notice(GONE, Muted)
                else -> {
                    busy?.let { ChangedLine(it) }
                    DetailBody(
                        row = row,
                        bill = read.bill,
                        paybacks = read.paybacks,
                        zone = zone,
                        // A payback is filed by its bill: Not a payback stands
                        // where Change category would (spec 9.7).
                        canCategorize = host.canCategorize && !row.row.txn.isExcluded && read.bill == null,
                        onCategorize = { host.categorize(row.row.txn.id) },
                        onChooseBill = { choosingBill = true },
                        onRemove = { removing = true },
                        actions = actions,
                        merchants = merchants,
                        writable = busy == null,
                    )
                }
            }
        }
        if (unlinked) {
            UndoBar(NOT_A_PAYBACK_SAID, enabled = busy == null, onUndo = actions.undo, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    val payback = row?.row
    if (choosingBill && payback != null && busy == null && offersBill(payback.txn)) {
        val subject = BillFor(
            payback.displayName ?: payback.txn.merchantRaw ?: NO_MERCHANT,
            payback.txn.amountSen,
            payback.txn.currency,
            LocalDates.calendarDay(payback.txn.localDate),
        )
        BillChooser(
            payback = subject,
            current = read.bill?.txn?.id,
            offer = { query -> actions.bills(subject, query) },
            onPick = { billId ->
                choosingBill = false
                if (billId != read.bill?.txn?.id) actions.pickBill(billId)
            },
            onDismiss = { choosingBill = false },
        )
    }

    if (removing && row != null) {
        RemoveSheet(
            row = row.row,
            paybacks = read.paybacks,
            onConfirm = {
                removing = false
                actions.remove()
            },
            onDismiss = { removing = false },
            enabled = busy == null,
        )
    }
    host.Sheets()
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

/**
 * The row's read in flight: `design/LoadingDetail.dc.html`, or
 * `LoadingWaiting.dc.html` while an export or the integrity check holds the
 * database. The eyebrow and the fields' labels are drawn, every value a dash
 * on a dotted leader; the merchant is a dotted rule and the notification a
 * dotted box, since what they say is the read.
 */
@Composable
private fun NotReadYet() {
    ReadingLayout(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
                // The board's PAID is a dash: PAID or a refund's CAME BACK
                // is the row's direction, which is a read.
                ValueDash(style = MonoLabel)
                // Rule, decorative, as Spending's hero dash is: the status
                // line under it says the amount is not read yet.
                Text(HERO_NOT_READ_YET, style = HeroSerif, color = Rule, modifier = Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.55f).height(18.dp).dottedRule())
                ReadingStatus(Modifier.padding(top = 6.dp))
            }
            TornEdge(Modifier.padding(top = 16.dp))
            Column(Modifier.fillMaxWidth().background(Card).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (label in listOf(WHEN, CATEGORY, CAME_FROM, NAME_ON_THE_BILL)) {
                        DashLeader(Modifier.heightIn(min = 18.dp), label = label, labelStyle = FieldLabel)
                    }
                }
                Text(WHAT_PINGED_READ, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 22.dp))
                DottedCell(Modifier.padding(top = 9.dp).fillMaxWidth().height(54.dp).testTag(VERBATIM_NOT_READ_YET))
            }
        }
    }
}

@Composable
private fun DetailBody(
    row: DetailRow,
    bill: BillChoice?,
    paybacks: List<FeedRow>,
    zone: ZoneId,
    canCategorize: Boolean,
    onCategorize: () -> Unit,
    onChooseBill: () -> Unit,
    onRemove: () -> Unit,
    actions: DetailActions,
    merchants: MerchantActions,
    writable: Boolean,
) {
    val txn = row.row.txn
    val name = row.row.displayName
    val excluded = txn.isExcluded
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
        val paid = when {
            // Charts' came back never holds a payback (spec 9.3), so neither does its eyebrow.
            bill != null -> PAID_BACK
            txn.direction == Direction.REFUND -> CAME_BACK
            else -> PAID
        }
        Text(if (excluded) paid + Separator + NOT_COUNTED else paid, style = MonoLabel, color = Muted)
        Text(
            detailHero(money(txn.amountSen, txn.currency, symbol = true), counted = !excluded),
            style = HeroSerif,
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
                        .clickable(role = Role.Button) { merchants.openMerchant(MerchantOpening(key, name ?: key, row.row.txn.localDate)) }
                        .size(44.dp)
                        .semantics { contentDescription = EDIT_MERCHANT },
                    contentAlignment = Alignment.Center,
                ) { Pencil() }
            }
        }
        if (excluded) {
            // The board's 9.5px badge at MonoLabel's 12sp floor, and its text
            // in Muted: Faint, the board's edge, is 3.55:1 on Paper, short of
            // text's 4.5:1.
            Text(
                notSpendingBadge(txn.exclusionReason),
                style = MonoLabel,
                color = Muted,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .border(1.dp, Faint, RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }

    TornEdge(Modifier.padding(top = 16.dp))
    Column(Modifier.fillMaxWidth().background(Card).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(WHEN, whenOf(txn, zone))
            if (offersBill(txn) || bill != null) {
                Field(PAYS_BACK_FIELD) {
                    val open = onChooseBill.takeIf { offersBill(txn) && writable }
                    if (bill != null) PaysBackChip(bill, open) else AddBillChip(open)
                }
            }
            if (bill?.counts == true) {
                // The bill's, as plain text: it changes only by changing the bill's.
                Field(COUNTS_AGAINST_FIELD) {
                    Text(
                        countsAgainstValue(bill),
                        fontFamily = Body,
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        color = Ink,
                        textAlign = TextAlign.End,
                        modifier = Modifier.testTag(COUNTS_AGAINST_VALUE),
                    )
                }
            } else Field(CATEGORY) {
                // The category an excluded row keeps is filed nowhere while it
                // is out, so the row says that instead of naming it. Muted
                // where the board has Faint, which is 3.12:1 on Card.
                if (excluded) {
                    Text(NOT_COUNTED_WHILE_LEFT_OUT, fontFamily = Body, fontSize = 12.5.sp, lineHeight = 16.sp, color = Muted)
                } else if (row.categoryName == Seed.UNCATEGORIZED && canCategorize) {
                    AddCategoryChip(onCategorize)
                } else {
                    CategoryChip(row.categoryName ?: NO_CATEGORY, onCategorize.takeIf { canCategorize })
                }
            }
            Field(CAME_FROM, (txn.sourceLabel ?: txn.sourcePackage ?: "").uppercase(CALENDAR))
            // The raw acquirer string, only where it says something the name
            // above does not: KK MART under KK Mart says nothing.
            txn.merchantRaw?.takeIf { !it.equals(name, ignoreCase = true) }?.let { Field(NAME_ON_THE_BILL, it) }
        }
        if (bill?.counts == true) {
            // Why the day it arrived does not count it (`design/DetailPayback.dc.html`).
            Text(
                countedOnLine(txn, bill),
                style = MonoLabel.copy(lineHeight = 18.sp),
                color = Muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (paybacks.isNotEmpty()) {
            // The hero stays the bank's amount; the share is a fact about the
            // ledger, so it is here, under its paybacks (`design/DetailBill.dc.html`).
            // A bill that counts nowhere cannot be picked, so it offers no
            // cash payback, and says where its paybacks count instead
            // (`DetailBillExcluded`).
            val counts = row.row.counts
            PaidBackList(
                paybacks = paybacks,
                onOpen = actions.openPayback,
                onAdd = actions.addCashPayback.takeIf { counts && writable },
                footer = if (counts) {
                    PaidBackFooter.Share(txn.amountSen - row.row.paidBackSen, txn.currency)
                } else {
                    PaidBackFooter.OnTheirOwn(onTheirOwnLine(paybacks))
                },
                modifier = Modifier.padding(top = 22.dp),
            )
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

        NoteField(txn, actions.saveNote, writable)
        if (excluded) {
            Text(
                LEFT_OUT_SMALL_PRINT,
                style = MonoLabel.copy(lineHeight = 18.sp),
                color = Muted,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }

    Actions(txn, canCategorize, onCategorize, isPayback = bill != null, onRemove, actions, writable)
}

/**
 * The note: one line, saved by the keyboard's Done or by Save note, which is
 * drawn only while the line differs from what is stored. Seeded from the row
 * and re-seeded when a re-read brings a different note. Neither saves unless
 * [writable]; what was typed is kept.
 */
@Composable
private fun NoteField(txn: Txn, save: (String) -> Unit, writable: Boolean) {
    val stored = txn.note.orEmpty()
    var typed by rememberSaveable(txn.id, stored) { mutableStateOf(stored) }
    val focus = LocalFocusManager.current
    val dirty = typed.trim() != stored
    val commit = {
        focus.clearFocus()
        if (dirty && writable) save(typed)
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
            OutlinedAction(SAVE_NOTE, Ink, Border, Modifier.padding(top = 10.dp).fillMaxWidth(), enabled = writable, onClick = commit)
        }
    }
}

/**
 * The band at the foot: Change category beside Not spending, and Delete this
 * entry under them, each a full 44dp. A payback ([isPayback]) has Not a
 * payback in Change category's place (`design/DetailPayback.dc.html`). A row the user left out has Count as
 * spending in their place, filled, as `design/DetailExcluded.dc.html` draws it.
 *
 * Each is offered only where it can write, as `LedgerRow` gates its own
 * controls: the chooser not on an excluded row, which is in no total;
 * Not spending and Delete only on a committed row, since a held one is the
 * inbox's to answer; and Count as spending on any excluded captured row,
 * whoever left it out -- the user, the inbox's Leave out or a standing answer
 * (spec 7.3) -- as `TxnDao.countAsSpending` writes. All but Change category
 * are off unless [writable].
 */
@Composable
private fun Actions(
    txn: Txn,
    canCategorize: Boolean,
    onCategorize: () -> Unit,
    isPayback: Boolean,
    onRemove: () -> Unit,
    actions: DetailActions,
    writable: Boolean,
) {
    val committed = txn.state == TxnState.COMMITTED
    val countBack = committed && txn.isExcluded && txn.rawCaptureId != null
    val pair = buildList {
        if (canCategorize) add(Triple(CHANGE_CATEGORY, true, onCategorize))
        if (isPayback) add(Triple(NOT_A_PAYBACK, writable, actions.notAPayback))
        if (committed && !txn.isExcluded) add(Triple(NOT_SPENDING, writable, actions.notSpending))
    }
    if (pair.isEmpty() && !committed) return
    Column(
        Modifier
            .fillMaxWidth()
            .dottedRule(atTop = true)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (countBack) FilledAction(COUNT_AS_SPENDING, Modifier.fillMaxWidth(), writable, actions.countAsSpending)
        if (pair.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                pair.forEach { (label, enabled, onClick) ->
                    OutlinedAction(label, Ink, Border, Modifier.weight(1f).fillMaxHeight(), enabled, onClick)
                }
            }
        }
        if (committed) OutlinedAction(DELETE, Stamp, Stamp, Modifier.fillMaxWidth(), writable, onRemove)
    }
}

/**
 * The board's primary action: filled [Stamp], its label in [Paper]; off,
 * outlined in [Muted], label too, as the chooser's Save is.
 */
@Composable
private fun FilledAction(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .then(if (enabled) Modifier.background(Stamp, shape) else Modifier.border(1.dp, Muted, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 14.sp, textAlign = TextAlign.Center), color = if (enabled) Paper else Muted)
    }
}

/**
 * An outlined button of the band, its edge solid: Delete's too, in the
 * accent, as `design/Detail.dc.html` draws it, since `Perforation.kt` keeps
 * dashes for offers and a delete is not one. Off, edge and label in [Muted].
 */
@Composable
private fun OutlinedAction(label: String, ink: Color, edge: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, if (enabled) edge else Muted, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 14.sp, textAlign = TextAlign.Center), color = if (enabled) ink else Muted)
    }
}

/** One line of the card: the label, a dotted leader, the value in the mono. */
@Composable
private fun Field(label: String, value: String) {
    Field(label) { Text(value, style = MonoLabel.copy(letterSpacing = 0.sp), color = Ink, textAlign = TextAlign.End) }
}

/**
 * The label, a dotted leader, and [value] flush right after it, as the
 * board's `flex: 1` leader sets it. Only the leader is weighted: a value
 * weighted beside it took half the row, so each ended where its half did and
 * "TOUCH 'N GO EWALLET" wrapped at 320dp. A value too long for the line
 * squeezes the leader to nothing and wraps in what is left.
 */
@Composable
private fun Field(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label, style = FieldLabel, color = Muted)
        Box(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 4.dp).height(1.dp).dottedRule())
        value()
    }
}

/**
 * The board's category: [name] in a Stamp outline, opening the chooser where
 * the row can be filed ([onClick] non-null). Laid out at its drawn size, as a
 * ledger row's chip is (#186), with Compose's minimum touch target round it.
 */
@Composable
private fun CategoryChip(name: String, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(2.dp)
    Text(
        name,
        fontFamily = Body,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Stamp,
        modifier = Modifier
            .testTag(CATEGORY_CHIP)
            .clip(shape)
            .border(1.dp, Stamp, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * `design/DetailRefund.dc.html`'s `+ CATEGORY` on a row filed under
 * Uncategorized: the ledger's own chip, a dashed [Stamp] offer, since the
 * row is one to sort.
 */
@Composable
private fun AddCategoryChip(onClick: () -> Unit) {
    Text(
        ADD_CATEGORY,
        style = MonoLabel,
        color = Stamp,
        modifier = Modifier
            .testTag(CATEGORY_CHIP)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .dashedOutline(Stamp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * `+ BILL` on a committed refund that pays back nothing (spec 9.7): dashed
 * in [Muted], never [Stamp], since on most refunds it never needs an answer
 * and must not compete with `+ CATEGORY`, which does. Off while [onClick]
 * is null.
 */
@Composable
private fun AddBillChip(onClick: (() -> Unit)?) {
    Text(
        ADD_BILL,
        style = MonoLabel,
        color = Muted,
        modifier = Modifier
            .testTag(PAYS_BACK_CHIP)
            .clip(RoundedCornerShape(2.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .dashedOutline(Muted)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * A payback's Pays back (`design/DetailPayback.dc.html`): its bill, `Yuen Kee ·
 * 18 Sep`, in a [Stamp] outline as the category chip is drawn, reopening the
 * chooser to move it to another bill.
 */
@Composable
private fun PaysBackChip(bill: BillChoice, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .testTag(PAYS_BACK_CHIP)
            .clip(shape)
            .border(1.dp, Stamp, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        BillLabel(bill, Stamp, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/**
 * Not a payback's snackbar (spec 9.7), pinned to the foot with Undo, as the
 * review inbox draws its own: until Undo is used or the screen closes.
 */
@Composable
private fun UndoBar(said: String, enabled: Boolean, onUndo: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Ink, RoundedCornerShape(2.dp))
            .padding(start = 16.dp)
            .testTag(DETAIL_UNDO_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(said, style = MonoLabel, color = Paper, modifier = Modifier.weight(1f))
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

/** Whether [txn] offers a bill (spec 9.7): a committed refund. */
internal fun offersBill(txn: Txn): Boolean = txn.direction == Direction.REFUND && txn.state == TxnState.COMMITTED

/** `Food & Drinks · Yuen Kee · 18 Sep`: the category, merchant and day a payback counts under. */
internal fun countsAgainstValue(bill: BillChoice): String =
    (bill.categoryName ?: NO_CATEGORY) + Separator + billLabel(bill)

/** `IN THE LIST ON 20 SEP · COUNTED ON 18 SEP`: why the day [txn] arrived does not count it. */
internal fun countedOnLine(txn: Txn, bill: BillChoice): String =
    "IN THE LIST ON " + shortDay(LocalDates.calendarDay(txn.localDate)).uppercase(CALENDAR) + Separator +
        "COUNTED ON " + shortDay(billDay(bill)).uppercase(CALENDAR)

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
internal fun provenance(row: DetailRow): String =
    provenance(row.matchedRuleId, row.packVersion, row.row.txn.userEdited)

/**
 * [provenance] from its three facts. A rule the user taught (spec 5.8) is
 * said to be theirs, and names no pack: stage two records the pack loaded
 * beside it, which had no part in reading it.
 */
internal fun provenance(ruleId: String?, packVersion: Int?, edited: Boolean): String {
    val taught = TaughtTemplate.storedIdOf(ruleId) != null
    return listOfNotNull(
        if (taught) "A RULE YOU TAUGHT" else ruleId?.let { "RULE $it" },
        packVersion?.takeIf { !taught }?.let { "PACK $it" },
        if (edited) "EDITED" else "NOT EDITED",
    ).joinToString(" · ")
}

/**
 * The board's hero figure, its colours carried in the string: Ink with Muted
 * sen for a counted row, Muted with Faint sen for one in no total. The sen are
 * 30sp, large text, for which Faint's 3.55:1 on Paper clears the 3:1 floor.
 */
internal fun detailHero(amount: String, counted: Boolean): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = if (counted) Ink else Muted)) {
        append(heroFigure(amount, 30.sp, senColour = if (counted) Muted else Faint))
    }
}

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

/** A field's label, read or not. */
private val FieldLabel = TextStyle(fontFamily = Body, fontSize = 13.5.sp, lineHeight = 18.sp)

/** The dotted box where the notification will be quoted, as a device test finds it. */
internal const val VERBATIM_NOT_READ_YET = "detail-verbatim-not-read-yet"

internal const val EYEBROW = "CAPTURED, NOT TYPED"
internal const val PAID = "PAID"
internal const val CAME_BACK = "CAME BACK"
internal const val PAID_BACK = "PAID BACK"
internal const val PAYS_BACK_FIELD = "Pays back"
internal const val COUNTS_AGAINST_FIELD = "Counts against"
internal const val ADD_BILL = "+ BILL"
internal const val ADD_CATEGORY = "+ CATEGORY"
internal const val NOT_A_PAYBACK = "Not a payback"
internal const val NOT_A_PAYBACK_SAID = "NOT A PAYBACK"
internal const val UNDO = "Undo"
internal const val NOT_COUNTED = "NOT COUNTED"
internal const val NOT_COUNTED_WHILE_LEFT_OUT = "Not counted while left out"
internal const val LEFT_OUT_SMALL_PRINT = "KEPT IN THE LIST, GREYED" + Separator + "LEFT OUT OF EVERY TOTAL AND CHART"
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

/** What a test finds the category chip by. */
internal const val CATEGORY_CHIP = "detail-category"

/** What a test finds the Pays back chip by, `+ BILL` or the bill. */
internal const val PAYS_BACK_CHIP = "detail-pays-back"

/** What a test finds a payback's Counts against value by. */
internal const val COUNTS_AGAINST_VALUE = "detail-counts-against"

/** What a test finds Not a payback's snackbar by. */
internal const val DETAIL_UNDO_BAR = "detail-undo"

/** Room under the scrolled column for [UndoBar], so it never covers Delete. */
private val UNDO_ROOM = 72.dp

/** What a test finds the hero amount by. */
internal const val AMOUNT = "detail-amount"

/** What a test finds the note field's wrapper by; the editable node is its child. */
internal const val NOTE_FIELD = "detail-note"
