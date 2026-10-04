package my.pinged.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.RandomAccessFile
import my.pinged.data.DatabaseFactory
import my.pinged.data.Databases
import my.pinged.data.PingedDatabase
import my.pinged.ledger.transfer.bulkCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 3,000 captures, checkpointed into the main file, and one byte flipped in
 * the middle `raw_capture` leaf, which nothing on the ledger or settings
 * screens reads. The shared instance is left closed, so the next one opens on
 * the damage.
 */
internal fun Context.damagedLedger() {
    val page = Databases.shared(this).let { db ->
        db.runInTransaction {
            (0 until 3_000).chunked(500).forEach { chunk -> db.rawCaptureDao().insertAll(chunk.map(::bulkCapture)) }
        }
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        leavesOf(db, "raw_capture").let { it[it.size / 2] }
    }
    Databases.reset()
    flipAByteIn(page)
}

/**
 * 3,000 transactions dated today, checkpointed, and one byte flipped in the
 * middle `txn` leaf: a month whose own figures meet the damage every time
 * they are read, and a fresh connection meets it again.
 */
internal fun Context.damagedMonth() {
    val now = System.currentTimeMillis()
    val page = Databases.shared(this).let { db ->
        db.runInTransaction {
            for (i in 0 until 3_000) {
                db.txnDao().insert(ledgerTxn(amountSen = 100L + i, occurredAt = now - i).copy(merchantDisplay = "Kedai $i"))
            }
        }
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        leavesOf(db, "txn").let { it[it.size / 2] }
    }
    Databases.reset()
    flipAByteIn(page)
}

/**
 * 3,000 captures, checkpointed, and one byte flipped in the middle leaf of
 * `raw_capture`'s `sbn_key` index: damage no export reads, since the export
 * walks each table by its own key, and that a lookup through the index meets.
 */
internal fun Context.damagedIndex() {
    val page = Databases.shared(this).let { db ->
        db.runInTransaction {
            (0 until 3_000).chunked(500).forEach { chunk -> db.rawCaptureDao().insertAll(chunk.map(::bulkCapture)) }
        }
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        leavesOf(db, SBN_KEY_INDEX).let { it[it.size / 2] }
    }
    Databases.reset()
    flipAByteIn(page)
}

/**
 * [damagedIndex]'s damage met by a read inside a transaction, which runs on
 * the connection the export's own transaction will take: what the ledger
 * feed's first page does, Room reading it in one. Asserted, as
 * [poisonTheSharedInstance] asserts its own.
 */
internal fun Context.poisonTheSharedInstanceThroughItsIndex() {
    val shared = Databases.shared(this)
    val met = runCatching {
        shared.runInTransaction {
            shared.openHelper.writableDatabase
                .query("SELECT sbn_key FROM raw_capture INDEXED BY $SBN_KEY_INDEX ORDER BY sbn_key")
                .use { c -> while (c.moveToNext()) Unit }
        }
    }.exceptionOrNull()
    assertTrue("the read did not meet the damage but $met", met is SQLiteDatabaseCorruptException)
    val next = runCatching { shared.runInTransaction<Int> { shared.categoryDao().countAll() } }.exceptionOrNull()
    assertEquals(
        "the connection was not poisoned by the damaged read, so this test proves nothing",
        "net.zetetic.database.sqlcipher.SQLiteNotADatabaseException",
        next?.javaClass?.name,
    )
}

private const val SBN_KEY_INDEX = "index_raw_capture_sbn_key"

private fun Context.flipAByteIn(page: Int) {
    RandomAccessFile(getDatabasePath(DatabaseFactory.NAME), "rw").use { raf ->
        val at = (page - 1).toLong() * 4096 + 1000
        raf.seek(at)
        val original = raf.readByte()
        raf.seek(at)
        raf.writeByte(original.toInt() xor 0xFF)
    }
}

private fun leavesOf(db: PingedDatabase, tree: String): List<Int> {
    val pages = mutableListOf<Int>()
    db.openHelper.readableDatabase
        .query("SELECT pageno FROM dbstat WHERE name = ? AND pagetype = 'leaf' ORDER BY path", arrayOf(tree))
        .use { c -> while (c.moveToNext()) pages += c.getInt(0) }
    return pages
}

/**
 * What the feed does scrolling into an old month: a plain read, outside a
 * transaction, that meets the damage. Asserted, so a read that stopped
 * meeting it, or stopped poisoning, fails here rather than passing what
 * follows vacuously.
 */
internal fun Context.poisonTheSharedInstance() {
    val shared = Databases.shared(this)
    var after = 0L
    val met = runCatching {
        while (true) {
            val page = shared.rawCaptureDao().pageFrom(after, 500)
            if (page.size < 500) break
            after = page.last().id
        }
    }.exceptionOrNull()
    assertTrue("the read did not meet the damage but $met", met is SQLiteDatabaseCorruptException)
    val next = runCatching { shared.captureSourceDao().all() }.exceptionOrNull()
    assertEquals(
        "the connection was not poisoned by the damaged read, so this test proves nothing",
        "net.zetetic.database.sqlcipher.SQLiteNotADatabaseException",
        next?.javaClass?.name,
    )
}
