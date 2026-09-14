package my.pinged.ledger.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.dao.CurrencyTotal
import my.pinged.data.entity.Category
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.ledger.R
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ledger.theme.Card
import my.pinged.ledger.theme.Display
import my.pinged.ledger.theme.Faint
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.MonoNumerals
import my.pinged.ledger.theme.Muted
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.Rule
import my.pinged.ledger.theme.Separator
import my.pinged.ledger.theme.Stamp
import my.pinged.ledger.theme.dottedRule
import my.pinged.parse.Direction
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Spec 9.1's transaction list, drawn from `design/Main.dc.html`.
 *
 * **The artboard's bottom navigation is not here**, and neither is its cash
 * button: two of its three tabs (charts, settings) do not exist, and it would
 * give the allow-list -- touched about once -- the same prominence as the
 * screen looked at daily. The top-bar control is the route there instead.
 *
 * The category is spelled out in the line beneath the merchant as well as drawn
 * as an icon ([RowIcon]), so a reader who cannot tell two 18dp glyphs apart has
 * lost nothing (WCAG 1.4.1); §4 lets the user re-icon a category, so the name
 * is the half that cannot drift from what the row is filed under.
 *
 * The "+ CATEGORY" chip assigns a category directly: §6.1's learned rules are
 * out of this milestone, so the tap writes one `category_id` -- and
 * `user_edited`, the record that a person decided (§5.5) -- and teaches
 * nothing. That is why a row that already has a category is not tappable: a
 * chip on every row would imply the deferred half is here.
 */
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val read by viewModel.read.collectAsState()
    val unavailable by viewModel.storageUnavailable.collectAsState()

    // Once per foreground, not once per holder -- see `SourcesScreen`, which
    // records the mechanism. Firing once per holder would leave the aggregates
    // never re-read: a payment captured while the app was away appears as a row
    // -- the insert invalidates the `PagingSource` -- above a day header and a
    // month total that do not count it.
    LifecycleResumeEffect(viewModel) {
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
        onAssign = { txnId, categoryId -> viewModel.assignCategory(txnId, categoryId) },
        onOpenSources = onOpenSources,
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
    onAssign: (txnId: Long, categoryId: Long) -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which row is being categorized, hoisted to here rather than held in the
    // row: a `ModalBottomSheet` emitted from inside the `LazyColumn` scrolls
    // out of the viewport and is disposed with the item that owns it.
    var categorizing by rememberSaveable { mutableStateOf<Long?>(null) }

    // Keyed on the list, because a `LazyColumn` recomposes as it scrolls and
    // rebuilding a fourteen-entry map per frame is work for nothing.
    val categoriesById = remember(read.categories) { read.categories.associateBy { it.id } }

    // Everything but the category the row is already in. `Uncategorized` would
    // read as the "never mind" tap and is not a no-op: `setCategory` writes
    // `user_edited = 1`, and §5.5 excludes an edited row from the re-parse
    // comparison for good, so one stray tap would opt that transaction out of
    // every future pack fix while nothing on screen changed. Removed from the
    // list rather than refused at the write, so the whole class goes with it.
    // Both halves come off one [LedgerRead], which is what keeps the filter
    // from being handed a new list beside a stale id.
    val choices = remember(read.categories, read.uncategorizedId) {
        read.categories.filter { it.id != read.uncategorizedId }
    }

    Column(modifier.fillMaxSize().background(Paper)) {
        // Above the branch: the month being summarised and the way out to the
        // allow-list are true in every state, including the two with nothing to
        // show -- which are the states a user most needs that route from.
        TopBar(month = read.summary?.month ?: YearMonth.now(), onOpenSources = onOpenSources)

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

            items.itemCount == 0 -> Empty(onOpenSources, Modifier.weight(1f))

            else -> Feed(
                items = items,
                subtotals = read.daySubtotals,
                summary = read.summary,
                categoriesById = categoriesById,
                uncategorizedId = read.uncategorizedId,
                hasChoices = choices.isNotEmpty(),
                onCategorize = { txnId -> categorizing = txnId },
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
    val chosen = categorizing
    if (chosen != null && choices.isNotEmpty()) {
        CategoryPicker(
            categories = choices,
            onChoose = { categoryId ->
                categorizing = null
                onAssign(chosen, categoryId)
            },
            onDismiss = { categorizing = null },
        )
    }
}

/**
 * The month being summarised, and the way to the allow-list.
 *
 * The month comes from the summary rather than the clock so the label cannot
 * name a period the numbers below it did not cover; before the first aggregate
 * lands there is no number to label and it names the current month.
 */
@Composable
private fun TopBar(month: YearMonth, onOpenSources: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monthLabel(month), style = MonoLabel, color = Muted, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                // A labelled control, so it reports as a button and takes a
                // 44dp target -- see `SourcesScreen`'s `Header`.
                .clickable(role = Role.Button, onClick = onOpenSources)
                .heightIn(min = 44.dp)
                // `Faint`, not the artboard's `Border`: that is 1.35:1 on
                // Paper, and WCAG 1.4.11 asks 3:1 of a component. Same
                // overrule, for the same reason, as the capture toggle.
                .border(1.dp, Faint, RoundedCornerShape(2.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("SOURCES", style = MonoLabel, color = Ink)
        }
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
 * whole `NavDisplay` carries §11.1's full account when §11.1 is what happened.
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
            "Pinged could not read your transactions. Nothing already " +
                "recorded has been deleted -- so this list is empty because it " +
                "could not be read, not because there is nothing in it. The " +
                "banner above says more when Pinged knows more.",
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
 * different facts and only one of them is knowable here, which is why the
 * route to the allow-list is part of this state rather than decoration.
 */
@Composable
private fun Empty(onOpenSources: () -> Unit, modifier: Modifier = Modifier) {
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
                .clickable(role = Role.Button, onClick = onOpenSources)
                .heightIn(min = 44.dp)
                .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("CHOOSE CAPTURE SOURCES", style = MonoLabel, color = Stamp)
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
                    is LedgerItem.Row -> TxnRow(
                        txn = item.txn,
                        category = categoriesById[item.txn.categoryId],
                        // A fact about the row, and nothing else: the row §7.1's
                        // gate could not file. A null [uncategorizedId] -- the
                        // seed row is gone -- equals no row's `category_id`,
                        // which is the honest answer there: nothing knows which
                        // rows are uncategorized, so no row is claimed to be.
                        uncategorized = item.txn.categoryId == uncategorizedId,
                        // Whether the chip can be *offered* is the separate
                        // question, asked by handing over an action or not.
                        // Each extra gate would otherwise be a chip that takes
                        // a tap and does nothing: no `hasChoices` leaves the
                        // sheet with nothing to open, and an excluded row is
                        // out of every total (§7.3), so a category assigned to
                        // it changes no number on any screen.
                        onCategorize = if (
                            hasChoices &&
                            !item.txn.isExcluded &&
                            item.txn.categoryId == uncategorizedId
                        ) {
                            { onCategorize(item.txn.id) }
                        } else {
                            null
                        },
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
                    money(category.netSen, category.currency),
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
                    money(total.netSen, total.currency),
                    style = MonoNumerals.copy(fontSize = 13.sp),
                    color = Muted,
                )
            }
        }
    }
}

/**
 * One transaction. Spec 9.1's merchant, source and amount.
 *
 * **A `PENDING` row and an excluded row are both drawn and both badged.**
 * Neither counts toward any total (§8), and there is no review inbox in this
 * milestone (§9.2 is not built), so a `PENDING` row this list hid would be
 * money in the database and nowhere on screen at all.
 *
 * An excluded row is struck through and badged, and greyed to [Muted] -- not to
 * [Faint], which measures 3.12:1 on this ground and is reserved by its own
 * documentation for a component's states rather than for text. The
 * strikethrough and the badge are what carry the meaning anyway, so a reader
 * who sees no colour still reads it (WCAG 1.4.1).
 *
 * The small print under the merchant is the artboard's "Groceries · Touch 'n
 * Go": the category the money is filed under, then the app it was read from.
 * On an [uncategorized] row the name is dropped -- "Uncategorized" is where the
 * row is filed, not what it is about, and the row's own mark already says so --
 * and the chip stands in its place when there is one. On an excluded row the
 * category is dropped too: §7.3 takes the row out of every total, so which
 * category it is filed under is a fact about nothing. The leading slot is
 * [RowIcon].
 *
 * **[uncategorized] is the row's state and [onCategorize] is the offer, and
 * they must stay separate.** Collapsed into one flag, a screen with nothing to
 * offer draws a genuinely uncategorized row as a categorized one:
 * "Uncategorized · GrabPay" in the small print, under the muted `circle-dashed`
 * glyph instead of the accent mark.
 */
@Composable
private fun TxnRow(
    txn: Txn,
    category: Category?,
    uncategorized: Boolean,
    onCategorize: (() -> Unit)?,
) {
    val excluded = txn.isExcluded
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowIcon(excluded = excluded, uncategorized = uncategorized, iconKey = category?.iconKey)
        Column(Modifier.weight(1f)) {
            Text(
                // `merchant_key` is not offered as a fallback: it is the
                // grouping identity, uppercased and stripped, not a name.
                txn.merchantDisplay ?: txn.merchantRaw ?: "Unknown merchant",
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                color = if (excluded) Muted else Ink,
                // Not struck through, though the amount is: the number is the
                // thing §7.3 says does not count, and striking both reads as
                // deleted, which is exactly what an excluded row is not.
            )
            val source = txn.sourceLabel ?: txn.sourcePackage
            // One `Text`, so the line reads as one line to a screen reader
            // rather than as fragments. The leading separator belongs only to
            // the chip: in front of a source with no category before it, "·"
            // is punctuation for a name that is not there.
            val name = category?.name.takeUnless { uncategorized || excluded }
            val line = listOfNotNull(name, source).joinToString(Separator)
            // No row at all when there is neither chip nor line, rather than an
            // empty one: `source_package` and `source_label` are both nullable
            // -- an imported row can have neither -- and the 2.dp would then be
            // a gap under the merchant holding a line that is not there.
            if (onCategorize != null || line.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onCategorize != null) {
                        CategoryChip(onClick = onCategorize)
                    }
                    if (line.isNotEmpty()) {
                        Text(
                            (if (onCategorize != null) Separator else "") + line,
                            style = MonoLabel,
                            color = Muted,
                        )
                    }
                }
            }
            if (txn.state == TxnState.PENDING) {
                // The reason, not just the state: §9.2 will offer actions per
                // reason, and until it exists this is the only place the user
                // can see which of §7.1's gates fired.
                Badge("PENDING" + Separator + words(txn.pendingReason?.name), Stamp)
            }
            if (excluded) {
                Badge("NOT SPENDING" + Separator + words(txn.exclusionReason?.name), Muted)
            }
        }
        Text(
            // A refund is signed in every total (it reduces its day), so it is
            // signed here too: two rows of the same size, one money out and one
            // money back, are otherwise identical on screen.
            (if (txn.direction == Direction.REFUND) "+" else "") +
                money(txn.amountSen, txn.currency),
            style = MonoNumerals.copy(fontSize = 15.sp),
            color = if (excluded) Muted else Ink,
            textDecoration = if (excluded) TextDecoration.LineThrough else null,
        )
    }
}

