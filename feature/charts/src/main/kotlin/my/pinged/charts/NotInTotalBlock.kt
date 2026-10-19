package my.pinged.charts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.charts.model.KeptOut
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.MonoNumerals
import my.pinged.ui.theme.Muted
import my.pinged.ui.theme.Stamp
import my.pinged.ui.theme.dottedRule

/**
 * The "not in the total" block, last in the Charts scroll and not pinned
 * (#89; #81, "Not in the total" block): what moved in the month without
 * entering its total, one ruled line each. No lines draws nothing, with no
 * space kept, so the block is gone rather than a heading over nothing.
 *
 * The line marked [KeptOut.opensReview] calls [onOpenReview]; the others are
 * not controls.
 */
@Composable
internal fun NotInTotalBlock(lines: List<KeptOut>, modifier: Modifier = Modifier, onOpenReview: () -> Unit = {}) {
    if (lines.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)
            .dottedRule(atTop = true),
    ) {
        Text("NOT IN THE TOTAL", style = MonoLabel, color = Muted, modifier = Modifier.padding(top = 16.dp))
        Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            lines.forEach { KeptOutLine(it, onOpenReview.takeIf { _ -> it.opensReview }) }
        }
    }
}

/**
 * A label, a dotted leader to the right edge, and the amount, as the merchants
 * are ruled. With [onClick] the whole line is one button, its label in the
 * accent and a chevron after its amount, as `ChartsMonth.dc.html` draws the
 * way into review.
 */
@Composable
private fun KeptOutLine(line: KeptOut, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "Open the review inbox", onClick = onClick)),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(line.label, fontFamily = Body, fontSize = 14.sp, lineHeight = 20.sp, color = if (onClick == null) Muted else Stamp)
        Box(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, bottom = 5.dp).height(1.dp).dottedRule())
        Text(line.amount, style = MonoNumerals.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Muted)
        if (onClick != null) {
            Box(Modifier.testTag(REVIEW_CHEVRON).padding(start = 6.dp).align(Alignment.CenterVertically)) {
                Chevron(pointsRight = true, size = 17.dp, strokeWidth = 1.7.dp, tint = Stamp)
            }
        }
    }
}

/** The test tag of the review line's chevron. */
internal const val REVIEW_CHEVRON = "not-in-total-review-chevron"
