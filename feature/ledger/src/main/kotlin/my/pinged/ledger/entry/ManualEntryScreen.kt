package my.pinged.ledger.entry

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import my.pinged.data.dao.BillChoice
import my.pinged.data.dao.FeedRow
import my.pinged.ledger.bill.PaidBackFooter
import my.pinged.ledger.bill.PaidBackList
import my.pinged.ledger.bill.paybacksStayLine
import my.pinged.ledger.bill.receivedOffLine
import my.pinged.ledger.home.RowOpenings
import my.pinged.data.entity.Category
import my.pinged.ledger.bill.BillChooser
import my.pinged.ledger.bill.BillFor
import my.pinged.ledger.bill.BillLabel
import my.pinged.ledger.bill.BillOffer
import my.pinged.ledger.bill.NO_BILL_PICKED
import my.pinged.ledger.bill.PAYS_BACK
import my.pinged.ledger.bill.countsAgainstLine
import my.pinged.ledger.Withheld
import my.pinged.ledger.busyLine
import my.pinged.ledger.home.CategoryPicker
import my.pinged.ledger.home.ChooserSubject
import my.pinged.ledger.home.ENTERED_BY_HAND
import my.pinged.ledger.home.UnderlinedField
import my.pinged.ledger.home.categoryIcon
import my.pinged.ledger.notice
import my.pinged.ledger.refused
import my.pinged.ledger.review.COUNTS_AGAINST
import my.pinged.ledger.withholding
import my.pinged.parse.Direction
import my.pinged.read.rememberReadState
import my.pinged.ui.RINGGIT
import my.pinged.ui.money
import my.pinged.ui.ringgit
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.DashLeader
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.DottedCell
import my.pinged.ui.theme.Faint
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.Rule
import my.pinged.ledger.home.HERO_NOT_READ_YET
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedOutline
import my.pinged.ui.theme.dottedRule

/**
 * Spec 9.4's entry screen: a payment Pinged did not see, typed in by hand
 * (#97, #99). Pushed on the Spending stack, so the tab bar is hidden and back
 * returns to the list.
 *
 * [onSaved] runs once the entry is in the ledger -- `:app` pops the screen --
 * and only while this screen is composed: a save that lands after the user
 * has already gone back pops nothing. [onDeleted] is the same once an entry
 * being corrected is deleted (#106).
 */
@Composable
fun ManualEntryScreen(
    viewModel: ManualEntryViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleted: () -> Unit = onSaved,
    openings: RowOpenings = RowOpenings.None,
) {
    // The chips read again on each resume and on `Databases.rewrites`, as every screen's read is.
    viewModel.chips.rememberReadState()
    // A typed bill's paybacks too: one added or changed over this screen is
    // listed on the way back to it.
    LifecycleResumeEffect(viewModel) {
        viewModel.reread()
        onPauseOrDispose {}
    }
    val state by viewModel.state.collectAsState()
    ManualEntryScreenContent(
        state = state,
        onAmount = viewModel::amount,
        onCategory = viewModel::tapCategory,
        onChooseCategory = viewModel::chooseCategory,
        onDay = viewModel::day,
        onDirection = viewModel::direction,
        onMerchant = viewModel::merchant,
        onNote = viewModel::note,
        onSave = viewModel::save,
        onBack = onBack,
        onSaved = onSaved,
        modifier = modifier,
        onAddAnyway = viewModel::addAnyway,
        onKeepEditing = viewModel::keepEditing,
        onDelete = viewModel::delete,
        onDeleted = onDeleted,
        onPickBill = viewModel::pickBill,
        bills = viewModel::bills,
        onOpenPayback = openings::open,
        onAddCashPayback = { id -> openings.addCashPayback(id) },
    )
}

/**
 * [ManualEntryScreen] without its holder, so a test can draw any state.
 *
 * Driven by [state] and callbacks alone: every field spec 9.4 adds -- the
 * date chip beside the amount, the category chips, the merchant and the note
 * -- is one more value on [ManualEntryState] and one more callback here.
 *
 * Under Received, `PAYS BACK` opens spec 7.4's bill chooser, whose list
 * [bills] reads; [onPickBill] reports the pick, null for No bill
 * (`design/CashPayback.dc.html`).
 */
