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
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.charts.model.ChartsState
import my.pinged.charts.model.Chip
import my.pinged.charts.model.Hero
import my.pinged.charts.model.chartsState
import my.pinged.data.Databases
import my.pinged.ui.monthName
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.CANNOT_READ_YOUR_DATA
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Stamp
import java.time.LocalDate

/**
 * The Charts tab (spec 9.3, #81): the selected month's header and hero, and
 * below them the month's sections in one vertical scroll.
 *
 * Re-read on each foreground, and when a delete or a restore rewrites the
 * ledger while the screen shows (`Databases.rewrites`), as the Ledger does.
 *
 * [onDayClick] is a grid square's tap (#69); `:app` routes it to `Day`, which
 * this module knows nothing of (#70).
 */
@Composable
fun ChartsScreen(viewModel: ChartsViewModel, modifier: Modifier = Modifier, onDayClick: (LocalDate) -> Unit = {}) {
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(viewModel, rewrites) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val read by viewModel.read.collectAsState()
    val unavailable by viewModel.unavailable.collectAsState()
    val month by viewModel.month.collectAsState()
    // Saveable, so a sheet open at a process death is open again after it.
    var monthsOpen by rememberSaveable { mutableStateOf(false) }
    ChartsScreenContent(
        monthName = monthName(month),
        state = chartsState(read),
        unavailable = unavailable,
        modifier = modifier,
        onDayClick = onDayClick,
        onOpenMonths = { monthsOpen = true },
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
 */
@Composable
internal fun ChartsScreenContent(
    monthName: String,
    state: ChartsState,
    unavailable: Boolean,
    modifier: Modifier = Modifier,
    onDayClick: (LocalDate) -> Unit = {},
    onOpenMonths: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        when {
            // Before the state, which may still hold an older read: a screen
            // that cannot read says so rather than drawing stale figures.
            unavailable -> {
                HeaderSection(monthName, chip = null, onOpenMonths)
                Unreadable()
            }

            state is ChartsState.Reading -> {
                HeaderSection(monthName, chip = null, onOpenMonths)
                MonoLine("READING", Muted, Modifier.padding(start = 20.dp, top = 24.dp))
            }

            state is ChartsState.Empty -> {
                HeaderSection(state.month, chip = null, onOpenMonths)
                EmptySection(state)
            }

            state is ChartsState.Month -> {
                val view = state.view
                // Slot: header (#86). The month name opens `Months` (#93).
                HeaderSection(view.header.month, view.header.chip, onOpenMonths)
                // Slot: hero (#86).
                HeroSection(view.hero)
                // Slot: comparison line (#94), under the hero.
                ComparisonLine(view.comparison)
                RhythmGridSection(view.grid, onDayClick)
                // Category bars, or collecting (#91): one block stands in
                // for both the bars and the merchants while the gate is shut.
                if (view.collecting.open) CategoryBars(view.bars) else CollectingBlock(view.collecting)
                // Top merchants (#92), behind the same gate.
                if (view.collecting.open) TopMerchants(view.merchants)
                NotInTotalBlock(view.notInTotal)
            }
        }
    }
}

/**
 * The mono month name, which opens `Months` (#93), and the chip on the right
 * (#68). The chevron is its own `Text`, so the name stays a whole node's text.
 */
@Composable
private fun HeaderSection(month: String, chip: Chip?, onOpenMonths: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            Modifier.clickable(onClickLabel = "Choose a month", onClick = onOpenMonths),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(month, style = MonoLabel, color = Ink)
            Text(" ⌄", style = MonoLabel, color = Ink)
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
            hero.figure,
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
 */
@Composable
private fun Unreadable() {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        MonoLine(
            CANNOT_READ_YOUR_DATA,
            Stamp,
            Modifier
                .border(1.dp, Stamp, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
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

@Composable
private fun MonoLine(text: String, colour: Color, modifier: Modifier = Modifier) {
    Text(text, style = MonoLabel, color = colour, modifier = modifier)
}

/**
 * The artboard's hero figure in the serif, as the Ledger draws its single
 * total; built without `tnum`, which Instrument Serif does not have.
 */
private val HeroSerif = TextStyle(fontFamily = Display, fontSize = 44.sp, lineHeight = 48.sp)
