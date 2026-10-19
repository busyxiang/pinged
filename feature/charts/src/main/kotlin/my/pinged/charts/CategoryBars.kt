package my.pinged.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.Bar
import my.pinged.charts.model.Bars
import my.pinged.charts.model.Collecting
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Border
import my.pinged.ui.theme.ChartRamp
import my.pinged.ui.theme.Display
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.dottedRule

/**
 * `WHERE IT WENT`: the category bars (spec 8, #81 Bars), from plain values.
 * [Bars.Nothing] draws nothing at all, heading included.
 *
 * The Uncategorized bar, the one [Bar.ruleBefore] marks, is the one control
 * here: it calls [onOpenUncategorized], `:app`'s Search over the month's rows
 * to sort (#273). The others are inert.
 */
@Composable
internal fun CategoryBars(bars: Bars, modifier: Modifier = Modifier, onOpenUncategorized: (() -> Unit)? = null) {
    if (bars is Bars.Nothing) return
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).dottedRule(atTop = true))
        Text("WHERE IT WENT", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 15.dp))
        when (bars) {
            is Bars.Drawn -> Column(
                Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                bars.bars.forEach { BarRow(it, bars.greyed, onOpenUncategorized.takeIf { _ -> it.ruleBefore }) }
            }

            is Bars.Suppressed -> Column(Modifier.padding(top = 12.dp)) {
                Text(bars.sentence, style = SmallPrint, color = Muted)
                Text(
                    bars.negatives,
                    style = SmallPrint,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Bars.Nothing -> Unit
        }
    }
}

/**
 * One bar: the name, the track and the amount.
 *
 * **The name column is 110dp at 12.5sp**, #68's "about 100": measured under
 * Robolectric, the longest seeded name, "Government & fees", is 106dp in
 * Karla and "Religious & zakat" 100dp, so 100 cut the first. A user's
 * longer name ends in an ellipsis. **The amount greys in an
 * untrusted month and the fill does not** (#73): the ramp means magnitude
 * only, and the shape still shows roughly where the money went.
 *
 * With [onClick] the rule and the bar are one button, laid out as the two
 * were apart (the column's 9dp between them kept inside), so the bars draw
 * the same either way.
 */
@Composable
private fun BarRow(bar: Bar, greyed: Boolean, onClick: (() -> Unit)? = null) {
    if (onClick != null) {
        Column(
            Modifier.clickable(role = Role.Button, onClickLabel = SORT_UNCATEGORIZED, onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).dottedRule(atTop = true))
            BarLine(bar, greyed)
        }
        return
    }
    if (bar.ruleBefore) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).dottedRule(atTop = true))
    }
    BarLine(bar, greyed)
}

/** [BarRow]'s name, track and amount. */
@Composable
private fun BarLine(bar: Bar, greyed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            bar.name,
            style = BarName,
            color = if (bar.quiet) Muted else Ink,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp),
        )
        Box(Modifier.weight(1f).height(12.dp).background(Border)) {
            if (bar.fraction > 0f) {
                // Grey for the remainder and Uncategorized: ChartRamp's palest
                // step, as the prototype fills them.
                val fill = bar.rampStep?.let { ChartRamp[it.coerceAtMost(ChartRamp.lastIndex)] } ?: ChartRamp.last()
                Box(Modifier.fillMaxWidth(bar.fraction).fillMaxHeight().background(fill))
            }
        }
        Text(
            bar.amount,
            style = MonoNumerals.copy(fontSize = 12.sp, textAlign = TextAlign.End),
            color = if (greyed) Muted else Ink,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(72.dp),
        )
    }
}

/**
 * While the gate is shut (#66), one block in place of the bars and the
 * merchant ranking: `COLLECTING · N OF 14 DAYS` and what it waits for.
 */
@Composable
internal fun CollectingBlock(collecting: Collecting, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).dottedRule(atTop = true))
        Text(collecting.label, style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 15.dp))
        Text(
            collecting.line,
            fontFamily = Display,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            color = Ink,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The Uncategorized bar's action, to TalkBack (#273). */
internal const val SORT_UNCATEGORIZED = "Find the payments to sort"

/**
 * The bar names: Karla at 12.5sp with no tracking. Material's body style
 * carries 0.5sp of it, which pushes "Government & fees" past the 100dp column.
 */
private val BarName: TextStyle = TextStyle(fontFamily = Body, fontSize = 12.5.sp, letterSpacing = 0.sp)

/** The suppressed sentence's small mono print, the prototype's 10px at 1.7. */
private val SmallPrint: TextStyle = MonoLabel.copy(fontSize = 11.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp)