@Composable
internal fun ManualEntryScreenContent(
    state: ManualEntryState,
    onAmount: (sen: Long) -> Unit,
    onMerchant: (String) -> Unit,
    onNote: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onDay: (LocalDate) -> Unit = {},
    onDirection: (Direction) -> Unit = {},
    onCategory: (categoryId: Long) -> Unit = {},
    onChooseCategory: (categoryId: Long) -> Unit = {},
    onAddAnyway: () -> Unit = {},
    onKeepEditing: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDeleted: () -> Unit = onSaved,
    onPickBill: (Long?) -> Unit = {},
    bills: suspend (BillFor, String) -> BillOffer? = { _, _ -> null },
    onOpenPayback: (FeedRow) -> Unit = {},
    onAddCashPayback: (billId: Long) -> Unit = {},
) {
    // Where a typed bill was leaving to when it asked first (spec 9.4): back,
    // or a payback's row. Null while it is not asking.
    var leaving by remember { mutableStateOf<(() -> Unit)?>(null) }
    val leave: (() -> Unit) -> Unit = { go -> if (state.asksBeforeLeaving) leaving = go else go() }
    BackHandler(enabled = state.asksBeforeLeaving) { leaving = onBack }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    // The entry is no longer there to correct; closed as after a delete.
    LaunchedEffect(state.gone) { if (state.gone) onDeleted() }
    // One scrolling column, Save inside it: under the keyboard on CI's short
    // screen, Save has to be reachable by a scroll, and spec 9.4's fields
    // sit between the amount and it.
    Column(
        modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState()),
    ) {
        val title = entryTitle(state.editing != null, state.draft.direction)
        Header(title) { leave(onBack) }
        if (state.reading) {
            // Not the fields: they would draw the empty default draft as the
            // payment, and a field typed into now is overwritten by the read.
            // A ledger that will not open is a read that will not land, and
            // the line below says so instead.
            if (!state.ledger.refused) NotReadYet()
        } else {
            // Kind and date on one row above the figure (#355), which then
            // has the whole width: beside it, the two controls left it
            // under 100dp at CI's 320dp.
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionControl(state.draft.direction, onDirection, receivedOff = state.receivedOff.takeIf { it }?.let { receivedOffLine(state.pointers) })
                DateChip(day = state.draft.day, today = state.today, onDay = onDay, conflict = state.dateConflict != null)
            }
            if (state.receivedOff) {
                // The reason, under the row: also the segment's TalkBack state.
                Text(
                    receivedOffLine(state.pointers),
                    style = MonoLabel,
                    color = Muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag(RECEIVED_OFF),
                )
            }
            state.dateConflict?.let {
                Text(it, style = MonoLabel, color = Stamp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag(DATE_CONFLICT))
            }
            AmountField(
                sen = state.draft.amountSen,
                onAmount = onAmount,
                focusOnArrival = state.editing == null,
                modifier = Modifier.fillMaxWidth(),
            )
            val paysBack = state.draft.paysBack
            val bill = state.shownBill
            if (state.draft.direction == Direction.REFUND) {
                PaysBack(
                    payback = BillFor(
                        state.draft.merchant.ifBlank { ENTERED_BY_HAND },
                        state.draft.amountSen,
                        RINGGIT,
                        state.draft.day ?: state.today,
                    ),
                    picked = paysBack,
                    bill = bill,
                    countedSen = state.countedSen,
                    amountSen = state.draft.amountSen,
                    bills = bills,
                    onPick = onPickBill,
                )
            }
            // A bill decides the category, so its chips go while one is
            // picked; one that has stopped counting leaves the money counting
            // on its own, filed by them.
            if (paysBack == null || bill?.counts == false) CategoryChips(
                state.categories,
                selected = state.draft.category?.categoryId,
                subject = ChooserSubject(
                    bill = state.draft.merchant.ifBlank { ENTERED_BY_HAND },
                    amount = money(state.draft.amountSen, RINGGIT, symbol = false),
                ),
                onTap = onCategory,
                onChoose = onChooseCategory,
                label = COUNTS_AGAINST.takeIf { state.draft.direction == Direction.REFUND },
            )
            val editing = state.editing
            if (editing != null && state.paybacks.isNotEmpty() && state.draft.direction == Direction.EXPENSE) {
                // Under the chips, as `design/CashBill.dc.html` draws it. The
                // board leaves the merchant and note out to fit the list; they
                // stay below it, since a bill's name is corrected here.
                PaidBackList(
                    paybacks = state.paybacks,
                    onOpen = { row -> leave { onOpenPayback(row) } },
                    onAdd = { onAddCashPayback(editing) },
                    footer = PaidBackFooter.Share(state.shareSen, RINGGIT),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                )
            }
            OptionalLine(MERCHANT, state.draft.merchant, onMerchant, MERCHANT_FIELD, KeyboardCapitalization.Words)
            OptionalLine(NOTE, state.draft.note, onNote, NOTE_FIELD, KeyboardCapitalization.Sentences)
        }
        // One line for what stops Save (#108): a refusal -- a ledger that will
        // not open, or one a replacement's gate holds -- else the restore or
        // delete running (#376). The draft above stays as typed.
        val stopped = state.ledger.withholding?.let { busyLine(it, Withheld.SAVED) } ?: state.ledger.notice
        if (stopped != null) {
            Text(
                stopped,
                style = MonoLabel,
                color = Stamp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
        }
        state.nearby?.let { nearby ->
            NearbyWarning(nearby, canAddAnyway = state.canAddAnyway, onAddAnyway = onAddAnyway, onKeepEditing = onKeepEditing)
        }
        SaveButton(if (state.editing != null) SAVE_CHANGES else SAVE, enabled = state.canSave, onClick = onSave)
        if (state.editing != null) DeleteEntry(state.loaded, state.paybacks, enabled = state.canDelete, onDelete = onDelete)
    }
    leaving?.let { go ->
        LeaveQuestion(
            onLeave = {
                leaving = null
                go()
            },
            onStay = { leaving = null },
        )
    }
}

