package my.pinged.ledger.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import my.pinged.data.entity.Category
import my.pinged.ledger.Withheld
import my.pinged.ledger.busyLine
import my.pinged.ledger.home.UnderlinedField
import my.pinged.ledger.home.categoryIcon
import my.pinged.ui.ringgit
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.ReceiptSheet
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp

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
) {
    val state by viewModel.state.collectAsState()
    ManualEntryScreenContent(
        state = state,
        onAmount = viewModel::amount,
        onCategory = viewModel::tapCategory,
        onDay = viewModel::day,
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
    )
}

/**
 * [ManualEntryScreen] without its holder, so a test can draw any state.
 *
 * Driven by [state] and callbacks alone: every field spec 9.4 adds -- the
 * date chip beside the amount, the category chips, the merchant and the note
 * -- is one more value on [ManualEntryState] and one more callback here.
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
    onCategory: (categoryId: Long) -> Unit = {},
    onAddAnyway: () -> Unit = {},
    onKeepEditing: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDeleted: () -> Unit = onSaved,
) {
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
        Header(if (state.editing != null) EDIT_PAYMENT else ADD_PAYMENT, onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmountField(sen = state.draft.amountSen, onAmount = onAmount, modifier = Modifier.weight(1f))
            DateChip(day = state.draft.day, today = state.today, onDay = onDay)
        }
        CategoryChips(state.categories, selected = state.draft.category?.categoryId, onTap = onCategory)
        OptionalLine(MERCHANT, state.draft.merchant, onMerchant, MERCHANT_FIELD, KeyboardCapitalization.Words)
        OptionalLine(NOTE, state.draft.note, onNote, NOTE_FIELD, KeyboardCapitalization.Sentences)
        // One line for what stops Save: the restore or delete running, else
        // a ledger that will not open (#108). The draft above stays as typed.
        val stopped = state.busy?.let { busyLine(it, Withheld.SAVED) }
            ?: CANNOT_READ_YOUR_DATA.takeIf { state.storageUnavailable }
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
        if (state.editing != null) DeleteEntry(state.loaded, enabled = state.canDelete, onDelete = onDelete)
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
private fun DeleteEntry(loaded: ManualDraft?, enabled: Boolean, onDelete: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
        WarningButton(DELETE_PAYMENT, enabled = enabled, onClick = { asking = true })
    }
    if (asking && loaded != null) {
        ReceiptSheet(onDismissRequest = { asking = false }) {
            Text(deleteQuestion(loaded.amountSen), style = MonoLabel, color = Ink)
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

/** Delete's question (spec 9.4): `Delete this RM12.50 payment? This can't be undone.` */
internal fun deleteQuestion(amountSen: Long): String = "Delete this " + ringgit(amountSen) + " payment? This can't be undone."


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
        Text(label, style = MonoLabel, color = ink)
    }
}

/**
 * `Pinged already has RM12.50 from Touch 'n Go eWallet at 12:41`, and
 * `, awaiting review` when the capture is held (spec 9.4): a held payment over
 * RM500 is exactly what a user types in after seeing the total not move. The
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
 * The amount, as a cash register takes it ([Keypad]): focused with the number
 * keyboard open on arrival, so the first tap is a digit.
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
 */
@Composable
private fun AmountField(sen: Long, onAmount: (Long) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shown = Keypad.shown(sen)
    BasicTextField(
        value = TextFieldValue(shown, selection = TextRange(shown.length)),
        onValueChange = { changed -> onAmount(Keypad.keyed(sen, shown, changed.text)) },
        textStyle = AmountStyle.copy(color = Ink),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        singleLine = true,
        visualTransformation = CashRegister,
        cursorBrush = SolidColor(Ink),
        modifier = modifier
            .padding(start = 20.dp, end = 8.dp, top = 24.dp)
            .focusRequester(focus)
            .testTag(AMOUNT_FIELD)
            .semantics { contentDescription = AMOUNT },
    )
}

/**
 * The field's digits drawn as ringgit: `1250` as `RM12.50`, nothing as
 * `RM0.00`. Every offset maps to the end of the other text, which is where
 * [AmountField] keeps the cursor.
 */
