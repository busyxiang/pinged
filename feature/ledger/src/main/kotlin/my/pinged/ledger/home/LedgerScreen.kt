package my.pinged.ledger.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import my.pinged.charts.MonthsSheet
import my.pinged.charts.model.monthsSheet
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ledger.entry.ADD_PAYMENT
import my.pinged.ledger.entry.EditTarget
import my.pinged.ledger.review.awaitingReviewLabel
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.monthName
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ButtonLabel
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.HeroFill
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.Magnifier
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.PlusMark
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.TornEdge
import my.pinged.ui.theme.dashedOutline
import my.pinged.ui.theme.dottedRule
import my.pinged.ui.theme.heroFigure
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle

/**
 * Spec 9.1's transaction list, drawn from `design/Main.dc.html`.
 *
 * **The artboard's bottom navigation is not drawn here**: `MainActivity` hosts
 * it, because it is shared with settings and a tab switch is not this screen's
 * to decide. Its cash button is [onAddPayment], spec 9.4's **Add payment**,
 * which `:app` routes to the entry screen.
 *
 * The category is spelled out in the line beneath the merchant as well as drawn
 * as an icon ([RowIcon]), so a reader who cannot tell two 18dp glyphs apart has
 * lost nothing (WCAG 1.4.1); §4 lets the user re-icon a category, so the name
 * is the half that cannot drift from what the row is filed under.
 *
 * The category chooser opens on any row that is not excluded: the "+ CATEGORY"
 * chip stays the marker on an Uncategorized row, and on a categorised one the
 * category line opens the same sheet. The sheet picks a category and then
 * saves it (#49), and the save either teaches or does not, by the "Always call
 * this" switch (#45, #48). With it on the save writes the merchant's learned
 * rule, moves the merchant's past payments when none was set by hand, and
 * leaves the row `user_edited = 0`, filed by the rule; with it off the save is a one-off,
 * `user_edited = 1`, the record that a person decided this one payment (§5.5).
 */
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenReview: (focus: Long?) -> Unit = {},
    onAddPayment: (() -> Unit)? = null,
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    heard: String? = null,
    /** The backup reminder (#252), or null for none; see [BackupReminderRow]. */
    backupReminder: BackupReminder? = null,
) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val read by viewModel.read.collectAsState()
    val merchantSheet by viewModel.merchantSheet.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()
    val picked by viewModel.picked.collectAsState()
    // Saveable, so a sheet open at a process death is open again after it,
    // as Charts' is.
    var monthsOpen by rememberSaveable { mutableStateOf(false) }
    // Counts picks, so the feed goes back to its top after each, the current
    // month's included.
    var picks by rememberSaveable { mutableIntStateOf(0) }

    // Once per foreground, not once per holder -- see `SourcesScreen`, which
    // records the mechanism. Firing once per holder would leave the aggregates
    // never re-read: a payment captured while the app was away appears as a row
    // -- the insert invalidates the `PagingSource` -- above a day header and a
    // month total that do not count it.
    //
    // Keyed on `Databases.rewrites` too, so a delete or restore that lands
    // while this screen is showing re-reads the aggregates without a resume;
    // the holder rebinds the feed itself (`LedgerViewModel.rebind`), on
    // `generation`. Here rather than there for the reason `SourcesScreen`
    // gives; `rewrites` rather than `generation` for the one `Databases` gives.
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        // Paging does not re-attempt a load that failed, so the feed's half of
        // the catch-up has to be asked for. Without it a transient failure is
        // permanent on this screen alone, while `refresh` and
        // `MainActivity.onResume` clear the banner -- leaving the body reading
        // "cannot be read" under a banner saying otherwise.
        items.retry()
        onPauseOrDispose {}
    }

    LedgerScreenContent(
        items = items,
        read = read,
        storageUnavailable = unavailable,
        onAssign = { txnId, categoryId, teach -> viewModel.assignCategory(txnId, categoryId, teach) },
        retroPreview = viewModel::retroPreview,
        addCategory = viewModel::addCategory,
        onOpenSettings = onOpenSettings,
        onOpenReview = onOpenReview,
        onAddPayment = onAddPayment,
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
        onOpenSearch = onOpenSearch,
        heard = heard,
        backupReminder = backupReminder,
        picked = picked,
        picks = picks,
        onOpenMonths = { monthsOpen = true },
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

    if (monthsOpen) {
        // Read when it opens, so its rows are as of that moment.
        LaunchedEffect(viewModel) { viewModel.readMonths() }
        val months by viewModel.months.collectAsState()
        MonthsSheet(
            items = months?.let(::monthsSheet),
            onPick = {
                monthsOpen = false
                picks++
                viewModel.pick(it)
            },
            onDismiss = { monthsOpen = false },
        )
    }
}