/**
 * Asked before a typed bill with unsaved edits is left (spec 9.4): Leave
 * goes where the user was going and keeps nothing typed, Keep editing stays.
 */
@Composable
private fun LeaveQuestion(onLeave: () -> Unit, onStay: () -> Unit) {
    ReceiptSheet(onDismissRequest = onStay) {
        Text(LEAVE_QUESTION, style = MonoLabel, color = Ink)
        Row(Modifier.padding(top = 16.dp)) {
            WarningButton(LEAVE, enabled = true, onClick = onLeave)
            WarningButton(KEEP_EDITING, enabled = true, onClick = onStay)
        }
    }
}

/**
 * A correction's payment not read yet: `design/LoadingDetail.dc.html`'s
 * treatment of this screen's own layout, or `LoadingWaiting.dc.html`'s
 * status line while an export or the integrity check holds the database.
 * The amount is the hero's dash, the category chips dotted cells, and the
 * merchant and note their labels over a dash, since each is the read.
 */
@Composable
private fun NotReadYet() {
    ReadingLayout(Modifier.fillMaxWidth()) {
        Column {
            // Rule, decorative, as Spending's hero dash is: the status line
            // under it says the amount is not read yet.
            Text(HERO_NOT_READ_YET, style = AmountStyle, color = Rule, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp))
            ReadingStatus(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { DottedCell(Modifier.weight(1f).heightIn(min = 44.dp), RoundedCornerShape(2.dp)) }
            }
            for (label in listOf(MERCHANT, NOTE)) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                    Text(label, style = MonoLabel, color = Muted)
                    DashLeader(Modifier.padding(top = 10.dp).heightIn(min = 18.dp))
                }
            }
        }
    }
}

/**
 * `PAYS BACK` under Received (spec 9.4, `design/CashPayback.dc.html`): the
 * label over a 44dp line naming the bill, `Warung Pak Din · 15 Aug`, or
 * `None` in [Ink] before one is picked, with a [Muted] chevron; a tap opens
 * the bill chooser. Once a counted bill is picked, one line in Karla says
 * where Save will count the money and what the share becomes. [picked] is
 * the draft's pick and [bill] its read, null while it is read.
 */
