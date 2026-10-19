package my.pinged

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import my.pinged.ui.test.boundedWaitUntil

/**
 * Waits for Spending's feed and scrolls it until [matcher] is composed: true
 * once it is, false if the feed or the row never came within [timeoutMillis].
 *
 * A row is not on screen at the start. The summary is the feed's first item
 * (#253), and at CI's 320x640 under the capture-off banner it fills all the
 * list has, so a row below it is never composed until the list is scrolled
 * to it, and waiting for its text alone waits forever.
 */
internal fun ComposeTestRule.scrollFeedTo(matcher: SemanticsMatcher, timeoutMillis: Long): Boolean =
    runCatching {
        boundedWaitUntil(timeoutMillis) {
            onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { onNode(hasScrollToIndexAction()).performScrollToNode(matcher) }.isSuccess
        }
    }.isSuccess

/** [scrollFeedTo] the first node drawing [text]. */
internal fun ComposeTestRule.scrollFeedTo(text: String, timeoutMillis: Long): Boolean =
    scrollFeedTo(hasText(text), timeoutMillis)

/** Back to the feed's first item, the month's summary. */
internal fun ComposeTestRule.scrollFeedToSummary() {
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    waitForIdle()
}
