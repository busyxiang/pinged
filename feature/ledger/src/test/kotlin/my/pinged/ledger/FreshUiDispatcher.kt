package my.pinged.ledger

import androidx.compose.ui.platform.AndroidUiDispatcher
import org.junit.rules.ExternalResource
import kotlin.coroutines.ContinuationInterceptor

/**
 * Clears what earlier tests in this JVM left scheduled on
 * `AndroidUiDispatcher.Main`, for a Robolectric test whose content is
 * delivered through it after composition: a page `collectAsLazyPagingItems`
 * collects after `setContent` is presented there.
 *
 * `Main` is one static per JVM. A window recomposer (a sheet, a dialog) also
 * dispatches through it, and a test that ends with a dispatch still posted
 * leaves `scheduledTrampolineDispatch` and `scheduledFrameDispatch` true.
 * Robolectric's reset between tests then drops the posted message and frame
 * callback that would have cleared them. The dispatcher never posts again,
 * and every later task queues behind them for the rest of the JVM. Measured
 * on `SpendingNotReadYetTest`: in each failing run both flags were already
 * true, with 1 task queued, before its first `setContent`. Its landed page
 * never arrived, even after 2s of real time idling the held clock.
 *
 * The queued tasks belong to a composition that is already disposed, so they
 * are dropped as Robolectric drops that test's looper messages, not run. A
 * Compose bump that renames these fields fails here with
 * `NoSuchFieldException` rather than letting the race back in silently.
 */
class FreshUiDispatcher : ExternalResource() {
    override fun before() {
        val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
        fun field(name: String) = AndroidUiDispatcher::class.java.getDeclaredField(name).apply { isAccessible = true }
        synchronized(field("lock").get(dispatcher)!!) {
            (field("toRunTrampolined").get(dispatcher) as MutableCollection<*>).clear()
            (field("toRunOnFrame").get(dispatcher) as MutableCollection<*>).clear()
            field("scheduledTrampolineDispatch").setBoolean(dispatcher, false)
            field("scheduledFrameDispatch").setBoolean(dispatcher, false)
        }
    }
}
