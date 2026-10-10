package my.pinged.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.Merchants
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.dottedRule

/**
 * `WHO GOT IT`: the month's top merchants (spec 8; #81, Merchants), ruled to
 * their totals as the artboard draws them: the ranked five, the rest, then
 * each net-negative merchant, signed. A month with no merchant draws
 * nothing, heading included. Drawn only while the collecting gate is open.
 *
 * **In an untrusted month every total and count greys (#73), and the names do
 * not.** So the count is in ink when the month is trusted, unlike the
 * artboard's pale `18×`: [Muted] is the palest grey this app sets text in
 * (`Color.kt`), and a count already drawn in it would have no grey to take.
 */
@Composable
internal fun TopMerchants(merchants: Merchants, modifier: Modifier = Modifier) {
    if (merchants.rows.isEmpty() && merchants.negatives.isEmpty()) return
    val figure = if (merchants.greyed) Muted else Ink
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).dottedRule(atTop = true))
        Text("WHO GOT IT", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 18.dp))
        Column(Modifier.padding(top = 13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            merchants.rows.forEach { RuledLine(it.name, Ink, it.count, it.amount, figure) }
            // Quiet like the bars' `Everything else`: the label is not a name.
            merchants.rest?.let { RuledLine(it.label, Muted, count = null, it.amount, figure) }
            merchants.negatives.forEach { RuledLine(it.name, Ink, it.count, it.amount, figure) }
        }
    }
}

/**
 * A name, its count, a dotted leader to the right edge, and the amount, on
 * one baseline. A long name ends in an ellipsis at 220dp, so the leader and
 * the amount always keep their place.
 */
@Composable
private fun RuledLine(name: String, nameColour: Color, count: String?, amount: String, figure: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            name,
            style = MerchantName,
            color = nameColour,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline().widthIn(max = 220.dp),
        )
        if (count != null) {
            Text(
                count,
                style = MonoNumerals.copy(fontSize = 11.sp),
                color = figure,
                modifier = Modifier.alignByBaseline().padding(start = 6.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .align(Alignment.Bottom)
                .padding(start = 8.dp, end = 8.dp, bottom = 5.dp)
                .height(1.dp)
                .dottedRule(),
        )
        Text(
            amount,
            style = MonoNumerals.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = figure,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** The merchant names: Karla, as the "not in the total" lines are set, with no tracking. */
private val MerchantName: TextStyle = TextStyle(fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