@Composable
private fun PaysBack(
    payback: BillFor,
    picked: Long?,
    bill: BillChoice?,
    countedSen: Long,
    amountSen: Long,
    bills: suspend (BillFor, String) -> BillOffer?,
    onPick: (Long?) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        Text(PAYS_BACK, style = MonoLabel, color = Muted)
        Row(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .dottedRule()
                .clickable(role = Role.Button) { choosing = true }
                .heightIn(min = 44.dp)
                .testTag(PAYS_BACK_LINE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f)) {
                when {
                    bill != null -> BillLabel(bill, Ink)
                    picked == null -> Text(NO_BILL_PICKED, fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Ink)
                }
            }
            Chevron(pointsRight = true, size = 17.dp, strokeWidth = 1.7.dp, tint = Muted)
        }
        if (bill != null && bill.counts) {
            Text(
                countsAgainstLine(bill, amountSen, countedSen),
                fontFamily = Body,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Muted,
                modifier = Modifier.padding(top = 8.dp).testTag(BILL_LINE),
            )
        }
    }
    if (choosing) {
        BillChooser(
            payback = payback,
            current = picked,
            offer = { query -> bills(payback, query) },
            onPick = {
                choosing = false
                onPick(it)
            },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * Edit mode's Delete (#106, spec 9.4), under Save: it asks once, in a sheet
 * naming [loaded]'s amount -- the payment as stored, not as retyped -- and
 * only the sheet's Delete reaches [onDelete]. Cancel, the scrim and back
 * leave the entry and the screen as they were. No undo follows: putting the
 * row back would give it a new id and a new `created_at`.
 */
@Composable
private fun DeleteEntry(loaded: ManualDraft?, paybacks: List<FeedRow>, enabled: Boolean, onDelete: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        WarningButton(DELETE_PAYMENT, enabled = enabled, onClick = { asking = true })
    }
    if (asking && loaded != null) {
        ReceiptSheet(onDismissRequest = { asking = false }) {
            Text(deleteQuestion(loaded.amountSen, paybacks), style = MonoLabel, color = Ink)
            Row(Modifier.padding(top = 16.dp)) {
                WarningButton(CONFIRM_DELETE, enabled = enabled) {
                    asking = false
                    onDelete()
                }
                WarningButton(DELETE_CANCEL, enabled = true) { asking = false }
            }
        }
    }
}

/**
 * Delete's question (spec 9.4): `Delete this RM12.50 payment? This can't be
 * undone.`, saying of a bill that its [paybacks] stay, as money received
 * (`design/DeleteBill.dc.html`).
 */
internal fun deleteQuestion(amountSen: Long, paybacks: List<FeedRow> = emptyList()): String =
    listOfNotNull("Delete this " + ringgit(amountSen) + " payment?", paybacksStayLine(paybacks), "This can't be undone.").joinToString(" ")


/**
 * Save's question when Pinged already has the payment (#104, spec 9.4): the
 * capture's amount, source and time, with Add anyway -- two genuine RM5
 * parking payments both count -- and Cancel, which keeps the draft.
 */
@Composable
private fun NearbyWarning(
    nearby: NearbyCapture,
    canAddAnyway: Boolean,
    onAddAnyway: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Text(nearbyLine(nearby), style = MonoLabel, color = Stamp)
        Row(Modifier.padding(top = 8.dp)) {
            WarningButton(ADD_ANYWAY, enabled = canAddAnyway, onClick = onAddAnyway)
            WarningButton(NEARBY_CANCEL, enabled = true, onClick = onKeepEditing)
        }
    }
}

/** An answer to [NearbyWarning] or to Delete's question, and Delete itself: outlined, so Save stays the one filled button. */
@Composable
private fun WarningButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Ink else Muted
    Box(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, ink, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ButtonLabel.copy(fontSize = 14.sp), color = ink)
    }
}

/**
 * `Pinged already has RM12.50 from Touch 'n Go eWallet at 12:41`, and
 * `, awaiting review` when the capture is held (spec 9.4): a payment held over
 * the check limit is exactly what a user types in after seeing the total not move. The
 * time alone, not the day: the capture is on the entry's own day.
 */