/**
 * Stateless, so the screen test can put the screen in a state a database cannot
 * be talked into producing -- an unreadable ledger, a first read still in
 * flight, a day header whose aggregate has not landed.
 *
 * `internal` rather than public like `SourcesScreenContent`: [LazyPagingItems]
 * comes from `paging-compose`, which this module takes as `implementation`, so
 * a public signature naming it would be a type consumers cannot resolve. The
 * androidTest source set is a friend of main and sees it.
 */
@Composable
internal fun LedgerScreenContent(
    items: LazyPagingItems<LedgerItem>,
    read: LedgerRead,
    storageUnavailable: Boolean,
    onAssign: (txnId: Long, categoryId: Long, teach: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    retroPreview: suspend (txnId: Long, categoryId: Long) -> RetroPreview? = { _, _ -> RetroPreview(0, emptyList()) },
    /** The chooser's New category (#155); null draws no tile. */
    addCategory: (suspend (name: String, iconKey: String) -> Long?)? = null,
    merchantSheet: MerchantSheetState? = null,
    merchantActions: MerchantActions = MerchantActions.None,
    onOpenReview: (focus: Long?) -> Unit = {},
    onAddPayment: (() -> Unit)? = null,
    onOpenEntry: ((EditTarget) -> Unit)? = null,
    onOpenDetail: ((EditTarget) -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    heard: String? = null,
    backupReminder: BackupReminder? = null,
    /** The holder's picked month, null to follow the clock; see [LedgerViewModel.picked]. */
    picked: YearMonth? = null,
    onOpenMonths: (() -> Unit)? = null,
    picks: Int = 0,
) {
    val listState = rememberLazyListState()
    // A pick starts the feed at its month, so the list goes to its top: at
    // once, over the rows still drawn, and again when the first row changes.
    // A `LazyColumn` keeps its first visible key across new data, so when the
    // picked month's rows land after the first scroll -- CI's emulator, and
    // `MonthPickerTest`'s slowed pages -- a pick back to now follows last
    // month's header deep into the new list. Bounded by [PICK_SETTLE], since
    // a pick that leaves the first row as it was never changes it.
    LaunchedEffect(picks) {
        if (picks == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        withTimeoutOrNull(PICK_SETTLE) {
            snapshotFlow { items.itemSnapshotList.firstOrNull() }.drop(1).first()
            listState.scrollToItem(0)
        }
    }

    // Which row is being categorized, hoisted to here rather than held in the
    // row: a `ModalBottomSheet` emitted from inside the `LazyColumn` scrolls
    // out of the viewport and is disposed with the item that owns it.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }

    // Keyed on the list, because a `LazyColumn` recomposes as it scrolls and
    // rebuilding a sixteen-entry map per frame is work for nothing.
    val categoriesById = remember(read.categories) { read.categories.associateBy { it.id } }

    val chooser = remember(read) { read.chooser }
    val choices = remember(chooser.categories, chooser.uncategorizedId) { chooserChoices(chooser) }

    val unreadable = storageUnavailable || items.loadState.refresh is LoadState.Error
    val reading = !unreadable && items.loadState.refresh is LoadState.Loading && items.itemCount == 0
    // A picked month with nothing on or before it is the feed, not day one:
    // the sheet listed it, so there is a history, only none of it that old.
    val empty = !unreadable && !reading && items.itemCount == 0 && picked == null

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Paper)) {
            // Above the branch: the month being summarised is true in every state,
            // including the two with nothing to show.
            TopBar(
                // The pick before the clock: it is saved state, so after a
                // process death it is known before the summary is read (#237).
                month = read.summary?.month ?: picked ?: YearMonth.now(),
                heard = heard,
                onOpenMonths = onOpenMonths,
                onOpenSearch = onOpenSearch,
            )

            when {
                // First, because it is the only state where the ledger is certainly
                // unreadable rather than empty. An empty list here would assert
                // "you spent nothing" when the truth is "this cannot be read"
                // (§11.1). Either read may be the one that found out: `refresh`
                // reports it through `CaptureStorage.guarded`, the feed as a
                // refresh error (see `ReopeningFeed`). Any refresh error, not only
                // §11.1's: a page that would not load is a list that could not be
                // read either way.
                unreadable -> Unreadable(Modifier.weight(1f))

                // Not empty until the first load has actually returned. `loadState`
                // distinguishes them; a bare `itemCount == 0` does not.
                //
                // Only while nothing is loaded. Every write invalidates the feed
                // and puts `refresh` back at `Loading` over rows still drawn;
                // drawing this then disposes the `LazyColumn` and its scroll
                // position with it, so a recategorized row jumps the list to the
                // top (#158).
                reading -> Reading(Modifier.weight(1f))

                empty -> Empty(onOpenSettings, Modifier.weight(1f), onAddPayment)

                else -> Feed(
                    items = items,
                    summary = read.summary,
                    categoriesById = categoriesById,
                    uncategorizedId = read.uncategorizedId,
                    hasChoices = choices.isNotEmpty(),
                    onCategorize = { txnId -> categorizing = txnId },
                    onOpenMerchant = merchantActions.open,
                    awaitingReview = read.awaitingReview,
                    awaitingReviewInMonth = read.awaitingReviewInMonth,
                    onOpenReview = onOpenReview,
                    onOpenEntry = onOpenEntry,
                    onOpenDetail = onOpenDetail,
                    onAddPayment = onAddPayment,
                    backupReminder = backupReminder,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // In every state but an empty ledger, an unreadable one included: the
        // entry screen says for itself when it cannot save. `Empty.dc.html`
        // draws no button, because its offer at the foot is the same action.
        // Empty is a ledger with no rows, not a month with none: a quiet month
        // over a history is the feed, and keeps the button. The feed draws its
        // own, since where it sits depends on the list's room.
        if (onAddPayment != null && (unreadable || reading)) {
            AddPaymentButton(onAddPayment, Modifier.align(Alignment.BottomEnd).padding(20.dp))
        }
    }

    // A sibling of the content, not an item of the list. Gated on there being
    // something to choose as well as on a row being chosen: a sheet whose every
    // row was filtered out still draws its heading and its handle. A tap cannot
    // arrive here with `choices` empty -- the chip is gated on the same list --
    // but a restore can: `categorizing` comes back from `rememberSaveable`
    // after a process death while `categories` is still the empty initial
    // value, because the refresh that fills it is asynchronous.
    //
    // The row is found among those loaded rather than saved with the id: what
    // the sheet needs of it (its key, its category, whether a person set it)
    // is a read of the database, and after a process death it would be a read
    // of a ledger that may have moved. A row not loaded draws no sheet.
    val chosen = categorizing
    val chosenRow = chosen?.let { id ->
        items.itemSnapshotList.firstOrNull { it is LedgerItem.Row && it.txn.id == id } as LedgerItem.Row?
    }
    if (chosen != null && chosenRow != null && choices.isNotEmpty()) {
        RowCategoryChooser(
            row = chosenRow.feedRow,
            chooser = chooser,
            retroPreview = retroPreview,
            onSave = { categoryId, teach ->
                categorizing = null
                onAssign(chosen, categoryId, teach)
            },
            onDismiss = { categorizing = null },
            addCategory = addCategory,
        )
    }

    // A sibling for the same reason as the picker. Its state is the holder's,
    // not saved here: it is a read of the database, and after a process death
    // it would be a read of a ledger that may have moved.
    if (merchantSheet != null) MerchantSheet(merchantSheet, merchantActions)
}

/**
 * The month being summarised, and the way to pick another (#192).
 *
 * The month comes from the summary rather than the clock so the label cannot
 * name a period the numbers below it did not cover; before the first aggregate
 * lands there is no number to label and it names the current month.
 *
 * With [onOpenMonths] the label and the board's chevron are one button with
 * the design system's 44dp target. The row's top is the board's 26dp less
 * half of what that target adds over the 30dp chip beside it, so with the
 * chip drawn the label sits on the board's line; what is under the bar sits
 * 7dp lower than the board's, the target's height over the chip's.
 *
 * [heard] is spec 10.2's always-visible signal, worded and withheld by
 * `MainActivity.heardLine`, which says why it is not "captured"; null draws
 * nothing.
 *
 * [onOpenSearch] is spec 9.1's search (#154), the magnifier at the row's end.
 * `Main.dc.html` draws no way in; the screen set's note that every pushed
 * screen carries a back arrow lists search among them, and the header is
 * the one place on this screen above the money rather than among it.
 *
 * The chip is on the month's row where its one line fits there, and
 * otherwise on a line of its own under the magnifier, right-aligned: at
 * 320dp beside SEPTEMBER 2026 its words get about 51dp, six characters, and
 * no `heardLine` fits. Decided by measuring the chip, not by a width, so a
 * longer month or a larger font moves it too.
 */
@Composable
private fun TopBar(month: YearMonth, heard: String?, onOpenMonths: (() -> Unit)?, onOpenSearch: (() -> Unit)?) {
    Layout(
        content = {
            Row(
                Modifier
                    .layoutId(BarSlot.MONTH)
                    .testTag(MONTH_BUTTON)
                    .heightIn(min = MONTH_TARGET)
                    .then(
                        if (onOpenMonths != null) {
                            Modifier.clickable(role = Role.Button, onClickLabel = CHOOSE_A_MONTH, onClick = onOpenMonths)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(monthName(month), style = MonoLabel, color = Muted)
                // Only where it opens something: it says "pick a month".
                if (onOpenMonths != null) DownChevron()
            }
            if (heard != null) HeardChip(heard, Modifier.layoutId(BarSlot.CHIP))
            if (onOpenSearch != null) SearchButton(onOpenSearch, Modifier.layoutId(BarSlot.SEARCH))
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp - (MONTH_TARGET - 30.dp) / 2),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun slot(id: BarSlot) = measurables.firstOrNull { it.layoutId == id }?.measure(loose)
        val monthSlot = slot(BarSlot.MONTH)!!
        val chip = slot(BarSlot.CHIP)
        val search = slot(BarSlot.SEARCH)
        val width = constraints.maxWidth
        val gap = CHIP_GAP.roundToPx()
        val rowHeight = maxOf(monthSlot.height, chip?.height ?: 0, search?.height ?: 0)
        val used = monthSlot.width + (search?.width ?: 0)
        val inRow = chip == null || used + gap + chip.width <= width
        val height = if (inRow) rowHeight else maxOf(monthSlot.height, search?.height ?: 0) + CHIP_DROP.roundToPx() + chip!!.height
        layout(width, height) {
            val firstRow = if (inRow) rowHeight else maxOf(monthSlot.height, search?.height ?: 0)
            monthSlot.place(0, (firstRow - monthSlot.height) / 2)
            search?.let { it.place(width - it.width, (firstRow - it.height) / 2) }
            if (chip != null) {
                if (inRow) {
                    // Where a SpaceBetween row puts it: at the end with no
                    // magnifier, else centred in what the month and the
                    // magnifier leave, at least CHIP_GAP after the month.
                    val free = width - used - gap - chip.width
                    val x = if (search == null) width - chip.width else monthSlot.width + gap + free / 2
                    chip.place(x, (rowHeight - chip.height) / 2)
                } else {
                    chip.place(width - chip.width, firstRow + CHIP_DROP.roundToPx())
                }
            }
        }
    }
}

/** The top bar's three slots, which [TopBar]'s layout finds by id. */
private enum class BarSlot { MONTH, CHIP, SEARCH }

/** The least room between the month and the heard chip on one row. */
private val CHIP_GAP = 12.dp

/** How far below the month's row the heard chip sits when it does not fit on it. */
private val CHIP_DROP = 8.dp

/**
 * The magnifier, `Search.dc.html`'s glyph at 20dp in Muted, in the design
 * system's 44dp target. Its right edge is the glyph's, on the 20dp margin.
 */
@Composable
private fun SearchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(start = 4.dp)
            .offset(x = 12.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .size(MONTH_TARGET)
            .semantics { contentDescription = OPEN_SEARCH },
        contentAlignment = Alignment.Center,
    ) {
        Magnifier(size = 20.dp, tint = Muted)
    }
}

/** The magnifier's description, and what a test finds it by. */
const val OPEN_SEARCH = "Search"

/**
 * How long after a pick a change of first row still sends the list to its
 * top. Past the slowest first page a test or CI has shown; a payment captured
 * inside it scrolls to the top, which is where a pick left the user.
 */
private const val PICK_SETTLE = 10_000L

/** The month button's height: the design system's 44px target. */
private val MONTH_TARGET = 44.dp

/**
 * The board's month chevron, `M6 9l6 6 6-6` in a 24-unit box at 14dp, stroke
 * 1.8 of 24, round-capped, in Muted.
 */
@Composable
private fun DownChevron() {
    Canvas(Modifier.testTag(MONTH_CHEVRON).size(14.dp)) {
        val unit = size.width / 24f
        val stroke = 1.8f * unit
        drawLine(Muted, Offset(6 * unit, 9 * unit), Offset(12 * unit, 15 * unit), stroke, StrokeCap.Round)
        drawLine(Muted, Offset(12 * unit, 15 * unit), Offset(18 * unit, 9 * unit), stroke, StrokeCap.Round)
    }
}

/**
 * The boards' top-right chip -- `Empty.dc.html`'s "LISTENING" -- carrying
 * [heard]. Never the board's word: the access grant is all this app knows on
 * day one, and granted with nothing ever heard is the OEM-kill signature
 * (`CaptureReport.looksDead`), so a claim to be listening would be the one
 * thing it cannot back. One line always: where that does not fit beside the
 * month, [TopBar] moves the whole chip.
 */
@Composable
private fun HeardChip(heard: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(HEARD_CHIP)
            .border(1.dp, Border, RoundedCornerShape(2.dp))
            .heightIn(min = 30.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).background(Stamp, CircleShape))
        Text(heard, style = MonoLabel, color = Ink, maxLines = 1, softWrap = false)
    }
}

/**
 * The one state on this screen that is not a fact about the user's money.
 *
 * Drawn in the accent inside a frame, as `SourcesScreen` draws the same
 * sentence, and never typeset like the empty state: a user who reads "nothing
 * captured yet" here concludes they have spent nothing, when what happened is
 * that their ledger could not be read.
 *
 * **The copy names no cause.** The branch above fires for any refresh error --
 * Room's own `PagingSource` can fail at page-load time on a full disk or an IO
 * error -- so naming §11.1 would explain the wrong one. The banner above the
 * whole `NavDisplay` names no cause either, for the same reason; its button
 * leads to settings, which tells the causes apart.
 *
 * **Nor does it say what is or is not still there.** A restore that lost the
 * ledger, or a delete, can leave a database the next read cannot open, so
 * "nothing already recorded has been deleted" is false in exactly the state a
 * user is likeliest to be reading this. Settings is the one screen that knows
 * whether that happened, so this points there, and not at the banner, which
 * defers to settings itself and may not be drawn at all.
 */
@Composable
private fun Unreadable(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Text(
            CANNOT_READ_YOUR_DATA,
            style = MonoLabel,
            color = Stamp,
            modifier = Modifier
                .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Text(
            "Pinged could not read your transactions, so an empty list here " +
                "is not a sign that there are none. Settings says more when " +
                "Pinged knows more.",
            fontFamily = Body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Ink,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

/** The first read is in flight. Deliberately not the empty copy. */
@Composable
private fun Reading(modifier: Modifier = Modifier) {
    Text(
        "READING",
        style = MonoLabel,
        color = Muted,
        modifier = modifier.padding(start = 20.dp, top = 24.dp),
    )
}

/**
 * Nothing has been captured, drawn from `design/Empty.dc.html`.
 *
 * **It never says "nothing spent".** With no source enabled those are
 * different facts and only one of them is knowable here, which is why a route
 * towards the allow-list is part of this state rather than decoration.
 *
 * That route is Settings, not the allow-list itself: the sources screen is
 * reached through `SettingsScreen`'s own `Capture sources` row, so the button
 * names where the tap lands rather than where it eventually leads.
 *
 * [onAddPayment] is the board's offer at the foot, in place of the floating
 * button the other states draw; null draws none.
 */
@Composable
internal fun Empty(onOpenSettings: () -> Unit, modifier: Modifier = Modifier, onAddPayment: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        // Scrolled on its own, so at 320x640 or a large font the offer stays
        // at the foot and the copy above it scrolls.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 34.dp),
        ) {
            Column(
                Modifier
                    .testTag(EMPTY_HERO)
                    .fillMaxWidth()
                    .background(HeroFill)
                    .border(1.dp, Border)
                    .padding(1.dp),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp)) {
                    Text("NOTHING CAPTURED YET", style = MonoLabel, color = Muted)
                    Text(
                        "Nothing is missing. There is just nothing yet.",
                        fontFamily = Display,
                        fontSize = 31.sp,
                        lineHeight = 1.2.em,
                        color = Ink,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Android will not hand over notifications from before you switched " +
                            "Pinged on, so your paper trail starts here. A payment lands in " +
                            "this list on its own once the app it comes from is switched on " +
                            "for capture.",
                        fontFamily = Body,
                        fontSize = 14.sp,
                        lineHeight = 1.6.em,
                        color = Muted,
                        modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                    )
                }
                // The board's tear runs the frame's inner width, `margin: 0 -18px`.
                TornEdge(band = Paper, notch = HeroFill)
            }
            // Solid, not dashed: the dash is the offer's, below (see
            // `dashedOutline`), and one screen with two offers has none.
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .clickable(role = Role.Button, onClick = onOpenSettings)
                    .heightIn(min = 44.dp)
                    .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(OPEN_SETTINGS, style = ButtonLabel.copy(fontSize = 14.5.sp), color = Stamp)
            }
        }
        if (onAddPayment != null) {
            CashOffer(onAddPayment, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 22.dp))
        }
    }
}

