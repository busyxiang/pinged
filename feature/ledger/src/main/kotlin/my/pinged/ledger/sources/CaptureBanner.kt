package my.pinged.ledger.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.pinged.ledger.theme.Body
import my.pinged.ledger.theme.Ink
import my.pinged.ledger.theme.MonoLabel
import my.pinged.ledger.theme.Paper
import my.pinged.ledger.theme.Stamp

/**
 * Spec 10.2's capture-stopped banner, drawn from `design/Stopped.dc.html`.
 *
 * Four states reach it today and spec 10.5 is clear none recovers on its own:
 * storage cannot be opened, the grant was never given or was revoked, the grant
 * is present and nothing has ever arrived, and the grant is present and nothing
 * has arrived for a day. The second is a *suspicion* -- a phone that genuinely
 * posted nothing looks identical from in here -- which is why the wording comes
 * from the caller.
 */
@Composable
fun CaptureBanner(
    label: String,
    body: String,
    modifier: Modifier = Modifier,
    /**
     * Optional, because one banner state deliberately has no one-tap action: when
     * the database key is gone, both spec 11.1 remedies destroy or replace data.
     *
     * After `modifier`, which Compose requires to be the first optional parameter.
     */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp)
            .border(1.5.dp, Stamp, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            label,
            style = MonoLabel.copy(fontSize = 9.5.sp, letterSpacing = 1.14.sp),
            color = Stamp,
        )
        Text(
            body,
            fontFamily = Body,
            fontSize = 14.sp,
            lineHeight = 21.7.sp,
            color = Ink,
            modifier = Modifier.padding(top = 7.dp),
        )
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Stamp)
                    .clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    fontFamily = Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    color = Paper,
                )
            }
        }
    }
}