/** The chip's label, named so the screen and its test cannot spell it differently. */
internal const val CATEGORY_CHIP = "+ CATEGORY"

/**
 * The artboard's "+ CATEGORY": the one control on a row, and the only thing on
 * this screen that writes.
 *
 * Drawn in [Stamp], the accent this app reserves for the state that wants
 * acting on, with the artboard's dashed rule hand-drawn by [dashedOutline].
 *
 * [Role.Button] so it reports as one: `SourcesScreen`'s `Header` records what a
 * tappable-looking thing with no semantics cost.
 */
@Composable
private fun CategoryChip(onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick)
            // Touch first, then the drawn box. The padding here is inside the
            // clickable and outside `dashedOutline`, so it enlarges what can be
            // tapped without enlarging what is drawn: the outline is painted in
            // this node's own bounds, which exclude padding applied before it.
            //
            // Not `heightIn(min = 44.dp)` like the other targets in this
            // feature: that makes the box itself 44dp and an uncategorized row
            // visibly taller than every other row. 44dp is WCAG 2.5.5's AAA
            // target; 2.5.8's AA minimum is 24dp, which the 6dp either side
            // clears.
            .padding(vertical = 6.dp)
            .dashedOutline()
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(CATEGORY_CHIP, style = MonoLabel, color = Stamp)
    }
}

