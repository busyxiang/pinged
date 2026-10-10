package my.pinged.ledger.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import my.pinged.data.Databases
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.dao.RetroPreview
import my.pinged.data.entity.Category
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.monthName
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Card
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Separator
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle

/**
 * Spec 9.1's transaction list, drawn from `design/Main.dc.html`.
 *
 * **The artboard's bottom navigation is not drawn here**: `MainActivity` hosts
 * it, because it is shared with settings and a tab switch is not this screen's
 * to decide. Its charts tab and its cash button have no screen to lead to.
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
) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val read by viewModel.read.collectAsState()
    val merchantSheet by viewModel.merchantSheet.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()

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
        onOpenSettings = onOpenSettings,
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
    retroPreview: suspend (txnId: Long, categoryId: Long) -> RetroPreview = { _, _ -> RetroPreview(0, emptyList()) },
    merchantSheet: MerchantSheetState? = null,
    merchantActions: MerchantActions = MerchantActions.None,
) {
    // Which row is being categorized, hoisted to here rather than held in the
    // row: a `ModalBottomSheet` emitted from inside the `LazyColumn` scrolls
    // out of the viewport and is disposed with the item that owns it.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }

    // Keyed on the list, because a `LazyColumn` recomposes as it scrolls and
    // rebuilding a fourteen-entry map per frame is work for nothing.
    val categoriesById = remember(read.categories) { read.categories.associateBy { it.id } }

    val chooser = remember(read) { read.chooser }
    val choices = remember(chooser.categories, chooser.uncategorizedId) { chooserChoices(chooser) }

    Column(modifier.fillMaxSize().background(Paper)) {
        // Above the branch: the month being summarised is true in every state,
        // including the two with nothing to show.
        TopBar(month = read.summary?.month ?: YearMonth.now())

        when {
            // First, because it is the only state where the ledger is certainly
            // unreadable rather than empty. An empty list here would assert
            // "you spent nothing" when the truth is "this cannot be read"
            // (§11.1). Either read may be the one that found out: `refresh`
            // reports it through `CaptureStorage.guarded`, the feed as a
            // refresh error (see `ReopeningFeed`). Any refresh error, not only
            // §11.1's: a page that would not load is a list that could not be
            // read either way.
            storageUnavailable || items.loadState.refresh is LoadState.Error ->
                Unreadable(Modifier.weight(1f))

            // Not empty until the first load has actually returned. `loadState`
            // distinguishes them; a bare `itemCount == 0` does not.
            items.loadState.refresh is LoadState.Loading -> Reading(Modifier.weight(1f))

            items.itemCount == 0 -> Empty(onOpenSettings, Modifier.weight(1f))

            else -> Feed(
                items = items,
                subtotals = read.daySubtotals,
                summary = read.summary,
                categoriesById = categoriesById,
                uncategorizedId = read.uncategorizedId,
                hasChoices = choices.isNotEmpty(),
                onCategorize = { txnId -> categorizing = txnId },
                onOpenMerchant = merchantActions.open,
                modifier = Modifier.weight(1f),
            )
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
        )
    }

    // A sibling for the same reason as the picker. Its state is the holder's,
    // not saved here: it is a read of the database, and after a process death
    // it would be a read of a ledger that may have moved.
    if (merchantSheet != null) MerchantSheet(merchantSheet, merchantActions)
}

/**
 * The month being summarised.
 *
 * The month comes from the summary rather than the clock so the label cannot
 * name a period the numbers below it did not cover; before the first aggregate
 * lands there is no number to label and it names the current month.
 */
@Composable
private fun TopBar(month: YearMonth) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monthName(month), style = MonoLabel, color = Muted, modifier = Modifier.weight(1f))
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
 */
@Composable
private fun Empty(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Text("NOTHING CAPTURED YET", style = MonoLabel, color = Muted)
        Text(
            "Nothing is missing. There is just nothing yet.",
            fontFamily = Display,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            color = Ink,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "Android will not hand over notifications from before you switched " +
                "Pinged on, so your paper trail starts here. A payment lands in " +
                "this list on its own once the app it comes from is switched on " +
                "for capture.",
            fontFamily = Body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Ink,
            modifier = Modifier.padding(top = 12.dp),
        )
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
            Text("OPEN SETTINGS", style = MonoLabel, color = Stamp)
        }
    }
}

/** The pinned summary, then the rows. */
@Composable
private fun Feed(
    items: LazyPagingItems<LedgerItem>,
    subtotals: Map<LocalDate, List<CurrencyTotal>>,
    summary: MonthSummary?,
    categoriesById: Map<Long, Category>,
    uncategorizedId: Long?,
    hasChoices: Boolean,
    onCategorize: (Long) -> Unit,
    onOpenMerchant: (ownKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        MonthSummaryBlock(summary, categoriesById)
        // A hairline and a change of ground rather than the artboard's
        // perforation: that is `SourcesScreen`'s `TornEdge`, private to that
        // file, and a second copy of its tile geometry is a second thing to
        // keep in step.
        Spacer(Modifier.fillMaxWidth().padding(top = 18.dp).height(1.dp).background(Rule))
        LazyColumn(
            Modifier
                .weight(1f)
                .background(Card)
                .fillMaxWidth(),
        ) {
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
                    is LedgerItem.DayHeader -> DayHeader(item.date, subtotals[item.date])
                    is LedgerItem.Row -> LedgerRow(
                        row = item.feedRow,
                        categoriesById = categoriesById,
                        uncategorizedId = uncategorizedId,
                        canCategorize = hasChoices,
                        onCategorize = onCategorize,
                        onOpenMerchant = onOpenMerchant,
                    )
                }
            }
        }
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
                Text(
                    money(total.netSen, total.currency, symbol = true),
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
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // The id is not a name, and a category the seed does not
                    // hold is a row pointing at a category that has been merged
                    // away -- named as unknown rather than printed as a number.
                    categoriesById[category.categoryId]?.name ?: "Unknown category",
                    fontFamily = Body,
                    fontSize = 13.sp,
                    color = Ink,
                )
                Spacer(Modifier.weight(1f).height(1.dp).dottedRule(inset = LEADER_INSET))
                Text(
                    money(category.netSen, category.currency, symbol = false),
                    style = MonoNumerals.copy(fontSize = 13.sp),
                    color = Ink,
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
 */
@Composable
private fun DayHeader(date: LocalDate, subtotal: List<CurrencyTotal>?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(date), style = DayLabelStyle, color = Muted, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            subtotal?.forEach { total ->
                Text(
                    money(total.netSen, total.currency, symbol = false),
                    style = MonoNumerals.copy(fontSize = 13.sp),
                    color = Muted,
                )
            }
        }
    }
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
private val HeroSerif = TextStyle(fontFamily = Display, fontSize = 44.sp, lineHeight = 48.sp)

/** Two or more totals, which have to align. See the note at the call site. */
private val HeroMono = MonoNumerals.copy(fontSize = 28.sp, lineHeight = 34.sp)

/** The artboard's day heading: the small-caps voice with its widest tracking. */
private val DayLabelStyle = MonoLabel.copy(letterSpacing = 1.4.sp)

/**
 * How far the leader between a category and its amount is held back from the
 * text on either side of it, which is all that distinguishes it from
 * [dottedRule]'s other uses.
 */
private val LEADER_INSET = 6.dp
