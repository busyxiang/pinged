package my.pinged.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.CellState
import my.pinged.charts.model.GridDay
import my.pinged.charts.model.GridLegend
import my.pinged.charts.model.RhythmGrid
import my.pinged.ui.dayMonth
import my.pinged.ui.ringgit
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.ChartRamp
import my.pinged.ui.theme.GridRamp
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted
import java.time.LocalDate

/**
 * The daily rhythm grid (spec 8, #88): `WHEN IT WENT`, the month's squares
 * Monday first, and the legend beside them, as the artboard draws it.
 *
 * Every square but a blank one calls [onDayClick] with its date (#69); a blank
 * square is outside the record and has no click semantics at all. Each
 * tappable square says in words what it draws, so colour is never the only
 * reading for a screen reader either.
 */
@Composable
internal fun RhythmGridSection(grid: RhythmGrid, onDayClick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
        Text("WHEN IT WENT", style = MonoLabel, color = Muted)
        Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    "MTWTFSS".forEach {
                        Text(
                            it.toString(),
                            style = Small.copy(fontSize = 8.sp, letterSpacing = 0.sp),
                            color = Muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.size(width = CELL, height = 12.dp),
                        )
                    }
                }
                val squares: List<GridDay?> = List(grid.leadingBlanks) { null } + grid.days
                squares.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        week.forEach { day ->
                            if (day == null) Spacer(Modifier.size(CELL)) else Square(day, onDayClick)
                        }
                    }
                }
            }
            Legend(grid.legend, showsToday = grid.days.any { it.today })
        }
        grid.footer?.let {
            Text(
                it,
                fontFamily = Body,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Muted,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** One day. Tagged by its ISO date, which is how a test finds it. */
@Composable
private fun Square(day: GridDay, onDayClick: (LocalDate) -> Unit) {
    val tagged = Modifier.testTag(dayTag(day.date)).size(CELL)
    val drawn = when (val state = day.state) {
        is CellState.Spent -> tagged.background(GridRamp[state.step])
        is CellState.NetNegative, CellState.NothingSpent -> tagged.border(1.dp, CellLine)
        CellState.NotCaptured -> tagged.hatched().border(1.dp, CellLine)
        CellState.Blank -> tagged
    }
    val marked = if (day.today) drawn.todayMark() else drawn
    val interactive = if (day.opensDay) {
        marked
            .semantics { contentDescription = describe(day) }
            .clickable { onDayClick(day.date) }
    } else {
        marked
    }
    Box(interactive, contentAlignment = Alignment.Center) {
        if (day.state is CellState.NetNegative) Dot(4.dp)
    }
}

/** The legend column (#68): the ramp and its range, then the outlined, dotted and hatched keys. */
@Composable
private fun Legend(legend: GridLegend, showsToday: Boolean) {
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (legend.range != null) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    legend.steps.forEach { Box(Modifier.size(width = 16.dp, height = 9.dp).background(GridRamp[it])) }
                }
                Text(legend.range, style = Small, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Key("NOTHING SPENT") { Modifier.border(1.dp, CellLine) }
        if (legend.moreCameBack) {
            Key("MORE CAME BACK", dot = true) { Modifier.border(1.dp, CellLine) }
        }
        Key("NOT CAPTURED") { Modifier.hatched().border(1.dp, CellLine) }
        if (showsToday) Key("TODAY") { Modifier.border(1.dp, CellLine).todayMark() }
        Text(
            "A DAY PINGED DID NOT WATCH IS HATCHED, NEVER EMPTY.",
            style = Small,
            color = Muted,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun Key(label: String, dot: Boolean = false, swatch: () -> Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).then(swatch()), contentAlignment = Alignment.Center) {
            if (dot) Dot(3.dp)
        }
        Text(label, style = Small, color = Muted)
    }
}

@Composable
private fun Dot(size: Dp) {
    Box(Modifier.size(size).background(Muted, CircleShape))
}

/**
 * The artboard's hatch: thin diagonals 5dp apart in [ChartRamp]'s palest
 * step, `#b6ad99`, the artboard's hatch colour. Clipped to the square.
 */
private fun Modifier.hatched(): Modifier = clipToBounds().drawBehind {
    val pitch = 5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(ChartRamp.last(), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 0.8.dp.toPx())
        x += pitch
    }
}

/**
 * Today's marker (#64): an ink bar in the gap under the square, which reads
 * on every fill, the darkest step included, where a border would not.
 */
private fun Modifier.todayMark(): Modifier = drawBehind {
    val y = size.height + 1.dp.toPx()
    drawLine(Ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
}

/** `3 September, RM45.20`, as a screen reader says a square. */
private fun describe(day: GridDay): String {
    val date = dayMonth(day.date)
    val what = when (val state = day.state) {
        is CellState.Spent -> ringgit(state.netSen)
        is CellState.NetNegative -> "more came back, ${ringgit(state.netSen)}"
        CellState.NothingSpent -> "nothing spent"
        CellState.NotCaptured -> "not captured"
        CellState.Blank -> ""
    }
    return "$date, $what" + if (day.today) ", today" else ""
}

/** The test tag of [date]'s square. */
internal fun dayTag(date: LocalDate): String = "grid-day-$date"

/** The artboard's 18px squares with 2px gaps, at 1px to the dp. */
private val CELL = 18.dp
private val GAP = 2.dp

/** The artboard's cell outline, `#d5cbb8`; drawn here only, so not a theme entry. */
private val CellLine = Color(0xFFD5CBB8)

/**
 * The legend's small print. 10sp rather than the artboard's 8.5px, for the
 * reason `MonoLabel` gives for its own size.
 */
private val Small = MonoLabel.copy(fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp)
