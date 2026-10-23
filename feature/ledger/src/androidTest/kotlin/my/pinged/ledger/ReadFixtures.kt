package my.pinged.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.pinged.read.ScreenRead

/**
 * Read [this] again and wait for the read to land, from a test's own thread:
 * a `ScreenRead`'s calls are made on its holder's scope's thread, which is the
 * main thread for `viewModelScope`.
 */
internal suspend fun ScreenRead<*, *>.refreshed() {
    withContext(Dispatchers.Main) { refresh() }.join()
}