/**
 * Day one's way to spec 9.4's **Add payment**, in place of the floating
 * button: the board's dashed offer at the foot.
 *
 * Its name to TalkBack is the words drawn and then [ADD_PAYMENT], so a
 * screen-reader user finds the action the floating button names on every
 * other state, and a voice user can still say what they see (WCAG 2.5.3).
 */
@Composable
private fun CashOffer(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .testTag(CASH_OFFER)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$CASH_OFFER_LABEL. $ADD_PAYMENT" }
            .dashedOutline(Stamp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The board's 17px glyph at stroke 1.9 of 24.
        PlusMark(size = 17.dp, strokeWidth = 1.35.dp, tint = Stamp)
        Text(CASH_OFFER_LABEL, style = ButtonLabel.copy(fontSize = 14.5.sp), color = Stamp)
    }
}

/**
 * The pinned summary, then the rows, under the awaiting-review row when there
 * is one, and the add button where [onAddPayment] offers one.
 *
 * The button sits in a band under the list, not over it: a ledger's amounts
 * and badges are right-aligned, the edge the button is on, so over the list it
 * covered a subtotal or a duplicate badge wherever the list was scrolled
 * (#221). The band is all or nothing, and only where the list keeps at least
 * one [LEDGER_ROW] above it: at 320x640 under the capture-off banner, which
 * every fresh install draws, the list has 56dp in all, and a band would leave
 * it none, which composes no row. There the list takes the whole area and the
 * button floats over it as the board draws it, with room after the last row.
 * Measured, not a breakpoint, so a banner, a font scale or a longer summary
 * moves it too.
 */