/**
 * The artboard's `1px dashed` chip border, in the accent, with the chip's 2dp
 * corner.
 *
 * `Modifier.border` draws a solid stroke and takes no dash pattern, so the
 * outline is drawn here -- the same `PathEffect` route `theme`'s [dottedRule]
 * takes for its hairlines. Inset by half the stroke, or a stroke centred on the
 * bounds would have its outer half clipped away and read thinner than the
 * hairline beside it.
 */
private fun Modifier.dashedOutline(): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = Stamp,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3.dp.toPx(), 2.dp.toPx()),
            ),
        ),
    )
}

/** What the row's leading slot draws, and whether it draws it in the accent. */
internal data class RowMark(@DrawableRes val icon: Int, val accent: Boolean)

/**
 * Which mark the row's leading slot holds, or null for an empty slot.
 *
 * A function rather than a `when` inside [RowIcon], for the reason
 * [dayHeaderBetween] is one: a decorative icon carries no `contentDescription`,
 * so through a composition this rule can only be asserted on a picture.
 * `RowMarkRuleTest` asserts it directly.
 *
 * **The order of the first two branches is the rule, and nothing upstream
 * enforces it.** [uncategorized] is the row's own state, so both arguments
 * arrive true for a row that is excluded *and* unfiled -- and it must read as
 * not counted: [TxnRow] is handed no action for such a row, and an accent "add
 * a category" mark over a chip that is not drawn would point at a control that
 * is not there. `RowMarkRuleTest` asserts the pair directly, because that state
 * has no separate spelling on screen to assert on.
 *
 * Only the third branch is the category's own icon, resolved from the category
 * row at render time as §4 requires.
 */
