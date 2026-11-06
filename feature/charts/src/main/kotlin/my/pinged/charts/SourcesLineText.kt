package my.pinged.charts

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import my.pinged.data.NamedSource
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
 * [onOpenSources]. With [onOpenUnread], each unreadable source's name in the
 * cannot-read sentence is underlined and opens Could not read filtered to
 * that app (#368): a link for a tap, since the name sits mid-sentence in a
 * line that wraps, and a custom action labelled [openUnreadLabel] for a
 * screen reader, which reads a link as its text alone and so would not say
 * where it goes. The same app in the `FROM` list opens nothing.
 */
@Composable
fun SourcesLineText(
    line: SourcesLine,
    modifier: Modifier = Modifier,
    onOpenSources: (() -> Unit)? = null,
    onOpenUnread: ((NamedSource) -> Unit)? = null,
) {
    val opens = onOpenSources?.takeIf { SourcesLine.Segment.NoAppRead in line.segments }
    val text = buildAnnotatedString {
        for (segment in line.segments) {
            if (onOpenUnread != null && segment is SourcesLine.Segment.App && segment.unreadable) {
                val link = LinkAnnotation.Clickable(segment.source.pkg, UNREADABLE_NAME) { onOpenUnread(segment.source) }
                withLink(link) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }
    var drawn = if (opens != null) modifier.clickable(onClickLabel = "Open Capture sources", onClick = opens) else modifier
    if (onOpenUnread != null && line.unreadable.isNotEmpty()) {
        drawn = drawn.semantics {
            customActions = line.unreadable.map { source ->
                CustomAccessibilityAction(openUnreadLabel(source.name)) { onOpenUnread(source); true }
            }
        }
    }
    Text(text, style = MonoLabel, color = Muted, modifier = drawn)
}

/** The accessible label of an unreadable [name]'s action (#368). */
fun openUnreadLabel(name: String): String = "Open Could not read for $name"

/** Underlined, in the line's own colour: a tap target, and still not the accent. */
private val UNREADABLE_NAME = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