@Composable
private fun Feed(
    items: LazyPagingItems<LedgerItem>,
    summary: MonthSummary?,
    categoriesById: Map<Long, Category>,
    uncategorizedId: Long?,
    hasChoices: Boolean,
    onCategorize: (Long) -> Unit,
    onOpenMerchant: (ownKey: String, on: LocalDate) -> Unit,
    awaitingReview: Int,
    awaitingReviewInMonth: Int,
    onOpenReview: (focus: Long?) -> Unit,
    onOpenEntry: ((EditTarget) -> Unit)?,
    onOpenDetail: ((EditTarget) -> Unit)?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onAddPayment: (() -> Unit)? = null,
    backupReminder: BackupReminder? = null,
) {
    Column(modifier) {
        MonthSummaryBlock(summary, categoriesById)
        // A hairline and a change of ground rather than the artboard's
        // perforation: that is `SourcesScreen`'s `TornEdge`, private to that
        // file, and a second copy of its tile geometry is a second thing to
        // keep in step.
        Spacer(Modifier.fillMaxWidth().padding(top = 18.dp).height(1.dp).background(Rule))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val banded = onAddPayment != null && maxHeight - ADD_PAYMENT_ROOM >= LEDGER_ROW
            val floating = onAddPayment != null && !banded
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .background(Card)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = if (floating) ADD_PAYMENT_ROOM else 0.dp),
                ) {
                    // An item and not a header: it scrolls away with the list
                    // rather than holding a line of a 640dp screen for a week.
                    // Above the review row, so that row's pin is not under it.
                    if (backupReminder != null) {
                        item(key = "backup-reminder", contentType = "backup-reminder") {
                            BackupReminderRow(backupReminder)
                        }
                    }
                    // A sticky header, so it stays at the top of the list as it
                    // scrolls, and inside the list's scroll so a test can reach it
                    // with `performScrollTo`. Absent at zero, so it is never noise.
                    if (awaitingReview > 0) {
                        stickyHeader(key = "awaiting-review", contentType = "awaiting-review") {
                            AwaitingReviewRow(pinnedReviewLabel(awaitingReview, awaitingReviewInMonth, summary?.month)) {
                                onOpenReview(null)
                            }
                        }
                    }
                    items(
                        count = items.itemCount,
                        // The header's key names the row it sits above as well as its
                        // day, because a duplicate key throws out of measure: see
                        // `LedgerItem.DayHeader.firstRowId`.
                        key = items.itemKey { item ->
                            when (item) {
                                is LedgerItem.Row -> "txn-" + item.txn.id
                                is LedgerItem.DayHeader ->
                                    "day-" + item.date.yyyymmdd + "-" + item.firstRowId
                            }
                        },
                        contentType = items.itemContentType { item ->
                            when (item) {
                                is LedgerItem.Row -> "row"
                                is LedgerItem.DayHeader -> "header"
                            }
                        },
                    ) { index ->
                        when (val item = items[index]) {
                            // Placeholders are off, so this is only reached if the list
                            // shrinks under the composition. Nothing is the honest draw
                            // for a row that is no longer there.
                            null -> Unit
                            is LedgerItem.DayHeader -> DayHeader(item.date, item.subtotal, first = index == 0)
                            is LedgerItem.Row -> LedgerRow(
                                row = item.feedRow,
                                categoriesById = categoriesById,
                                uncategorizedId = uncategorizedId,
                                canCategorize = hasChoices,
                                onCategorize = onCategorize,
                                onOpenMerchant = onOpenMerchant,
                                onOpenReview = onOpenReview,
                                onOpenEntry = onOpenEntry,
                                onOpenDetail = onOpenDetail,
                            )
                        }
                    }
                }
                // The dotted rule is the screens' footers'.
                if (banded) {
                    Spacer(Modifier.testTag(CASH_BAND).fillMaxWidth().height(ADD_PAYMENT_ROOM).background(Card).dottedRule(atTop = true))
                }
            }
            if (onAddPayment != null) {
                val bottom = if (banded) (ADD_PAYMENT_ROOM - ADD_PAYMENT_SIZE) / 2 else 20.dp
                AddPaymentButton(onAddPayment, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = bottom))
            }
        }
    }
}