internal fun nearbyLine(nearby: NearbyCapture, zone: ZoneId = ZoneId.systemDefault()): String =
    "Pinged already has " + ringgit(nearby.amountSen) + " from " + nearby.source + " at " +
        CLOCK.format(Instant.ofEpochMilli(nearby.occurredAt).atZone(zone)) +
        if (nearby.awaitingReview) ", awaiting review" else ""

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** The chevron and the screen's name, [title]: [ADD_PAYMENT], or [EDIT_PAYMENT] in edit mode. */
@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp, top = 12.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onBack)
            .size(44.dp)
            .semantics { contentDescription = entryBack(title) },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
    }
    Text(
        title,
        fontFamily = Display,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        color = Ink,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
    )
}

/**
 * The amount, as a cash register takes it ([Keypad]). With [focusOnArrival]
 * -- a new payment -- it is focused with the number keyboard open, so the
 * first tap is a digit. A correction opens unfocused (#272): the amount is
 * already there, and the keyboard would cover Delete payment.
 *
 * The field's own text is only ever [Keypad.shown] -- the digits of [sen] --
 * with the cursor held at the end, so every edit an IME can make is an append
 * or an erase at the end; [Keypad.keyed] reads it as keys, never as a number.
 * It is drawn as `RM12.50` by [CashRegister], which maps every offset to the
 * end so the cursor cannot be placed inside the figure.
 *
 * `KeyboardType.Number`, not `NumberPassword`, though the latter is the one
 * keyboard with no separator key: Compose marks a `NumberPassword` field as a
 * password, and TalkBack does not read a password's text aloud.
 *
 * The caret is `design/Cash.dc.html`'s 2dp by 44dp [Stamp] bar, 3dp after
 * the figure and standing on its baseline, drawn here off the text's layout
 * while the field has focus. The field's own cursor is transparent: it spans
 * the whole 58sp line, and no `BasicTextField` parameter sets its height.
 */
@Composable
private fun AmountField(sen: Long, onAmount: (Long) -> Unit, focusOnArrival: Boolean, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusOnArrival) focus.requestFocus() }
    val shown = Keypad.shown(sen)
    var focused by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicTextField(
        value = TextFieldValue(shown, selection = TextRange(shown.length)),
        onValueChange = { changed -> onAmount(Keypad.keyed(sen, shown, changed.text)) },
        textStyle = AmountStyle,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        singleLine = true,
        visualTransformation = CashRegister,
        cursorBrush = SolidColor(Color.Transparent),
        onTextLayout = { drawn = it },
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                drawContent()
                val text = drawn ?: return@drawWithContent
                if (!focused) return@drawWithContent
                val width = CaretWidth.toPx()
                // At the figure's end, or the field's when a long figure has
                // scrolled to keep its end in view.
                val x = minOf(text.getLineRight(0) + CaretGap.toPx(), size.width - width)
                val top = text.firstBaseline - CaretHeight.toPx()
                drawRect(Stamp, topLeft = Offset(x, top), size = Size(width, CaretHeight.toPx()))
            }
            .testTag(AMOUNT_FIELD)
            .semantics { contentDescription = AMOUNT },
    )
}

/**
 * The field's digits drawn as ringgit: `1250` as `RM12.50`, nothing as
 * `RM0.00`. Every offset maps to the end of the other text, which is where
 * [AmountField] keeps the cursor.
 *
 * One string with "RM" styled down to [RmSpan], not a separate `Text`: the
 * field's text is then still `RM12.50`, which TalkBack reads and every entry
 * test matches, and the two sizes share the line's baseline.
 */
private object CashRegister : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val drawn = ringgit(text.text.toLongOrNull() ?: 0L)
        return TransformedText(
            AnnotatedString(drawn, listOf(AnnotatedString.Range(RmSpan, 0, RM.length))),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = drawn.length
                override fun transformedToOriginal(offset: Int): Int = text.length
            },
        )
    }
}

