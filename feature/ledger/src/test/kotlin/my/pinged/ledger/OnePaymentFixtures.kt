package my.pinged.ledger

import kotlinx.coroutines.runBlocking
import my.pinged.data.entity.Category
import my.pinged.read.LedgerAccess
import my.pinged.read.ScreenRead
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.ZoneId

/** The category [seedOnePayment] files its payment under. */
internal const val SEEDED_CATEGORY = 7L

/** [seedOnePayment]'s amount: RM45.00. */
internal const val SEEDED_SEN = 4_500L

/**
 * One category and one counted payment of [SEEDED_SEN] in it, at noon on
 * [day], written to [this] runner's in-memory ledger, which has the schema and
 * none of the seed. The payment is `ledgerTxn`'s, merchant [MERCHANT_KEY].
 *
 * Through the runner, so it is the first of [ScriptedRunner.calls].
 */
internal fun ScriptedRunner.seedOnePayment(day: LocalDate) {
    runBlocking {
        read("The test cannot be seeded") { db ->
            db.categoryDao().insert(Category(SEEDED_CATEGORY, "Food & Drinks", "utensils", 1))
            val noon = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            db.txnDao().insert(ledgerTxn(SEEDED_SEN, occurredAt = noon, categoryId = SEEDED_CATEGORY))
        }
    }
}

/**
 * [read]'s one read, refused by [runner] as an unopenable database refuses it,
 * published the screen's own notice and no answer. [what] is the read's own
 * `what`, so a holder that asked another runner fails here, not on the notice.
 */
internal fun assertRefusedRead(runner: ScriptedRunner, read: ScreenRead<*, *>, what: String) {
    assertEquals("The read was not asked of the runner handed in", listOf(what), runner.calls.map { it.what })
    assertFalse("A refused read ran", runner.calls.single().ran)
    assertEquals("A refusal did not say the ledger cannot be read", LedgerAccess.Unreadable, read.state.value.ledger)
    assertTrue("A refusal published an answer", read.state.value.notReadYet)
}

/** [read] last landed an answer, and the screen draws it as readable. */
internal fun assertReadable(read: ScreenRead<*, *>) {
    assertEquals("A read that landed left the ledger other than readable", LedgerAccess.Readable, read.state.value.ledger)
}