/**
 * §9.2's way in: [label], in the accent, opening the inbox. The count is every
 * month's and every currency's, so it can name payments no total here mentions.
 */
@Composable
private fun AwaitingReviewRow(label: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Paper)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MonoLabel, color = Stamp, modifier = Modifier.weight(1f))
        Chevron(pointsRight = true, size = 17.dp, strokeWidth = 1.7.dp, tint = Stamp)
    }
}

/**
 * Spec 9.1's pinned month summary: the total, and the top three categories.
 *
 * Three states, none of which is a zero. A null [summary] is "the aggregate has
 * not landed"; an empty `totals` is "the month has nothing that counts", which
 * happens when every row in it is pending or excluded (§8) and cannot be drawn
 * as `RM0.00` because there is no currency to attach the zero to.
 *
 * [categoriesById] resolves `CategoryTotal`'s `category_id` to the name §9.1
 * asks for -- see [MonthSummary] for why the summary does not carry it.
 */
@Composable
private fun MonthSummaryBlock(summary: MonthSummary?, categoriesById: Map<Long, Category>) {
    val leaderLift = with(LocalDensity.current) { LEADER_LIFT.roundToPx() }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        Text("SPENT SO FAR", style = MonoLabel, color = Muted)

        when {
            summary == null -> Text(
                "READING",
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )

            summary.totals.isEmpty() -> Text(
                "NOTHING COUNTED THIS MONTH",
                style = MonoLabel,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )

            else -> summary.totals.forEach { total ->
                val amount = money(total.netSen, total.currency, symbol = true)
                Text(
                    if (summary.totals.size == 1) heroText(amount) else AnnotatedString(amount),
                    // The serif for a single display figure, the mono the
                    // moment there are two: §8's typography note measures
                    // Instrument Serif's digits at 249-460 units per 1000 em
                    // with no `tnum` feature, so two serif figures stacked
                    // cannot align. A second currency is exactly that stack.
                    style = if (summary.totals.size == 1) HeroSerif else HeroMono,
                    // Greyed, not hidden, when the month has gaps: see
                    // [MonthSummary.trustworthy].
                    color = if (summary.trustworthy) Ink else Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // Drawn over a worded zero as well as over a real total, and never
        // suppressed by either: §8's rule is that a gap in capture is never
        // drawn as a zero, and "NOTHING COUNTED THIS MONTH" is a zero in words
        // -- the state a reader is most likely to read as "nothing spent". A
        // null summary has no trust answer yet and so is not one of them.
        if (summary != null && !summary.trustworthy) {
            Text(
                "DO NOT TRUST" + Separator + "CAPTURE HAS GAPS THIS MONTH",
                style = MonoLabel,
                color = Stamp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        summary?.top?.forEach { category ->
            // On one baseline, as the board's `align-items: baseline` sets
            // them: a Karla name and a Plex amount centred apart measured 3px
            // between their baselines.
            Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                Text(
                    // The id is not a name, and a category the seed does not
                    // hold is a row pointing at a category that has been merged
                    // away -- named as unknown rather than printed as a number.
                    categoriesById[category.categoryId]?.name ?: "Unknown category",
                    fontFamily = Body,
                    fontSize = 13.sp,
                    color = Ink,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .alignBy { it.measuredHeight + leaderLift }
                        .dottedRule(inset = LEADER_INSET),
                )
                Text(
                    money(category.netSen, category.currency, symbol = false),
                    style = MonoNumerals.copy(fontSize = 13.sp),
                    modifier = Modifier.alignByBaseline(),
                    // Greyed with the total above it: the breakdown of a
                    // number §8 says not to trust is not itself trustworthy.
                    color = if (summary.trustworthy) Ink else Muted,
                )
            }
        }
    }
}

/**
 * A day, and its subtotal when there is one.
 *
 * **An absent subtotal draws no number, and never a zero.** The key is absent
 * in two cases the screen cannot tell apart -- the aggregate has not landed,
 * and the day holds nothing that counts (§8's excluded and pending rows) -- and
 * a zero would be a false claim in the first and a claim about arithmetic the
 * rows contradict in the second. No number is true in both.
 *
 * The board's rhythm, with a row's 3dp above and 8dp below it (`LedgerRow`):
 * 8 here and 3 make the 11dp between a heading and its first row, a row's 8
 * and 6 here the 14dp between days, and [first]'s 14 the card's top padding.
 */
@Composable
private fun DayHeader(date: LocalDate, subtotal: List<CurrencyTotal>?, first: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = if (first) 14.dp else 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(date), style = DayLabelStyle, color = Muted, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            subtotal?.forEach { total ->
                Text(
                    money(total.netSen, total.currency, symbol = false),
                    // The board's 11px is under the 12sp floor, and the day
                    // label beside it is already raised to 12: smaller than
                    // its label would turn the board's hierarchy over.
                    style = MonoNumerals.copy(fontSize = 12.sp),
                    color = Muted,
                )
            }
        }
    }
}

/**
 * The pinned row's words: `11 AWAITING REVIEW · 5 IN OCTOBER`. The count is
 * every month's (§9.2) and Charts' is [month]'s, so where they differ the row
 * names its share of [month] rather than read as contradicting Charts (#215).
 * Where every held payment is in [month], or the month has not been read, the
 * suffix would say nothing and is left off.
 */
internal fun pinnedReviewLabel(count: Int, inMonth: Int, month: YearMonth?): String {
    val all = awaitingReviewLabel(count)
    if (month == null || inMonth == count) return all
    return all + Separator + "$inMonth IN " + month.month.getDisplayName(JavaTextStyle.FULL, CALENDAR).uppercase(CALENDAR)
}

/**
 * "WED 30 SEP", from the stored `yyyymmdd`.
 *
 * [CALENDAR] rather than the device locale, so what is drawn does not depend on
 * a setting, and English because every other label in this app is an English
 * string uppercased in the source rather than by a text transform.
 */
internal fun dayLabel(date: LocalDate): String {
    val day = LocalDates.calendarDay(date)
    return (
        day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, CALENDAR) + " " +
            day.dayOfMonth + " " +
            day.month.getDisplayName(JavaTextStyle.SHORT, CALENDAR)
        ).uppercase(CALENDAR)
}