/**
 * Spec 9.4's category chips (#100), drawn as `design/Cash.dc.html` (#272):
 * the first [SHOWN] of [categories] in the order they are handed -- most used
 * first -- wrapped, then a dashed [MORE] chip opening the ledger's own
 * chooser, [CategoryPicker], over all of them. A tap on a chip reports it,
 * and whether that selects or deselects is the draft's ([ManualDraft.tapped]);
 * a pick in the chooser always selects ([ManualDraft.chosen]).
 *
 * The [selected] category is always drawn: when it is not among the first
 * [SHOWN] -- picked through More, or a correction filed under a rarer one --
 * it takes the last place, so the row stays [SHOWN] chips and More.
 *
 * The chooser has no New category tile here: manual entry has no write
 * that makes a category, and Settings' Categories is where one is made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    categories: List<Category>,
    selected: Long?,
    subject: ChooserSubject,
    onTap: (Long) -> Unit,
    onChoose: (Long) -> Unit,
    label: String? = null,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    // Under Received (#355): the review inbox card's wording, over the chips.
    if (label != null) {
        Text(label, style = MonoLabel, color = Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
    }
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = if (label != null) 8.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shownChips(categories, selected).forEach { category ->
            CategoryChip(category, chosen = category.id == selected) { onTap(category.id) }
        }
        if (categories.size > SHOWN) MoreChip { choosing = true }
    }
    if (choosing) {
        CategoryPicker(
            categories = categories,
            subject = subject,
            teach = null,
            initial = selected,
            preview = { null },
            onSave = { id, _ ->
                choosing = false
                onChoose(id)
            },
            onDismiss = { choosing = false },
            noSwitchNote = null,
        )
    }
}

/** The chips [CategoryChips] draws before More: the first [SHOWN], with [selected] in the last place when it is not among them. */
internal fun shownChips(categories: List<Category>, selected: Long?): List<Category> {
    val first = categories.take(SHOWN)
    val chosen = categories.firstOrNull { it.id == selected }
    return if (chosen == null || chosen in first) first else first.take(SHOWN - 1) + chosen
}

/** How many category chips the row draws before More: the artboard's four. */
private const val SHOWN = 4

/**
 * The artboard's More: a chip outlined in a dashed [Muted], its name in
 * [Muted], and no icon. A button, not a selectable: it opens the chooser.
 */
@Composable
private fun MoreChip(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .dashedOutline(Muted)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(MORE, style = ChipLabel, color = Muted)
    }
}

/**
 * One chip, as `design/Cash.dc.html` draws them: the category's icon and its
 * name in Karla 13, filled in [Stamp] with the name at weight 500 while
 * selected, outlined in [Border] while not. The icon is decorative, for the
 * reason `CategoryPicker`'s tiles give.
 */
@Composable
private fun CategoryChip(category: Category, chosen: Boolean, onTap: () -> Unit) {
    val ink = if (chosen) Paper else Ink
    Row(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .then(if (chosen) Modifier.background(Stamp) else Modifier.border(1.dp, Border, RoundedCornerShape(2.dp)))
            .selectable(selected = chosen, role = Role.Checkbox, onClick = onTap)
            .heightIn(min = 44.dp)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categoryIcon(category.iconKey)?.let { icon ->
            Icon(painter = painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        }
        Text(category.name, style = if (chosen) ChipLabel.copy(fontWeight = FontWeight.Medium) else ChipLabel, color = ink)
    }
}

/**
 * The date chip opposite the Paid/Received control, above the amount (#103): `Today`, or the past day the entry
 * is moved to, and a tap opens [EntryDatePicker]. Picking a day closes it.
 * [Stamp] while [conflict]: the day puts a bill after its payback (spec 9.4).
 * The artboard has no date chip; it is outlined in [Border] like the
 * category chips, since `Rule` is the dotted rules' colour.
 */
@Composable
private fun DateChip(day: LocalDate?, today: LocalDate, onDay: (LocalDate) -> Unit, conflict: Boolean = false) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button) { picking = true }
            .heightIn(min = 44.dp)
            .border(1.dp, if (conflict) Stamp else Border, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(DATE_CHIP),
        contentAlignment = Alignment.Center,
    ) {
        Text(EntryDate.chip(day, today), style = MonoLabel, color = if (conflict) Stamp else Ink)
    }
    if (picking) {
        EntryDatePicker(
            selected = day ?: today,
            today = today,
            onPick = { picked ->
                picking = false
                onDay(picked)
            },
            onDismiss = { picking = false },
        )
    }
}