internal fun rowMark(excluded: Boolean, uncategorized: Boolean, iconKey: String?): RowMark? =
    when {
        excluded -> RowMark(R.drawable.ic_row_not_counted, accent = false)
        uncategorized -> RowMark(R.drawable.ic_row_uncategorized, accent = true)
        else -> iconKey?.let(::categoryIcon)?.let { RowMark(it, accent = false) }
    }

/**
 * The row's leading 18dp slot, drawn at [Muted] like the artboard's.
 *
 * The slot keeps its width whatever [rowMark] answers, so the merchant names
 * below stay on one left edge. A key this build does not bundle draws
 * **nothing** rather than a stand-in -- see [categoryIcon].
 *
 * Decorative, so no `contentDescription`: everything it encodes is already
 * written beside it -- the category in the line under the merchant, the
 * exclusion in the badge below that -- and announcing it would read the row
 * twice.
 */
@Composable
private fun RowIcon(excluded: Boolean, uncategorized: Boolean, iconKey: String?) {
    val mark = rowMark(excluded, uncategorized, iconKey)
    Box(Modifier.size(18.dp)) {
        if (mark != null) {
            Icon(
                painter = painterResource(mark.icon),
                contentDescription = null,
                // The artboard draws every row's glyph at Muted and the
                // uncategorized one in the accent, pairing it with the chip
                // below so the two read as one invitation.
                tint = if (mark.accent) Stamp else Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A framed mono label, in the colour of whatever it is qualifying. */
@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MonoLabel,
        color = color,
        modifier = Modifier
            .padding(top = 4.dp)
            .border(1.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** `RULE_REVIEW` as "RULE REVIEW", and an absent reason as nothing. */
private fun words(name: String?): String = name?.replace('_', ' ') ?: "REASON NOT RECORDED"

/**
 * Sen as a string, with the grouping the artboard draws.
 *
 * `Long` arithmetic throughout: this is a display path and spec 4's money is
 * `Long` sen, so nothing here converts through a `Double`.
 *
 * **Bare digits mean ringgit and a non-MYR figure names its currency.** The
 * hero total prints its symbol, every column below it does not -- which is only
 * honest while every figure on the screen is in the same currency, and `txn`
 * has a `currency` column precisely because that will stop being true (§4.5).
 *
 * `Locale.ROOT`, not the device locale: the grouping separator is part of a
 * string the UI test matches exactly, and a device that groups with a full stop
 * would turn a green test red for a reason that has nothing to do with money.
 */
private fun money(sen: Long, currency: String, symbol: Boolean = false): String {
    val sign = if (sen < 0) "-" else ""
    val magnitude = abs(sen)
    val digits = String.format(Locale.ROOT, "%,d", magnitude / 100) +
        "." + String.format(Locale.ROOT, "%02d", magnitude % 100)
    return when {
        currency != "MYR" -> "$currency $sign$digits"
        symbol -> "${sign}RM$digits"
        else -> sign + digits
    }
}

/**
 * "SEPTEMBER 2026".
 *
 * **[CALENDAR], which is `Locale.ENGLISH` and deliberately not `Locale.ROOT`.**
 * ROOT carries no calendar names: `Month.getDisplayName(FULL, Locale.ROOT)`
 * answers "M09", and this shipped to the emulator and drew "M09 2026" across
 * the top of the ledger. Measured by looking at the screen, which is the only
 * thing that would have caught it -- the test built its expected string from
 * this same function and passed.
 */
private fun monthLabel(month: YearMonth): String =
    month.month.getDisplayName(JavaTextStyle.FULL, CALENDAR).uppercase(CALENDAR) +
        " " + month.year

/**
 * "WED 30 SEP", from the stored `yyyymmdd`.
 *
 * [CALENDAR] rather than the device locale, so what is drawn does not depend on
 * a setting, and English because every other label in this app is an English
 * string uppercased in the source rather than by a text transform.
 */
private fun dayLabel(date: LocalDate): String {
    val day = LocalDates.calendarDay(date)
    return (
        day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, CALENDAR) + " " +
            day.dayOfMonth + " " +
            day.month.getDisplayName(JavaTextStyle.SHORT, CALENDAR)
        ).uppercase(CALENDAR)
}

/**
 * The locale the month and day names are spelled in. See [monthLabel] for what
 * `Locale.ROOT` does here.
 */
private val CALENDAR: Locale = Locale.ENGLISH

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
