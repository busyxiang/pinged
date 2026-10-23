package my.pinged.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import my.pinged.ledger.sources.SourcesState
import my.pinged.ledger.sources.SourcesViewModel
import my.pinged.ledger.sources.toState

/**
 * [SourcesViewModel]'s operations, made from a test's own thread as the app
 * makes them: a `ScreenRead`'s calls are made on its scope's thread, which is
 * the main thread for `viewModelScope`, and it takes its place in the turn
 * there. The `Job` is the operation's, and completes when its read has landed.
 */
internal fun SourcesViewModel.refreshOnMain(): Job = runBlocking(Dispatchers.Main) { read.refresh() }

/** [SourcesViewModel.setEnabled] from the main thread; see [refreshOnMain]. */
internal fun SourcesViewModel.setEnabledOnMain(pkg: String, on: Boolean): Job =
    runBlocking(Dispatchers.Main) { setEnabled(pkg, on) }

/** What the screen draws from the last read that landed. */
internal val SourcesViewModel.current: SourcesState get() = read.state.value.toState()