/** [DirectionControl]'s segments, in order: direction, label, test tag. */
private val SEGMENTS = listOf(
    Triple(Direction.EXPENSE, PAID, PAID_SEGMENT),
    Triple(Direction.REFUND, RECEIVED, RECEIVED_SEGMENT),
)

/**
 * Paid or Received (#355), a two-segment control on the row above the
 * amount, opposite the date chip. Paid is the default, so the common path
 * takes no tap on it. Each segment is a 44dp-high selectable of its own.
 */
@Composable
private fun DirectionControl(direction: Direction, onDirection: (Direction) -> Unit, receivedOff: String? = null) {
    // Each segment draws its own hairline and the pair overlap by 1dp, with
    // the selected one on top: a border on the row would be drawn over the
    // selected segment's Ink edge, leaving only its inner side dark.
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy((-1).dp)) {
        for ((value, label, tag) in SEGMENTS) {
            val on = direction == value
            // A typed bill's Received ([receivedOff] its reason): dotted in
            // Faint, the board's off control, and saying why to TalkBack.
            val off = receivedOff != null && value == Direction.REFUND && !on
            val shape = RoundedCornerShape(2.dp)
            Box(
                Modifier
                    .zIndex(if (on) 1f else 0f)
                    .clip(shape)
                    // Selected is the day header's Card tint inside an Ink
                    // hairline, not a fill: Save stays the one filled element.
                    .then(if (on) Modifier.background(Card) else Modifier)
                    .then(if (off) Modifier.dottedOutline(Faint) else Modifier.border(1.dp, if (on) Ink else Border, shape))
                    .selectable(selected = on, enabled = !off, role = Role.RadioButton) { onDirection(value) }
                    .then(if (off) Modifier.semantics { stateDescription = receivedOff!! } else Modifier)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 10.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                // Faint as the board draws it: an off control's label is exempt
                // from text's 4.5:1 (WCAG 1.4.3), and the line under the row
                // says the same in Muted.
                Text(label, style = MonoLabel, color = if (on) Ink else if (off) Faint else Muted)
            }
        }
    }
}

/**
 * One of spec 9.4's optional one-line fields: a label over the merchant
 * sheet's [UnderlinedField]. Blank is a complete answer -- nothing here gates
 * [ManualEntryState.canSave] -- so the placeholder says so.
 */
@Composable
private fun OptionalLine(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    capitalization: KeyboardCapitalization,
) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Text(label, style = MonoLabel, color = Muted)
        UnderlinedField(
            value = value,
            onValueChange = onValueChange,
            placeholder = OPTIONAL,
            tag = tag,
            capitalization = capitalization,
        )
    }
}

/**
 * Save, reading [label]: filled in the accent while it would write, outlined
 * in [Muted] while not. 52dp, in a band under a dotted rule, as
 * `design/Cash.dc.html` draws it below its keypad; the band is [Paper] like
 * the screen, so the rule is all that marks it.
 */
@Composable
private fun SaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Stamp else Muted
    Box(
        Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .dottedRule(atTop = true)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp))
                .then(if (enabled) Modifier.background(ink) else Modifier.border(1.dp, ink, RoundedCornerShape(2.dp)))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = ButtonLabel, color = if (enabled) Paper else ink)
        }
    }
}

/**
 * The amount: Instrument Serif 58 in [Ink], the screen's one total, and spec
 * 8 gives a single display figure to the serif. Its digits are proportional
 * (advances 249 to 460 per 1000 em, no `tnum`, measured from the bundled
 * file), which a cash register's entry does not need: every key shifts every
 * digit one place, so no column holds still to align whatever the face.
 */
private val AmountStyle = TextStyle(fontFamily = Display, fontSize = 58.sp, lineHeight = 58.sp, color = Ink)

private const val RM = "RM"

private val CaretWidth = 2.dp
private val CaretHeight = 44.dp
private val CaretGap = 3.dp

/**
 * The figure's "RM": serif 30 in [Faint]. [Faint] fails 4.5:1 and is kept
 * off body text, but at 30sp this is large text, which WCAG 1.4.3 holds to
 * 3:1, and [Faint] on [Paper] measures 3.55:1.
 */
