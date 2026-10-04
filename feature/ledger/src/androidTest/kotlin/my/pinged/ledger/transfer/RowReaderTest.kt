package my.pinged.ledger.transfer

import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RowReader]'s walk against a table that fails the way a damaged SQLCipher
 * file was measured to (`RowReader`'s KDoc), without a file.
 * `RowReaderDamageTest` is the same walk against real damage.
 *
 * Instrumented only because the `android.database` exceptions it throws are
 * stubs on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class RowReaderTest {
    /** One connection. Once it has thrown a corruption, it answers nothing else. */
    private class Connection {
        var poisoned = false
        var closed = false
    }

    /**
     * [ids] stored [perPage] to a leaf, of which [damaged] (leaf indexes) will
     * not read. Its indexes list [indexed].
     *
     * A read touches the leaf holding the first id at or above `from` -- or
     * the last leaf, when there is none, which is the rightmost path -- and the
     * leaves of the rows it returns. [failFirst] replaces the first read's
     * outcome, for the errors that are not damage.
     */
    private class Table(
        val ids: List<Long>,
        perPage: Int,
        val damaged: Set<Int>,
        val idSourcesDamaged: Int = 0,
        val sequenceDamaged: Boolean = false,
        val failFirst: Throwable? = null,
        val indexed: List<Long> = ids,
    ) {
        private val leaves = ids.chunked(perPage)
        val unreadable = damaged.flatMap { leaves[it] }.toSet()
        var reads = 0
            private set

        /** Past this many reads the walk is not going to stop. */
        private val cap = 10 * ids.size + 1_000

        private fun leafOf(id: Long) = leaves.indexOfFirst { id in it }

        fun read(c: Connection, from: Long, limit: Int): List<Long> {
            if (++reads > cap) throw AssertionError("salvage made $reads reads of ${ids.size} ids and was still going")
            failFirst?.let { if (reads == 1) throw it }
            touch(c)
            val rows = ids.filter { it >= from }.take(limit)
            val landing = rows.firstOrNull()?.let(::leafOf) ?: leaves.lastIndex
            if ((rows.map(::leafOf) + landing).any { it in damaged }) damage(c)
            return rows
        }

        fun sources(): List<(Connection) -> List<Long>> = List(2) { index ->
            { c -> touch(c); if (index < idSourcesDamaged) damage(c); indexed.shuffled() }
        }

        fun sequence(c: Connection): Long? {
            touch(c)
            if (sequenceDamaged) damage(c)
            return ids.max()
        }

        private fun touch(c: Connection) {
            if (c.closed) throw SQLException("Error code: 21, message: connection is closed")
            if (c.poisoned) throw SQLiteException("file is not a database (code 26)")
        }

        private fun damage(c: Connection): Nothing {
            c.poisoned = true
            throw SQLiteDatabaseCorruptException("database disk image is malformed (code 11)")
        }

        fun paged() = PagedTable<Connection, Long>(
            name = "t",
            fetch = ::read,
            idOf = { it },
            idSources = sources(),
            highestIdEver = ::sequence,
        )
    }

    /** Ids 1..300 with every seventh missing, so an id and a row are not the same count. */
    private val gappy = (1L..300L).filter { it % 7 != 0L }

    private fun salvaging() = RowReader(open = { Connection() }, close = { it.closed = true })

    /** What `ExportJson.writePaged` does with a reader: page until a short page. */
    private fun walk(reader: RowReader<Connection>, table: PagedTable<Connection, Long>, limit: Int = 50): List<Long> {
        val seen = mutableListOf<Long>()
        var after = 0L
        while (true) {
            val page = reader.page(table, after, limit)
            seen += page
            if (page.size < limit) return seen
            after = page.last()
        }
    }

    /** The control: the table's own fetch, with nothing walking past damage, throws on it. */
    @Test fun aPlainFetchStopsAtTheFirstDamage() {
        val table = Table(gappy, perPage = 10, damaged = setOf(5)).paged()
        val connection = Connection()
        assertEquals(gappy.take(10), table.fetch(connection, 1, 10))
        val outcome = runCatching {
            var from = 1L
            while (true) {
                val page = table.fetch(connection, from, 50)
                if (page.size < 50) break
                from = page.last() + 1
            }
        }
        assertTrue(
            "A plain fetch walked past damage it should have thrown on: $outcome",
            outcome.exceptionOrNull() is SQLiteDatabaseCorruptException,
        )
    }

    /**
     * Leaves 5 and 6 together, then a readable island, then leaf 12, at every
     * page size to 64: a span halved from 50 goes 25, 12, 6, 3, 1 and never
     * fails at 2, so one size alone cannot see a walk that stops halving there.
     */
    @Test fun everyReadableRowIsKeptOnceAndEveryUnreadableOneCounted() {
        for (limit in 1..64) {
            val table = Table(gappy, perPage = 10, damaged = setOf(5, 6, 12))
            val reader = salvaging()
            val outcome = runCatching { walk(reader, table.paged(), limit) }

            assertNull("at $limit a page, salvage threw rather than reading past the damage", outcome.exceptionOrNull())
            val seen = outcome.getOrThrow()
            assertEquals("at $limit a page, a row came back twice", seen.size, seen.toSet().size)
            assertEquals("at $limit a page, a readable row was lost, or an unreadable one invented", gappy - table.unreadable, seen)
            assertEquals(
                "at $limit a page, the loss was not counted as the rows the index lists and would not read",
                mapOf("t" to Unreadable.Counted(recovered = seen.size, unread = table.unreadable.size)),
                reader.unreadable,
            )
            // 56 reads, measured. A walk that stays at one row a read after
            // stepping over damage, until the next page call, makes 144.
            if (limit == 50) {
                assertTrue("salvage made ${table.reads} reads, so it did not return to full spans after the damage", table.reads <= 80)
            }
        }
    }

    /**
     * The index misses 31, the row straight after the damaged 21..30, then 60
     * among rows that read, and 95..100 at the end. Every one of them reads,
     * so every one is kept, and the figure is the rows returned plus the
     * listed ids that would not read.
     */
    @Test(timeout = 20_000)
    fun aRowTheIndexDoesNotListIsStillRead() {
        val ids = (1L..100L).toList()
        val table = Table(ids, perPage = 10, damaged = setOf(2), indexed = ids - setOf(31L, 60L) - (95L..100L))
        val reader = salvaging()
        val outcome = runCatching { walk(reader, table.paged()) }

        assertNull("salvage did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("a readable row was lost, or an unreadable one invented", ids - (21L..30L), outcome.getOrThrow())
        assertEquals(
            "the loss is not the rows returned and the listed ids that would not read",
            mapOf("t" to Unreadable.Counted(recovered = 90, unread = 10)),
            reader.unreadable,
        )
    }

    /**
     * The index lists 20 and 55, which the table does not hold. 55 lies among
     * rows that read, and the read that runs across it shows it absent; every
     * read starting at 20 lands on the damaged leaf holding 21, so nothing can
     * tell 20 from a lost row, and it is counted. What Counted says of this:
     * the ten lost rows and 20 would not read, and 88 did.
     */
    @Test(timeout = 20_000)
    fun aListedIdTheTableLacksIsCountedOnlyWhereNoReadCanShowItAbsent() {
        val ids = (1L..100L) - 20L - 55L
        val table = Table(ids, perPage = 10, damaged = setOf(1), indexed = (1L..100L).toList())
        assertEquals("the damaged leaf is not 11..21 without 20", (11L..21L) - 20L, table.unreadable.sorted())
        val reader = salvaging()
        val outcome = runCatching { walk(reader, table.paged()) }

        assertNull("salvage did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("a readable row was lost, or an unreadable one invented", ids - table.unreadable, outcome.getOrThrow())
        assertEquals(
            "a listed id the table lacks was counted where a read showed it absent, or not where none could",
            mapOf("t" to Unreadable.Counted(recovered = 88, unread = 11)),
            reader.unreadable,
        )
    }

    /**
     * The one readable row salvage loses, so the KDoc's account of it is
     * tested: 30 and 31 are both missing from the index, 30 on the damaged
     * leaf and 31 readable on the next. The failed read at 30 moves the walk
     * to the next listed id, 32, and 31 is not read.
     */
    @Test(timeout = 20_000)
    fun anUnlistedRowStraightAfterAnUnlistedUnreadableOneIsLost() {
        val ids = (1L..100L).toList()
        val table = Table(ids, perPage = 10, damaged = setOf(2), indexed = ids - 30L - 31L)
        val reader = salvaging()
        val outcome = runCatching { walk(reader, table.paged()) }

        assertNull("salvage did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("salvage did not lose exactly 21..31", ids - (21L..31L), outcome.getOrThrow())
        assertEquals(
            "the unlisted ids were counted, or the listed ones not",
            mapOf("t" to Unreadable.Counted(recovered = 89, unread = 9)),
            reader.unreadable,
        )
    }

    /**
     * The shape that does not terminate on its own: every read at or past the
     * damage throws, including the one that would have said nothing is left.
     */
    @Test(timeout = 20_000)
    fun damageThatReachesTheEndOfTheTableStillEnds() {
        val table = Table(gappy, perPage = 10, damaged = setOf(23, 24, 25))
        val reader = salvaging()
        val outcome = runCatching { walk(reader, table.paged()) }

        assertNull("salvage did not end: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("a readable row was lost, or an unreadable one invented", gappy - table.unreadable, outcome.getOrThrow())
        assertEquals(
            "the loss was not counted as the rows the index lists and would not read",
            mapOf("t" to Unreadable.Counted(recovered = gappy.size - table.unreadable.size, unread = table.unreadable.size)),
            reader.unreadable,
        )
    }

    /**
     * Only a corruption is stepped over. The rest would have read on a retry,
     * or mean the database is gone, and stepping over them drops rows silently.
     * "malformed" is in the message of an ordinary SQL error too.
     */
    @Test fun onlyCorruptionIsSteppedOver() {
        val notDamage = listOf(
            SQLiteDiskIOException("disk I/O error (code 10)"),
            SQLiteFullException("database or disk is full (code 13)"),
            SQLiteException("malformed JSON (code 1)"),
            SQLiteException("file is not a database (code 26)"),
            // The class SQLCipher itself throws for code 26; see
            // RowReaderDamageTest for it arriving off a poisoned connection.
            Class.forName("net.zetetic.database.sqlcipher.SQLiteNotADatabaseException")
                .getConstructor(String::class.java)
                .newInstance("file is not a database (code 26)") as SQLiteException,
            SQLException("Error code: 21, message: connection is closed"),
        )
        for (thrown in notDamage) {
            val table = Table(gappy, perPage = 10, damaged = emptySet(), failFirst = thrown)
            val reader = salvaging()
            val outcome = runCatching { walk(reader, table.paged()) }
            assertSame("salvage stepped over ${thrown.javaClass.simpleName} \"${thrown.message}\"", thrown, outcome.exceptionOrNull())
            assertEquals("a failure that is not damage was recorded as loss", emptyMap<String, Unreadable>(), reader.unreadable)
        }
    }

    @Test fun aDamagedIndexIsPassedOverForTheNext() {
        val table = Table(gappy, perPage = 10, damaged = setOf(8), idSourcesDamaged = 1)
        val reader = salvaging()
        val outcome = runCatching { walk(reader, table.paged()) }

        assertNull("salvage did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("a readable row was lost, or an unreadable one invented", gappy - table.unreadable, outcome.getOrThrow())
        assertEquals(
            "a damaged first index was not passed over for the second",
            mapOf("t" to Unreadable.Counted(recovered = gappy.size - table.unreadable.size, unread = table.unreadable.size)),
            reader.unreadable,
        )
    }

    /**
     * With no index, ids are stepped one integer at a time, gaps included, so
     * the figure is how many were stepped and is at least the rows lost.
     */
    @Test(timeout = 20_000)
    fun withEveryIndexDamagedTheLossIsOnlyABound() {
        val table = Table(gappy, perPage = 10, damaged = setOf(8, 25), idSourcesDamaged = 2)
        val reader = salvaging()
        val outcome = runCatching { walk(reader, table.paged()) }

        assertNull("salvage did not end in rows: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("a readable row was lost, or an unreadable one invented", gappy - table.unreadable, outcome.getOrThrow())
        // Every integer whose next stored id sits on a damaged leaf, up to the
        // highest id: what one-at-a-time stepping has to walk over.
        val stepped = (1L..gappy.max()).count { x -> gappy.first { it >= x } in table.unreadable }.toLong()
        assertEquals(
            "the loss was not reported as the ids stepped over",
            mapOf("t" to Unreadable.AtMost(recovered = gappy.size - table.unreadable.size, ids = stepped)),
            reader.unreadable,
        )
        assertTrue("the bound is below the rows actually lost", stepped > table.unreadable.size)
    }

    @Test(timeout = 20_000)
    fun withNothingToBoundTheWalkTheDamageIsRethrown() {
        val table = Table(gappy, perPage = 10, damaged = setOf(8), idSourcesDamaged = 2, sequenceDamaged = true)
        val outcome = runCatching { walk(salvaging(), table.paged()) }
        assertTrue(
            "salvage with no bound answered $outcome rather than giving up",
            outcome.exceptionOrNull() is SQLiteDatabaseCorruptException,
        )
    }
}
