package my.pinged.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import my.pinged.ui.theme.Chevron
import my.pinged.ui.theme.Ink

/**
 * A pushed screen's way back: `design/Rules.dc.html`'s 20dp chevron in a 44dp
 * target, a button TalkBack calls [description] since the mark has no words.
 */
@Composable
internal fun BackChevron(description: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onBack)
            .size(44.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Chevron(pointsRight = false, size = 20.dp, strokeWidth = 1.5.dp, tint = Ink)
    }
}

/** The chevron of a screen Settings opens, to TalkBack. */
const val SETTINGS_BACK = "Back to Settings"