private val RmSpan = SpanStyle(fontFamily = Display, fontSize = 30.sp, color = Faint)

/** A category chip's name: Karla 13, the artboard's chip. */
private val ChipLabel = TextStyle(fontFamily = Body, fontSize = 13.sp, lineHeight = 16.sp)

/** The entry point's name: the Spending tab's + button and this screen's title (spec 9.4). */
const val ADD_PAYMENT = "Add payment"

/** The direction control's default segment (#355). */
const val PAID = "Paid"

/** The direction control's other segment: money that came back (#355). */
const val RECEIVED = "Received"

/** The title under Received (#355). */
const val ADD_MONEY_RECEIVED = "Add money received"

/** Edit mode's title under Received (#355). */
const val EDIT_MONEY_RECEIVED = "Edit money received"

/** The screen's title: by edit mode, and by [direction], which says what is being recorded (#355). */
internal fun entryTitle(editing: Boolean, direction: Direction): String = when {
    direction == Direction.REFUND -> if (editing) EDIT_MONEY_RECEIVED else ADD_MONEY_RECEIVED
    else -> if (editing) EDIT_PAYMENT else ADD_PAYMENT
}

/** What a test finds the Paid segment by. */
internal const val PAID_SEGMENT = "manual-entry-paid"

/** The question a typed bill with unsaved edits asks before it is left (spec 9.4), and its two answers. */
internal const val LEAVE_QUESTION = "Leave without saving? Your changes to this payment are not saved."
internal const val LEAVE = "Leave"
internal const val KEEP_EDITING = "Keep editing"

/** What a test finds Received's off line by. */
internal const val RECEIVED_OFF = "manual-entry-received-off"

/** What a test finds the Received segment by. */
internal const val RECEIVED_SEGMENT = "manual-entry-received"

/** This screen's title in edit mode (#105): a correction, not an addition. */
const val EDIT_PAYMENT = "Edit payment"

/** The chip after the first few categories, which opens the chooser over all of them (#272). */
const val MORE = "More"

/** The button that writes the entry. */
const val SAVE = "Save"

/** Save in edit mode (#105): the entry is corrected, not added. */
const val SAVE_CHANGES = "Save changes"

/** [NearbyWarning]'s answer that saves the entry beside the capture. */
const val ADD_ANYWAY = "Add anyway"

/** [NearbyWarning]'s answer that returns to the draft unsaved. */
const val NEARBY_CANCEL = "Cancel"

/** Edit mode's Delete (#106), which asks before deleting. */
const val DELETE_PAYMENT = "Delete payment"

/** Delete's question's answer that deletes the entry. */
const val CONFIRM_DELETE = "Delete"

/** Delete's question's answer that keeps the entry; [NEARBY_CANCEL]'s word, a different button. */
const val DELETE_CANCEL = "Cancel"

/** The amount field's description, read by TalkBack before the figure. */
const val AMOUNT = "Amount"

/** The merchant field's label (spec 9.4); optional. */
const val MERCHANT = "MERCHANT"

/** The note field's label (spec 9.4); optional. */
const val NOTE = "NOTE"

/** The placeholder both optional fields show while empty. */
const val OPTIONAL = "Optional"

/** The chevron's description under [title]: `Back from Add payment`, or `Back from Edit payment`. */
fun entryBack(title: String): String = "Back from $title"

/** What a test finds the date chip by. */
internal const val DATE_CHIP = "manual-entry-date"

/** What a test finds the line under a date that puts a bill after its payback by. */
internal const val DATE_CONFLICT = "manual-entry-date-conflict"

/** What a test finds `PAYS BACK`'s line by, and the line that says where Save counts a payback. */
internal const val PAYS_BACK_LINE = "manual-entry-pays-back"
internal const val BILL_LINE = "manual-entry-bill"

/** What a test finds the amount field by. */
internal const val AMOUNT_FIELD = "manual-entry-amount"

/** What a test finds the merchant field's wrapper by; the editable node is its child. */
internal const val MERCHANT_FIELD = "manual-entry-merchant"

/** What a test finds the note field's wrapper by; the editable node is its child. */
internal const val NOTE_FIELD = "manual-entry-note"