private object CashRegister : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val drawn = ringgit(text.text.toLongOrNull() ?: 0L)
        return TransformedText(
            AnnotatedString(drawn),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = drawn.length
                override fun transformedToOriginal(offset: Int): Int = text.length
            },
        )
    }
}

/**
 * Spec 9.4's category chips (#100), in the order they are handed -- most used
 * first -- in one row that scrolls sideways, so every category is reachable
 * and a rare one needs no other screen. A tap reports the chip; whether that
 * selects or deselects is the draft's ([ManualDraft.tapped]).
 *
 * A `Row` with its own scroll rather than a `LazyRow`: thirteen chips are
 * cheap to compose, and a composed chip is one `performScrollTo` reaches.
 */
@Composable
private fun CategoryChips(categories: List<Category>, selected: Long?, onTap: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .horizontalScroll(rememberScrollState())
            .padding(ChipRowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { category ->
            CategoryChip(category, chosen = category.id == selected) { onTap(category.id) }
        }
    }
}

/**
 * One chip: the category's icon and name, filled in [Ink] while selected.
 * The icon is decorative, for the reason `CategoryPicker`'s rows give.
 */
@Composable
private fun CategoryChip(category: Category, chosen: Boolean, onTap: () -> Unit) {
    val ink = if (chosen) Paper else Ink
    Row(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .then(if (chosen) Modifier.background(Ink) else Modifier.border(1.dp, Muted, RoundedCornerShape(2.dp)))
            .selectable(selected = chosen, role = Role.Checkbox, onClick = onTap)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categoryIcon(category.iconKey)?.let { icon ->
            Icon(painter = painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        }
        Text(category.name, style = MonoLabel, color = ink)
    }
}

private val ChipRowPadding = PaddingValues(horizontal = 20.dp)

/**
 * The date chip beside the amount (#103): `Today`, or the past day the entry
 * is moved to, and a tap opens [EntryDatePicker]. Picking a day closes it.
 */
@Composable
private fun DateChip(day: LocalDate?, today: LocalDate, onDay: (LocalDate) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier
            .padding(end = 20.dp, top = 24.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button) { picking = true }
            .heightIn(min = 44.dp)
            .border(1.dp, Rule, RoundedCornerShape(2.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(DATE_CHIP),
        contentAlignment = Alignment.Center,
    ) {
        Text(EntryDate.chip(day, today), style = MonoLabel, color = Ink)
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
 * in [Muted] while not.
 */
@Composable
private fun SaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Stamp else Muted
    Box(
        Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .clip(RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.background(ink) else Modifier.border(1.dp, ink, RoundedCornerShape(2.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MonoLabel, color = if (enabled) Paper else ink)
    }
}

private val AmountStyle = MonoNumerals.copy(fontSize = 40.sp, lineHeight = 48.sp)

/** The entry point's name: the Spending tab's + button and this screen's title (spec 9.4). */
const val ADD_PAYMENT = "Add payment"

/** This screen's title in edit mode (#105): a correction, not an addition. */
const val EDIT_PAYMENT = "Edit payment"

/** The button that writes the entry. */
const val SAVE = "SAVE"

/** Save in edit mode (#105): the entry is corrected, not added. */
const val SAVE_CHANGES = "SAVE CHANGES"

/** [NearbyWarning]'s answer that saves the entry beside the capture. */
const val ADD_ANYWAY = "ADD ANYWAY"

/** [NearbyWarning]'s answer that returns to the draft unsaved. */
const val NEARBY_CANCEL = "CANCEL"

/** Edit mode's Delete (#106), which asks before deleting. */
const val DELETE_PAYMENT = "DELETE PAYMENT"

/** Delete's question's answer that deletes the entry. */
const val CONFIRM_DELETE = "DELETE"

/** Delete's question's answer that keeps the entry; [NEARBY_CANCEL]'s word, a different button. */
const val DELETE_CANCEL = "CANCEL"

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

/** What a test finds the amount field by. */
internal const val AMOUNT_FIELD = "manual-entry-amount"

/** What a test finds the merchant field's wrapper by; the editable node is its child. */
internal const val MERCHANT_FIELD = "manual-entry-merchant"

/** What a test finds the note field's wrapper by; the editable node is its child. */
internal const val NOTE_FIELD = "manual-entry-note"
