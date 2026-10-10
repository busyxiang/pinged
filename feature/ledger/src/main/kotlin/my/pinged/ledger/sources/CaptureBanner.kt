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
import my.pinged.ui.theme.Body
import my.pinged.ui.theme.Ink
import my.pinged.ui.theme.MonoLabel
import my.pinged.ui.theme.Paper
import my.pinged.ui.theme.Stamp

/**
 * Spec 10.2's capture-stopped banner, drawn from `design/Stopped.dc.html`.
 *
 * Five capture states reach it, and spec 10.5 is clear none recovers on its
 * own: storage cannot be opened, the grant was never given or was revoked, the
 * heartbeat store cannot be read so nothing about capture can be said at all,
 * the grant is present and nothing has ever arrived, and the grant is present
 * and nothing has arrived for a day. The last is a *suspicion* -- a phone that
 * genuinely posted nothing looks identical from in here -- which is why the
 * wording comes from the caller.
 *
 * A sixth state is not a capture state at all: `MainActivity`'s backup nudge,
 * which reports that nothing has been exported for a month. It is drawn here
 * because a user reads one strip above the screen, and it is drawn last for the
 * reason `bannerFor` gives -- every other state says capture is broken now.
 */
@Composable
fun CaptureBanner(
    label: String,
    body: String,
    modifier: Modifier = Modifier,
    /**
     * Optional, because not every state has a remedy a tap can reach;
     * `MainActivity.GrantBanner` says which does not, and why.
     *
     * After `modifier`, which Compose requires to be the first optional parameter.
     */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /**
     * Hides the banner for a while. Only the backup nudge passes one: every
     * capture state says something is broken now, and hiding that would leave
     * it broken with nothing saying so.
     */
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
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
        // Outlined rather than filled, so it reads as the lesser of the two.
        if (dismissLabel != null && onDismiss != null) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .border(1.5.dp, Stamp, RoundedCornerShape(2.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dismissLabel,
                    fontFamily = Body,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    color = Stamp,
                )
            }
        }
    }
}
