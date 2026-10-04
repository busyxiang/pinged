package my.pinged.ledger

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.pinged.data.DatabaseFactory
import my.pinged.data.PingedDatabase
import my.pinged.ledger.home.LeasedFeed
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * A payment written after the feed's first page is read invalidates the feed,
 * however late Room's own source starts listening.
 *
 * Room's `LimitOffsetPagingSource` subscribes to its tables from a coroutine
 * its constructor launches on the database's query context (room-paging
 * 2.8.4, read off the bytecode), and a write committed before that coroutine
 * runs is never replayed to it. Issue #16: on a CI emulator with its four
 * cores loaded, 8 to 20 of every 100 inserts went unseen for the full 5s,
 * and none of 300 did on an idle one. So the race is forced here rather than
 * waited for: the instance's query dispatcher holds the subscription until
 * after the insert, which is the order a starved scheduler produces by chance.
 */
@RunWith(AndroidJUnit4::class)
class FeedInvalidationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatcher = HoldingDispatcher()
    private lateinit var db: PingedDatabase

    @Before fun open() {
        context.deleteDatabase(NAME)
        db = Room.databaseBuilder(context, PingedDatabase::class.java, NAME)
            .openHelperFactory(DatabaseFactory.openHelperFactory(context))
            .setQueryCoroutineContext(dispatcher)
            .build()
        db.categoryDao().seedIfEmpty()
    }

    @After fun close() {
        dispatcher.release()
        db.close()
        context.deleteDatabase(NAME)
    }

    @Test(timeout = 60_000)
    fun aPaymentWrittenBeforeRoomsSourceSubscribesStillInvalidatesTheFeed() = runBlocking<Unit> {
        val category = db.categoryDao().requireUncategorizedId()
        dispatcher.holding = true
        val room = db.txnDao().feed()
        dispatcher.holding = false
        val feed = LeasedFeed(room, db.invalidationTracker)

        val first = withContext(Dispatchers.IO) { feed.load(PagingSource.LoadParams.Refresh(null, 10, false)) }
        assertTrue("the first page was not read: $first", first is PagingSource.LoadResult.Page)
        db.txnDao().insert(ledgerTxn(amountSen = 100L, categoryId = category))
        dispatcher.release()

        val deadline = System.nanoTime() + 5_000_000_000L
        while (!feed.invalid && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(
            "The feed was not invalidated by a payment written after its first " +
                "page, so the ledger would not show it until the next write",
            feed.invalid,
        )
    }

    /** [Dispatchers.IO], except that what is dispatched while [holding] waits for [release]. */
    private class HoldingDispatcher : CoroutineDispatcher() {
        @Volatile var holding = false
        private val held = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held += context to block else Dispatchers.IO.dispatch(context, block)
        }

        fun release() {
            holding = false
            while (true) {
                val (context, block) = held.poll() ?: break
                Dispatchers.IO.dispatch(context, block)
            }
        }
    }

    private companion object {
        const val NAME = "feed-invalidation-test.db"
    }
}
