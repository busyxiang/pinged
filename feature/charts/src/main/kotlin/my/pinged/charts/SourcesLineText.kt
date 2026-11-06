package my.pinged.charts

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
 * `NO APP IS READ YET` opens Capture sources through [onOpenSources]. With
 * [onOpenUnread], each unreadable source's name in the cannot-read sentence
 * is underlined and opens Could not read filtered to that app (#368). Each
 * is a link on its own run, never a click on the whole text: a line can hold
 * both, and a tap on the warning's words between them must open neither. A
 * screen reader reads a link as its text alone and so would not say where it
 * goes, so each also has a custom action, [OPEN_CAPTURE_SOURCES] or
 * [openUnreadLabel]. The same app in the `FROM` list opens nothing.
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
            when {
                opens != null && segment is SourcesLine.Segment.NoAppRead ->
                    withLink(LinkAnnotation.Clickable(NO_APP_READ) { opens() }) { append(segment.text) }
                onOpenUnread != null && segment is SourcesLine.Segment.App && segment.unreadable -> {
                    val link = LinkAnnotation.Clickable(segment.source.pkg, UNREADABLE_NAME) { onOpenUnread(segment.source) }
                    withLink(link) { append(segment.text) }
                }
                else -> append(segment.text)
            }
        }
    }
    val actions = buildList {
        if (opens != null) add(CustomAccessibilityAction(OPEN_CAPTURE_SOURCES) { opens(); true })
        if (onOpenUnread != null) {
            line.unreadable.forEach { source -> add(CustomAccessibilityAction(openUnreadLabel(source.name)) { onOpenUnread(source); true }) }
        }
    }
    val drawn = if (actions.isEmpty()) modifier else modifier.semantics { customActions = actions }
    Text(text, style = MonoLabel, color = Muted, modifier = drawn)
}

/** The accessible label of `NO APP IS READ YET`'s action. */
const val OPEN_CAPTURE_SOURCES = "Open Capture sources"

/** `NO APP IS READ YET`'s link tag. */
private const val NO_APP_READ = "capture-sources"

/** The accessible label of an unreadable [name]'s action (#368). */
fun openUnreadLabel(name: String): String = "Open Could not read for $name"

/** Underlined, in the line's own colour: a tap target, and still not the accent. */
private val UNREADABLE_NAME = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
