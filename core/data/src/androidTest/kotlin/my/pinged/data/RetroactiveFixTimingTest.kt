package my.pinged.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #49: how long the retroactive fix holds the write lock, against `CLAUDE.md`'s
 * lease discipline. The figures are recorded on `MerchantRuleDao.teachAndFix`.
 *
 * What is asserted is the bound they have to stay under,
 * [Databases.RESET_LEASE_WAIT_MILLIS]: how long a gate waits for a lease before
 * closing under it, which is the lock-on-a-closed-instance case the discipline
 * exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class RetroactiveFixTimingTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    @Test fun theFixOnATenThousandPaymentMerchantStaysUnderTheGatesWait() {
        val millis = measure(movable = 10_000)
        assertTrue("The fix held the write lock for ${millis.max()}ms", millis.max() < Databases.RESET_LEASE_WAIT_MILLIS)
    }

    /**
     * Logged, not asserted: the largest ledger seeded, and past the bound on a
     * shared emulator. See the measurement on `teachAndFix`.
     */
    @Test fun theFixOnAHundredThousandPaymentMerchantIsMeasured() {
        measure(movable = 100_000)
    }

    /**
     * Seeds `movable + 1` rows of one merchant (a third in Shopping, the rest
     * uncategorized) among as many again over 500 other merchants, then times
     * five teaching saves that alternate categories so each moves every one of
     * the merchant's rows. Returns the milliseconds of each.
     */
    private fun measure(movable: Int): List<Long> {
        val shopping = db.categoryId("Shopping")
        val food = db.categoryId("Food & Drinks")
        val petrol = db.categoryId("Transport")
        val uncategorized = db.categoryDao().requireUncategorizedId()
        val txns = db.txnDao()
        val big = movable + 1
        val others = big
        fun batch(from: Int, to: Int, key: (Int) -> String, category: (Int) -> Long) =
            txns.insertAll(
                (from until to).map { i ->
                    sampleTxn(occurredAt = 1_000L + i, categoryId = category(i))
                        .copy(merchantRaw = key(i), merchantDisplay = key(i), merchantKey = key(i))
                },
            )
        for (from in 0 until big step 500) {
            batch(from, minOf(from + 500, big), { "BIG SHOP" }, { if (it % 3 == 0) shopping else uncategorized })
        }
        for (from in big until big + others step 500) {
            batch(from, minOf(from + 500, big + others), { "OTHER ${it % 500}" }, { shopping })
        }
        val tapped = db.openHelper.readableDatabase
            .query("SELECT id FROM txn WHERE merchant_key = 'BIG SHOP' LIMIT 1")
            .use { it.moveToFirst(); it.getLong(0) }
        val total = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM txn").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(big + others, total)

        val millis = listOf(food, petrol, food, petrol, food).mapIndexed { i, category ->
            val start = System.nanoTime()
            val outcome = db.merchantRuleDao().teachAndFix(tapped, category, now = 5_000L + i)
            val took = (System.nanoTime() - start) / 1_000_000
            assertEquals(movable, outcome.moved)
            took
        }
        Log.i("RetroactiveFixTiming", "teachAndFix moving $movable of $total rows: ${millis.joinToString()} ms")
        return millis
    }
}
