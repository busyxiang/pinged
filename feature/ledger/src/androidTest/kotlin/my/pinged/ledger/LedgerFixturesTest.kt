package my.pinged.ledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import my.pinged.data.Databases
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [freshLedger] hands back a seeded database even while something else opens
 * one (#140).
 *
 * A view model a previous test left running reads on Dispatchers.IO after
 * that test's teardown. On CI one such read reached `Databases.shared` between
 * the next test's `reset` and its `deleteDatabase`, built and seeded a file
 * the delete then unlinked, and that instance was what `freshLedger` returned:
 * the next connection its pool opened found a new, empty `pinged.db` and
 * "no such table: category".
 *
 * So [freshLedger]'s database has to be the file on disk.
 */
@RunWith(AndroidJUnit4::class)
class LedgerFixturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun leaveNothingBehind() {
        context.discardTheDatabase()
    }

    @Test fun aFreshLedgerIsSeededWhileAnotherThreadOpensTheDatabase() {
        val stop = AtomicBoolean(false)
        // A lease, as every production reader takes, so a reset never closes
        // the instance under this thread's statement.
        val opener = thread(name = "stray reader") {
            while (!stop.get()) runCatching { Databases.leasing { Databases.categoryDao(context).countAll() } }
        }
        val failures = try {
            (1..ROUNDS).mapNotNull { round ->
                runCatching {
                    context.freshLedger()
                    // A second open of the file, as stage two's or an export's
                    // would be. The instance itself reads its own tables on the
                    // unlinked inode until its pool opens another connection,
                    // which nothing here can force: measured on emulator-5554,
                    // 167 unlinks in 100 rounds and every read still answered.
                    val onDisk = Databases.aside(context) { it.categoryDao().countAll() }
                    check(onDisk > 0) { "no categories in pinged.db" }
                }.exceptionOrNull()?.let { "round $round: $it" }
            }
        } finally {
            stop.set(true)
            opener.join()
        }

        assertEquals("freshLedger returned a database without its seeded tables", emptyList<String>(), failures)
    }

    private companion object {
        const val ROUNDS = 100
    }
}