/**
 * The artboard's hero: a single display figure, so the serif is right for it.
 *
 * Built from scratch rather than from [MonoNumerals], which carries `tnum`:
 * Instrument Serif contains no such feature (§8 measured the file), so
 * inheriting the setting would claim tabular digits this face cannot supply.
 */
internal val HeroSerif = TextStyle(fontFamily = Display, fontSize = 54.sp, lineHeight = 1.02.em)

/** The board's `.30`: the sen in a 30px span, in Muted, after the ringgit. */
internal fun heroText(amount: String): AnnotatedString = heroFigure(amount, 30.sp)

/** Two or more totals, which have to align. See the note at the call site. */
private val HeroMono = MonoNumerals.copy(fontSize = 28.sp, lineHeight = 34.sp)

/** The artboard's day heading: the small-caps voice with its widest tracking. */
private val DayLabelStyle = MonoLabel.copy(letterSpacing = 1.4.sp)

/**
 * How far the leader between a category and its amount is held back from the
 * text on either side of it, which is all that distinguishes it from
 * [dottedRule]'s other uses.
 */
private val LEADER_INSET = 8.dp

/**
 * How far the leader's centre stands above the baseline it shares with its
 * text: the board's `margin-bottom: 4px` under a 1px border.
 */
private val LEADER_LIFT = 4.5.dp

