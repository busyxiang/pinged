package my.pinged.charts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Muted

/**
 * The comparison line under the hero (#81, Comparison line; #67). A held-back
 * line is null and draws nothing, with no space kept for it: no reason text,
 * since the incomplete line already explains an untrusted month and a running
 * or first month needs none.
 */
@Composable
internal fun ComparisonLine(line: String?) {
    if (line == null) return
    Text(
        line,
        style = MonoLabel,
        color = Muted,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
    )
}
