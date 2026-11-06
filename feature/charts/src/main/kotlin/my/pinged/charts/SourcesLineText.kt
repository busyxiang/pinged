package my.pinged.charts

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import my.pinged.data.SourcesLine
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted

/**
 * The sources line (#365; CONTEXT.md, Sources line), in the mono label style
 * under a month's total. Public so Spending draws the same line (#367).
 *
 * It wraps and never truncates: a name cut off would be an app the user is
 * not told about. It is never greyed and never in the accent: it is a claim
 * about apps, and greying stays the capture record's (#73).
 *
 * A line that says `NO APP IS READ YET` opens Capture sources through
 * [onOpenSources]; any other line opens nothing here, and its unreadable
 * names are #368's to make tap targets, from [SourcesLine.segments].
 */
@Composable
fun SourcesLineText(line: SourcesLine, modifier: Modifier = Modifier, onOpenSources: (() -> Unit)? = null) {
    val opens = onOpenSources?.takeIf { SourcesLine.Segment.NoAppRead in line.segments }
    Text(
        line.text,
        style = MonoLabel,
        color = Muted,
        modifier = if (opens != null) modifier.clickable(onClickLabel = "Open Capture sources", onClick = opens) else modifier,
    )
}