/**
 * Spec 9.4's **Add payment**, the artboard's cash button: a + over "CASH" at
 * the bottom right of the Spending tab, in a band under the list. Named
 * [ADD_PAYMENT] to TalkBack, since neither the + nor "CASH" says what it does.
 *
 * "CASH" is [MonoLabel] at its 12sp floor, not the board's 8px; the + and the
 * label stack 43dp at a 1.3 font scale, inside the 54dp.
 */
@Composable
private fun AddPaymentButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .testTag(ADD_PAYMENT_BUTTON)
            .size(ADD_PAYMENT_SIZE)
            .clip(RoundedCornerShape(2.dp))
            .background(Stamp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = ADD_PAYMENT },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        // The board's 20px glyph at stroke 2 of 24.
        PlusMark(size = 20.dp, strokeWidth = 1.67.dp, tint = Paper)
        Text("CASH", style = MonoLabel, color = Paper, maxLines = 1)
    }
}

/** The board's button, 54px square. */
private val ADD_PAYMENT_SIZE = 54.dp

/** The band under the list: the button and 16dp above and below it. */
private val ADD_PAYMENT_ROOM = 86.dp

/** A categorised ledger row as drawn, 57dp (#189): the least list the band leaves. */
private val LEDGER_ROW = 57.dp

/** The empty ledger's way to Settings, under its copy; the offer at its foot is the other. */
internal const val OPEN_SETTINGS = "Open settings"

/** Day one's offer, as drawn: its first words to TalkBack too. */
internal const val CASH_OFFER_LABEL = "Or start with a cash expense"

/** Test tags, for the elements a test measures the drawing of. */
const val ADD_PAYMENT_BUTTON = "add-payment-button"

/** The band under the list the add button sits in, where there is room for one. */
const val CASH_BAND = "cash-band"
const val CASH_OFFER = "cash-offer"
const val EMPTY_HERO = "empty-hero"
const val HEARD_CHIP = "heard-chip"
const val MONTH_BUTTON = "month-button"
const val MONTH_CHEVRON = "month-chevron"

/** The month button's action, to TalkBack; Charts' header says the same. */
internal const val CHOOSE_A_MONTH = "Choose a month"
