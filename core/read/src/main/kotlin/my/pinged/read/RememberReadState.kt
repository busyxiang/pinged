package my.pinged.read

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import my.pinged.data.Databases

/**
 * The one composable a screen reads a [ScreenRead] through (#296): its
 * [ScreenRead.state], and the two triggers that read it again, so no screen
 * wires them for itself.
 *
 * It reads on each resume, which is also the first composition of a screen
 * that is shown, and whenever `Databases.rewrites` moves while the screen is
 * resumed: a delete or a restore has replaced what the ledger holds, and an
 * answer from the old one must not stay on screen. A screen that is not
 * resumed reads when it next is, and once.
 *
 * The trigger is here and not in the holder because a holder that re-reads
 * for itself re-reads at the wrong moments; `LedgerViewModel.rebind`'s KDoc
 * records why.
 *
 * @param alsoOn any further reason to read again: a change of this value is
 *   one. Ledger's summary passes the feed's *write seen* signal.
 */
@Composable
fun <Q, A> ScreenRead<Q, A>.rememberReadState(alsoOn: Any? = null): ReadState<Q, A> {
    val rewrites by Databases.rewrites.collectAsState()
    LifecycleResumeEffect(this, rewrites, alsoOn) {
        refresh()
        onPauseOrDispose {}
    }
    val held by state.collectAsState()
    return held
}
