package my.pinged.charts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.BarOpens
import my.pinged.charts.model.Bars
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.Chip
import my.pinged.charts.model.Hero
import my.pinged.charts.model.chartsState
import my.pinged.read.rememberReadState
import my.pinged.ui.monthName
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.REPLACING_YOUR_DATA
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonthChevron
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.ReadingLayout
import my.pinged.ui.theme.ReadingStatus
import my.pinged.ui.theme.Rule
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.ValueDash
import my.pinged.ui.theme.heroFigure
import my.pinged.ui.theme.NOT_READ_YET
import my.pinged.ui.theme.UpdatingStatus
import my.pinged.ui.theme.rememberUpdating
import my.pinged.ui.theme.updating
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Charts tab (spec 9.3, #81): the selected month's header and hero, and
 * below them the month's sections in one vertical scroll.
 *
 * Re-read on each foreground, and when a delete or a restore rewrites the
 * ledger while the screen shows (`Databases.rewrites`), by `rememberReadState`.
 *
 * [onDayClick] is a grid square's tap (#69); `:app` routes it to `Day`, which
 * this module knows nothing of (#70). [onOpenReview] is the "not in the
 * total" block's *N awaiting review* line, which `:app` routes to the review
 * inbox the same way. [onOpenUncategorized] is the Uncategorized bar
 * (#273): Search over the month drawn, filtered to [uncategorizedId], which
 * `:app` routes to `:feature:ledger` the same way. [onOpenBreakdown] is any
 * other category's bar, or a line of the `Everything else` sheet (#342):
 * that category's month by merchant, routed the same way. [onOpenMerchant]
 * is a `WHO GOT IT` row, or a line of the `N more merchants` sheet (#344):
 * that merchant identity's month, routed the same way.
 */
@Composable
fun ChartsScreen(
    viewModel: ChartsViewModel,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
    onOpenReview: () -> Unit = {},
    onOpenUncategorized: ((month: YearMonth, uncategorizedId: Long) -> Unit)? = null,
    onOpenBreakdown: ((month: YearMonth, categoryId: Long) -> Unit)? = null,
    onOpenMerchant: ((month: YearMonth, identityKey: String) -> Unit)? = null,
) {
    // Reads on each resume and on `Databases.rewrites`: `rememberReadState` owns
    // both triggers, and what the screen shows while a read is in flight.
    val read = viewModel.read.rememberReadState()
    val month by viewModel.read.question.collectAsState()
    // Saveable, so a sheet open at a process death is open again after it.
    var monthsOpen by rememberSaveable { mutableStateOf(false) }
    ChartsScreenContent(
        month = month,
        state = chartsState(read.answer),
        unavailable = read.unavailable,
        replacing = read.replacing,
        // A month picked whose read has not landed.
        rereading = read.rereading,
        modifier = modifier,
        onDayClick = onDayClick,
        onOpenMonths = { monthsOpen = true },
        onOpenReview = onOpenReview,
        // The read's month and id, not the selection's: the bar drawn is
        // the read's, and a newer selection may not have landed yet.
        onOpenUncategorized = onOpenUncategorized?.let { open ->
            read.answer?.let { r -> r.uncategorizedId?.let { id -> { open(r.month, id) } } }
        },
        // The read's month, for the same reason.
        onOpenBreakdown = onOpenBreakdown?.let { open -> read.answer?.let { r -> { id: Long -> open(r.month, id) } } },
        onOpenMerchant = onOpenMerchant?.let { open -> read.answer?.let { r -> { key: String -> open(r.month, key) } } },
    )
    if (monthsOpen) {
        MonthsSheetHost(
            viewModel,
            onPick = {
                monthsOpen = false
                viewModel.select(it)
            },
            onDismiss = { monthsOpen = false },
        )
    }
}

/**
 * The screen from its state, with no holder, so a test can draw any state.
 *
 * **One slot per section, in scroll order.** A section a later ticket adds
 * goes in its own slot in the `Month` branch, drawn by its own composable from
 * its own field of `MonthView`, and nothing here is shared between slots
 * beyond the column.
 *
 * The `Everything else` sheet is drawn from [state]'s own fold, so its lines
 * are the bar's, and only while the bars drawn have one: a read that no
 * longer folds closes it. The `N more merchants` sheet is drawn from
 * [state]'s own rest by the same rule (#344).
 */
@Composable
internal fun ChartsScreenContent(
    month: YearMonth,
    state: ChartsState,
    unavailable: Boolean,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    onDayClick: (LocalDate) -> Unit = {},
    onOpenMonths: () -> Unit = {},
    onOpenReview: () -> Unit = {},
    onOpenUncategorized: (() -> Unit)? = null,
    onOpenBreakdown: ((categoryId: Long) -> Unit)? = null,
    onOpenMerchant: ((identityKey: String) -> Unit)? = null,
    /** [state] is a month other than [month], whose read is in flight (#285). */
    rereading: Boolean = false,
    /** A ledger replacement turned the read away (#328): [REPLACING_YOUR_DATA] in place of anything read. */
    replacing: Boolean = false,
) {
    val monthName = monthName(month)
    // Saveable, as `Months`' flag is, so a sheet open at a process death is open after it.
    var foldOpen by rememberSaveable { mutableStateOf(false) }
    var restOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        when {
            // Before the state, which may still hold an older read: a screen
            // that cannot read says so rather than drawing stale figures.
            unavailable || replacing -> {
                HeaderSection(monthName, chip = null, onOpenMonths)
                Unreadable(replacing)
            }

            // The month name is the selection, not a read, and opens
            // `Months` at once; the rest waits out `ReadingLayout`'s delay.
            state is ChartsState.Reading -> {
                HeaderSection(monthName, chip = null, onOpenMonths, chipNotReadYet = true)
                ChartsNotReadYet(month, today)
            }

            state is ChartsState.Empty -> {
                HeaderSection(state.month, chip = null, onOpenMonths)
                EmptySection(state)
            }

            state is ChartsState.Month -> {
                val view = state.view
                // A month picked and not read yet (#285): the last month
                // stays, dimmed under `UPDATING`, below the month picked,
                // whose chip is a count not read yet.
                val updating = rememberUpdating(rereading)
                // Slot: header (#86). The month name opens `Months` (#93).
                HeaderSection(
                    if (updating) monthName else view.header.month,
                    if (updating) Chip(NOT_READ_YET, alert = false) else view.header.chip,
                    onOpenMonths,
                )
                Column(Modifier.updating(updating)) {
                    // Slot: hero (#86).
                    HeroSection(view.hero)
                    // Slot: comparison line (#94), under the hero.
                    ComparisonLine(view.comparison)
                }
                if (updating) UpdatingStatus(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
                Column(Modifier.updating(updating)) {
                    RhythmGridSection(view.grid, onDayClick)
                    // Category bars, or collecting (#91): one block stands in
                    // for both the bars and the merchants while the gate is shut.
                    if (view.collecting.open) {
                        CategoryBars(
                            view.bars,
                            onOpenUncategorized = onOpenUncategorized,
                            onOpenBreakdown = onOpenBreakdown,
                            onOpenFolded = onOpenBreakdown?.let { { foldOpen = true } },
                        )
                    } else {
                        CollectingBlock(view.collecting)
                    }
                    // Top merchants (#92), behind the same gate.
                    if (view.collecting.open) {
                        TopMerchants(view.merchants, onOpenMerchant = onOpenMerchant, onOpenRest = onOpenMerchant?.let { { restOpen = true } })
                    }
                    NotInTotalBlock(view.notInTotal, onOpenReview = onOpenReview)
                }
            }
        }
    }
    val shown = (state as? ChartsState.Month)?.view?.takeIf { it.collecting.open && !unavailable && !replacing }
    val drawn = shown?.bars as? Bars.Drawn
    val fold = drawn?.bars?.firstOrNull { it.opens is BarOpens.Folded }
    val folded = (fold?.opens as? BarOpens.Folded)?.categories
    // Closed by a read that landed with nothing folded, not by one in flight,
    // so a sheet restored after a process death waits for its read.
    LaunchedEffect(shown, fold) { if (shown != null && fold == null) foldOpen = false }
    if (foldOpen && fold != null && folded != null && onOpenBreakdown != null) {
        EverythingElseSheet(
            month = shown.header.month,
            folded = folded,
            amount = fold.amount,
            greyed = drawn.greyed,
            onOpen = { id ->
                foldOpen = false
                onOpenBreakdown(id)
            },
            onDismiss = { foldOpen = false },
        )
    }
    val rest = shown?.merchants?.rest
    LaunchedEffect(shown, rest) { if (shown != null && rest == null) restOpen = false }
    if (restOpen && rest != null && onOpenMerchant != null) {
        MoreMerchantsSheet(
            month = shown.header.month,
            rest = rest,
            greyed = shown.merchants.greyed,
            onOpen = { key ->
                restOpen = false
                onOpenMerchant(key)
            },
            onDismiss = { restOpen = false },
        )
    }
}

/**
 * The first read is in flight: `design/LoadingCharts.dc.html`, or
 * `LoadingWaiting`'s status line while an export or the integrity check
 * holds the database. Deliberately not the empty copy.
 *
 * Only what is known without a read is drawn: the section labels and the
 * month's days. Every figure is a dash, every name a dotted leader, and every
 * day a dotted cell, so nothing here can be taken for an amount, a zero, or a
 * day **Not captured**.
 */
@Composable
private fun ChartsNotReadYet(month: YearMonth, today: LocalDate) {
    ReadingLayout(Modifier.fillMaxWidth()) {
        Column {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                // Whether the net is spending is a read; that the month is
                // still running is not.
                MonoLine(if (month == YearMonth.from(today)) "SPENT SO FAR" else "TOTAL SPENT", Muted)
                Text(HERO_NOT_READ_YET, style = HeroSerif, color = Rule, modifier = Modifier.padding(top = 4.dp))
                ReadingStatus(Modifier.padding(top = 6.dp))
            }
            RhythmGridNotReadYet(month, today)
            CategoryBarsNotReadYet()
            TopMerchantsNotReadYet()
        }
    }
}

/** The board's `RM&thinsp;&mdash;`, in [Rule]: decorative, since the status line under it says what it means. */
internal const val HERO_NOT_READ_YET = "RM " + NOT_READ_YET

/**
 * The mono month name, which opens `Months` (#93), and the chip on the right
 * (#68). The chevron is its own `Text`, so the name stays a whole node's text.
 *
 * [chipNotReadYet] draws the board's dash in the chip's frame, after
 * `ReadingLayout`'s delay: which chip a month takes is a read.
 */
@Composable
private fun HeaderSection(month: String, chip: Chip?, onOpenMonths: () -> Unit, chipNotReadYet: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.clickable(onClickLabel = "Choose a month", onClick = onOpenMonths),
            verticalAlignment = Alignment.CenterVertically,
            // The boards' `gap: 6px`.
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(month, style = MonoLabel, color = Muted)
            MonthChevron()
        }
        if (chip != null) {
            // The alert is in the accent and framed in it; the others are
            // framed in the border colour, so the state is not colour alone.
            val colour = if (chip.alert) Stamp else Muted
            Text(
                chip.text,
                style = MonoLabel,
                color = colour,
                modifier = Modifier
                    .border(1.dp, if (chip.alert) Stamp else Border, RoundedCornerShape(2.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        } else if (chipNotReadYet) {
            ReadingLayout {
                ValueDash(
                    Modifier
                        .border(1.dp, Border, RoundedCornerShape(2.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MonoLabel,
                )
            }
        }
    }
}

/**
 * The hero total. Greyed, never hidden, in an untrusted month, with the one
 * line that explains the grey for the whole screen (#73).
 */
@Composable
private fun HeroSection(hero: Hero) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
        MonoLine(hero.label, Muted)
        Text(
            heroFigure(hero.figure, HERO_SEN),
            style = HeroSerif,
            color = if (hero.greyed) Muted else Ink,
            modifier = Modifier.padding(top = 4.dp),
        )
        hero.split?.let { MonoLine(it, Muted, Modifier.padding(top = 6.dp)) }
        hero.incomplete?.let { MonoLine(it, Stamp, Modifier.padding(top = 6.dp)) }
    }
}

/** No history start (#65): what that means, never "nothing spent". */
@Composable
private fun EmptySection(empty: ChartsState.Empty) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        MonoLine(empty.label, Muted)
        Text(
            empty.line,
            fontFamily = Display,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            color = Ink,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * The read failed. Drawn in the accent, as the Ledger draws it, and never as
 * the empty state: a reader of "nothing captured" here would conclude there is
 * nothing, when what happened is that nothing could be read.
 *
 * [replacing]: a restore or a delete-everything turned the read away (#328),
 * which is no failure, so the line says so and nothing explains a failure.
 */
@Composable
private fun Unreadable(replacing: Boolean) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        MonoLine(
            if (replacing) REPLACING_YOUR_DATA else CANNOT_READ_YOUR_DATA,
            Stamp,
            Modifier
                .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        if (!replacing) {
            Text(
                "Pinged could not read your transactions, so these charts cannot be " +
                    "drawn. Settings says more when Pinged knows more.",
                fontFamily = Body,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Ink,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun MonoLine(text: String, colour: Color, modifier: Modifier = Modifier) {
    Text(text, style = MonoLabel, color = colour, modifier = modifier)
}

/**
 * The artboard's hero figure in the serif, as the Ledger draws its single
 * total; built without `tnum`, which Instrument Serif does not have.
 */
private val HeroSerif = TextStyle(fontFamily = Display, fontSize = 44.sp, lineHeight = 48.sp)

/** The board's `.30`: 28px under a 50px figure, the same 0.56 of this 44sp one. */
private val HERO_SEN = 25.sp

/** The test tag of a grid day not read yet. */
internal const val NOT_READ_YET_DAY = "grid-day-not-read-yet"
