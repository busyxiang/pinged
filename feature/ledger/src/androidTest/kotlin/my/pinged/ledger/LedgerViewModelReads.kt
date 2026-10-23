package my.pinged.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.pinged.ledger.home.LedgerRead
import my.pinged.ledger.home.LedgerViewModel
import java.time.YearMonth

// What the Ledger's tests ask of the holder's `ScreenRead` (#299): the module
// must be called on the holder's own thread, which is the main one, and a test
// that names the month it built a fixture for asks that month.

/** Ask [month] on the main thread and wait until it has landed, been refused or been dropped. */
internal suspend fun LedgerViewModel.readMonth(month: YearMonth) {
    withContext(Dispatchers.Main) { read.ask(month) }.join()
}

/** Read the question the holder holds again, as a resume does, and wait for it. */
internal suspend fun LedgerViewModel.readAgain() {
    withContext(Dispatchers.Main) { read.refresh() }.join()
}

/** The last answer to land, or what the screen draws before the first. */
internal val LedgerViewModel.answer: LedgerRead get() = read.state.value.answer ?: LedgerRead()

/** The last read, or a write, was refused because the database could not be opened. */
internal val LedgerViewModel.unavailable: Boolean get() = read.state.value.unavailable
