package my.pinged.data

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import my.pinged.data.Access.Why
import my.pinged.data.entity.Category
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DatabaseAccess]'s policy (#302, decision 13) over an in-memory
 * `PingedDatabase` the block fails on command. What needs SQLCipher and real
 * locks stays on a device: `DamagedConnectionTest`, `SharedInstanceUnderUseTest`,
 * `GateRefusalTest` and `DeleteGateTest`.
 *
 * **The instances are real Room databases on Robolectric's SQLite and no block
 * here queries one**: the policy reads an instance's `closed` and `retired`
 * and never a table, so what a block throws is all the test has to say.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseAccessTest {
    private val source = ScriptedSource(ApplicationProvider.getApplicationContext())
    private val access = DatabaseAccess(source)

    @After fun closeAll() = source.closeAll()

    /** Code 11: the block's own statement read the damaged page. */
    private fun code11() = SQLiteDatabaseCorruptException("database disk image is malformed (code 11)")

    /** Code 26: a connection some other read poisoned. */
    private fun code26() = SQLiteNotADatabaseException("file is not a database (code 26)")

    @Test fun code11IsRefusedAfterOneAttemptBecauseANewConnectionMeetsTheSamePage() {
        var attempts = 0

        val result = runBlocking {
            access.write("a capture") { attempts++; throw code11() }
        }

        assertEquals("attempts", 1, attempts)
        assertRefused(Why.Damage, result)
        assertEquals("instances opened for a refusal that no new one could change", 1, source.built.size)
        assertTrue("the damaged instance was not set aside", source.built.single().retired)
    }

    @Test fun code26IsTriedOnANewInstanceUpToAttemptsAndThenRefusedAsDamage() {
        val seen = ArrayList<PingedDatabase>()

        val result = runBlocking {
            access.write("a capture") { db -> seen += db; throw code26() }
        }

        assertEquals("attempts before the refusal", DatabaseAccess.ATTEMPTS, seen.size)
        assertEquals("each attempt a new instance", seen.size, seen.toSet().size)
        assertTrue("an instance that answered code 26 was kept", seen.all { it.retired })
        assertRefused(Why.Damage, result)
    }

    @Test fun code26ThenAnAnswerIsDoneOnTheNewInstanceTheBlockWasHanded() {
        val seen = ArrayList<PingedDatabase>()

        val result = runBlocking {
            access.write("a capture") { db ->
                seen += db
                if (seen.size < 3) throw code26()
                "stored"
            }
        }

        assertEquals(Access.Done("stored"), result)
        assertEquals(3, seen.size)
        assertSame("the block was handed an instance other than the one served", source.current, seen.last())
        assertFalse(seen.last().retired)
    }

    @Test fun aClosedInstanceIsTriedAgainOnANewOne() {
        val seen = ArrayList<PingedDatabase>()

        val result = runBlocking {
            access.write("a write") { db ->
                seen += db
                if (seen.size == 1) {
                    db.close()
                    throw SQLException("connection is closed (code 21)")
                }
                "stored"
            }
        }

        assertEquals(Access.Done("stored"), result)
        assertEquals(2, seen.size)
        assertNotSame(seen[0], seen[1])
        assertEquals("a closed instance is no damage", emptyList<String>(), source.events.filter { it == "record" })
    }

    @Test fun anIllegalStateOnAnInstanceThatIsNotClosedIsAProgrammingErrorAndIsNotRetried() {
        var attempts = 0

        try {
            runBlocking { access.write("a write") { attempts++; throw IllegalStateException("not the pool's") } }
            fail("an IllegalStateException from a live instance was refused instead of thrown")
        } catch (thrown: IllegalStateException) {
            assertEquals("not the pool's", thrown.message)
        }

        assertEquals(1, attempts)
        assertEquals("a throw is no damage", emptyList<String>(), source.events.filter { it == "record" || it == "announce" })
    }

    @Test fun aClosedInstancesOwnExhaustionIsRefusedAsDamageAfterAttempts() {
        var attempts = 0

        val result = runBlocking {
            access.write("a write") { db ->
                attempts++
                db.close()
                throw SQLException("connection is closed (code 21)")
            }
        }

        assertEquals(DatabaseAccess.ATTEMPTS, attempts)
        assertRefused(Why.Damage, result)
    }

    @Test fun aGateIsRefusedAsAGateAndTheBlockNeverRuns() {
        source.gate = true
        var ran = false

        val result = runBlocking { access.write("a capture") { ran = true } }

        assertFalse("the block ran behind a gate", ran)
        assertRefused(Why.Gate, result)
        assertEquals("a gate is no damage", emptyList<String>(), source.events.filter { it == "record" || it == "announce" })
    }

    @Test fun aGateThatRisesAfterAnAttemptMetDamageIsStillAGateAndTheDamageIsStillRecorded() {
        var attempts = 0

        val result = runBlocking {
            access.write("a capture") {
                attempts++
                source.gate = true
                throw code26()
            }
        }

        assertEquals(1, attempts)
        assertRefused(Why.Gate, result)
        assertEquals(listOf("record", "announce"), source.events.filter { it == "record" || it == "announce" })
    }

    @Test fun anyOtherUnavailableIsRefusedAsUnavailable() {
        source.unavailable = DatabaseKeyUnavailableException("the key is gone")

        val result = runBlocking { access.write("a capture") { "unreachable" } }

        assertRefused(Why.Unavailable, result)
    }

    @Test fun damageIsRecordedOnceAndBeforeItIsAnnouncedAndAfterTheRetryHasRun() {
        var attempts = 0

        runBlocking {
            access.write("a capture") {
                source.events += "block ${++attempts}"
                if (attempts < 3) throw code26()
            }
        }

        assertEquals(
            "retire, then the retry, then record, then announce -- once each",
            listOf("block 1", "retire", "block 2", "retire", "block 3", "record", "announce"),
            source.events.filter { it != "lease" && it != "release" },
        )
    }

    @Test fun aRefusalRecordsAndAnnouncesAsOnceToo() {
        runBlocking { access.write("a capture") { throw code11() } }

        assertEquals(listOf("retire", "record", "announce"), source.events.filter { it != "lease" && it != "release" })
    }

    @Test fun nothingIsRecordedOrAnnouncedWhenNothingMetDamage() {
        runBlocking { access.write("a capture") { "fine" } }

        assertEquals(emptyList<String>(), source.events.filter { it == "record" || it == "announce" || it == "retire" })
    }

    @Test fun damageThatSomeoneElseAlreadyReplacedIsRecordedAndNotAnnounced() {
        runBlocking {
            access.write("a capture") { db ->
                // Another caller replaces the instance first.
                source.retire(db)
                throw code11()
            }
        }

        assertEquals("the damage is still news", 1, source.events.count { it == "record" })
        assertEquals("nothing was replaced by this caller, so nothing is announced", 0, source.events.count { it == "announce" })
    }

    @Test fun aNestedDamageScopeRecordsAndAnnouncesOnceWhenTheOuterOneExits() {
        runBlocking {
            access.damageScope {
                access.damageScope {
                    replace(source.instance())
                }
                assertEquals("the inner scope announced before the outer one exited", 0, source.events.count { it == "announce" })
                assertEquals("the inner scope recorded before the outer one exited", 0, source.events.count { it == "record" })
                replace(source.instance())
            }
        }

        assertEquals(1, source.events.count { it == "record" })
        assertEquals(1, source.events.count { it == "announce" })
        assertTrue("record came after announce", source.events.indexOf("record") < source.events.indexOf("announce"))
    }

    @Test fun aWriteInsideADamageScopeJoinsItAndSoRecordsAndAnnouncesOnceHoweverManyPassesReplaced() {
        runBlocking {
            access.damageScope {
                repeat(3) { access.write("a pass") { throw code11() } }
                assertEquals("a pass announced inside the scope", 0, source.events.count { it == "announce" })
            }
        }

        assertEquals("retired", 3, source.events.count { it == "retire" })
        assertEquals("recorded", 1, source.events.count { it == "record" })
        assertEquals("announced", 1, source.events.count { it == "announce" })
    }

    @Test fun aScopeThatThrowsStillRecordsAndAnnouncesWhatItReplaced() {
        try {
            runBlocking { access.damageScope { replace(source.instance()); throw IllegalArgumentException("boom") } }
            fail("the scope swallowed its block's throw")
        } catch (thrown: IllegalArgumentException) {
            assertEquals("boom", thrown.message)
        }

        assertEquals(listOf("retire", "record", "announce"), source.events)
    }

    @Test fun damageNotedWithoutAnInstanceIsRecordedAndRetiresAndAnnouncesNothing() {
        runBlocking { access.damageScope { source.instance(); noteDamage() } }

        assertEquals(listOf("record"), source.events.filter { it != "lease" && it != "release" })
        assertTrue("noting damage retired the instance being served", source.current() != null)
    }

    @Test fun damageNotedAfterAReplacementIsStillRecordedOnce() {
        runBlocking { access.damageScope { replace(source.instance()); noteDamage() } }

        assertEquals(listOf("retire", "record", "announce"), source.events)
    }

    @Test fun everyLeaseIsReleasedWhateverTheBlockDoes() {
        runBlocking {
            access.write("done") { 1 }
            access.write("refused") { throw code11() }
            runCatching { access.write("thrown") { throw IllegalArgumentException() } }
        }

        assertEquals(3, source.events.count { it == "lease" })
        assertEquals(3, source.events.count { it == "release" })
        assertEquals("a lease was held while damage was announced", 0, source.leasesHeld)
    }

    @Test fun theAnnouncementIsMadeWhileTheLeaseIsStillHeld() {
        runBlocking { access.write("a capture") { throw code11() } }

        val events = source.events
        assertTrue(
            "announced after the lease ended, which lets a retired instance close before the feed has rebound",
            events.indexOf("announce") < events.lastIndexOf("release"),
        )
    }

    @Test fun writeHopsToTheIoPoolAndWriteOnThisThreadDoesNot() {
        val caller = Thread.currentThread()
        var inWrite: Thread? = null
        var inOrder: Thread? = null

        runBlocking {
            access.write("a write") { inWrite = Thread.currentThread() }
            access.writeOnThisThread("an insert") { inOrder = Thread.currentThread() }
        }

        assertNotEquals("write ran on the caller's thread", caller, inWrite)
        assertSame("writeOnThisThread hopped", caller, inOrder)
    }

    @Test fun readRunsItsBlockOnTheIoPoolAndRefusesAsWriteDoes() {
        val caller = Thread.currentThread()
        var inRead: Thread? = null

        val done = runBlocking { access.read("a read") { inRead = Thread.currentThread(); 7 } }
        val refused = runBlocking { access.read("a read") { throw code11() } }

        assertEquals(Access.Done(7), done)
        assertNotEquals("read ran on the caller's thread", caller, inRead)
        assertRefused(Why.Damage, refused)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aReadFromInsideATransactionOnTheServedInstanceThrowsRatherThanDeadlocking() {
        val served = source.instance()
        var ran = false

        try {
            served.runInTransaction(Callable { runBlocking { access.read("a nested read") { ran = true } } })
            fail("a read from inside a transaction was run, where the real one waits on its own connection")
        } catch (thrown: IllegalStateException) {
            assertTrue(thrown.message, thrown.message!!.contains("inside a transaction"))
        }

        assertFalse("the nested read's block ran", ran)
        assertEquals("a refused call took no lease", 0, source.events.count { it == "lease" })
    }

    @Test fun aReadFromOutsideAnyTransactionIsNotRefusedEvenWithAnInstanceServed() {
        source.instance()

        val result = runBlocking { access.read("a read") { 3 } }

        assertEquals(Access.Done(3), result)
    }

    @Test fun writeInOneTransactionRunsItsBlockInOneAndAPlainWriteInNone() {
        var plain: Boolean? = null
        var single: Boolean? = null

        runBlocking {
            access.write("a write") { db -> plain = db.inTransaction() }
            access.write("a write that needs one", oneTransaction = true) { db -> single = db.inTransaction() }
        }

        assertEquals("a plain write opened a transaction", false, plain)
        assertEquals("write(oneTransaction = true) ran its block outside a transaction", true, single)
    }

    @Test fun aWriteInOneTransactionIsUndoneWhenItsBlockThrowsAndAPlainOneIsNot() {
        val categories = { source.instance().categoryDao() }
        val partial = { db: PingedDatabase ->
            db.categoryDao().insert(Category(name = "Kept", iconKey = "k", sortOrder = 1))
            throw IllegalArgumentException("after the first statement")
        }

        runCatching { runBlocking { access.write("plain") { db -> partial(db) } } }
        assertEquals("a plain write's first statement stays", 1, categories().countAll())

        runCatching {
            runBlocking {
                access.write("one transaction", oneTransaction = true) { db ->
                    db.categoryDao().insert(Category(name = "Undone", iconKey = "u", sortOrder = 2))
                    throw IllegalArgumentException("after the first statement")
                }
            }
        }
        assertEquals("a statement of a transaction that threw was kept", 1, categories().countAll())
    }

    @Test fun writeInOneTransactionRetriesAndRefusesAsAPlainWriteDoes() {
        val seen = ArrayList<PingedDatabase>()

        val retried = runBlocking {
            access.write("a write", oneTransaction = true) { db ->
                seen += db
                if (seen.size < 3) throw code26()
                "stored"
            }
        }
        val refused = runBlocking { access.write("a write", oneTransaction = true) { throw code11() } }

        assertEquals(Access.Done("stored"), retried)
        assertEquals("attempts", 3, seen.size)
        assertEquals("each attempt a new instance", 3, seen.toSet().size)
        assertRefused(Why.Damage, refused)
    }

    @Test fun writeInOneTransactionRunsOnTheIoPool() {
        val caller = Thread.currentThread()
        var ran: Thread? = null

        runBlocking { access.write("a write", oneTransaction = true) { ran = Thread.currentThread() } }

        assertNotEquals("the block ran on the caller's thread", caller, ran)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aWriteInOneTransactionFromInsideATransactionOnTheServedInstanceThrowsRatherThanDeadlocking() {
        val served = source.instance()
        var ran = false

        try {
            served.runInTransaction(
                Callable { runBlocking { access.write("nested", oneTransaction = true) { ran = true } } },
            )
            fail("a write from inside a transaction was run, where the real one waits on its own connection")
        } catch (thrown: IllegalStateException) {
            assertTrue(thrown.message, thrown.message!!.contains("inside a transaction"))
        }

        assertFalse("the nested write's block ran", ran)
    }

    @Test fun aReadRunsItsBlockInOneTransaction() {
        var inside: Boolean? = null

        runBlocking { access.read("a read") { db -> inside = db.inTransaction() } }

        assertEquals(true, inside)
    }

    @Test fun leasedHandsTheBlockTheServedInstanceUnderALeaseAndReleasesIt() {
        var held = -1
        var handed: PingedDatabase? = null

        val answer = runBlocking {
            access.leased { db ->
                handed = db
                held = source.leasesHeld
                "counted"
            }
        }

        assertEquals("counted", answer)
        assertSame("the block was handed an instance other than the one served", source.current, handed)
        assertEquals("the lease was not held while the block ran", 1, held)
        assertEquals("the lease was kept after the block", 0, source.leasesHeld)
    }

    @Test fun leasedTakesItsLeaseBeforeAskingForTheInstance() {
        runBlocking { access.leased { } }

        assertEquals(
            "an instance retired between the open and the lease could be closed under the block",
            listOf(1),
            source.instanceAskedWithLeasesHeld,
        )
    }

    @Test fun leasedReleasesItsLeaseWhateverHappens() {
        runCatching { runBlocking { access.leased { throw IllegalArgumentException("boom") } } }
        source.gate = true
        runCatching { runBlocking { access.leased { "unreachable" } } }

        assertEquals(2, source.events.count { it == "lease" })
        assertEquals("a lease was kept", 0, source.leasesHeld)
    }

    @Test fun leasedDoesNotRetryOrRefuseAndLetsDamageThrough() {
        var attempts = 0

        try {
            runBlocking { access.leased { attempts++; throw code26() } }
            fail("leased swallowed what its block threw")
        } catch (thrown: SQLiteNotADatabaseException) {
            assertTrue(thrown.message, thrown.message!!.contains("code 26"))
        }

        assertEquals("leased ran its block again", 1, attempts)
        assertEquals("leased replaced, recorded or announced damage itself", emptyList<String>(), source.events.filter { it == "retire" || it == "record" || it == "announce" })
    }

    @Test fun leasedLetsAGateThroughAsTheExceptionAndNeverRunsTheBlock() {
        source.gate = true
        var ran = false

        try {
            runBlocking { access.leased { ran = true } }
            fail("leased ran its block behind a gate")
        } catch (thrown: DatabaseBeingDeletedException) {
            assertFalse(ran)
        }
    }

    @Test fun leasedRunsOnTheCallersThread() {
        val caller = Thread.currentThread()
        var ran: Thread? = null

        runBlocking { access.leased { ran = Thread.currentThread() } }

        assertSame("leased hopped, and its callers already run on the IO pool", caller, ran)
    }

    @Test fun leasedInsideADamageScopeLetsTheScopeAnnounceOnlyAfterTheLeaseIsReleased() {
        runBlocking {
            access.damageScope {
                access.leased { db -> replace(db) }
            }
        }

        assertEquals(
            listOf("lease", "retire", "release", "record", "announce"),
            source.events,
        )
    }

    private fun assertRefused(why: Why, result: Access<*>) {
        assertTrue("expected a refusal for $why but got $result", result is Access.Refused)
        assertEquals(why, (result as Access.Refused).why)
    }

    /** A [DatabaseAccess.Source] over in-memory databases, that records what the policy asked of it, in order. */
    private class ScriptedSource(private val context: Context) : DatabaseAccess.Source {
        val events = CopyOnWriteArrayList<String>()
        val built = CopyOnWriteArrayList<PingedDatabase>()
        @Volatile var current: PingedDatabase? = null
        @Volatile var gate = false
        @Volatile var unavailable: DatabaseUnavailableException? = null
        @Volatile var leasesHeld = 0

        override fun current(): PingedDatabase? = current?.takeIf { !it.retired && !it.closed }

        /** [leasesHeld], each time [instance] was asked for. */
        val instanceAskedWithLeasesHeld = CopyOnWriteArrayList<Int>()

        override fun instance(): PingedDatabase {
            instanceAskedWithLeasesHeld += leasesHeld
            if (gate) throw DatabaseBeingDeletedException("deleting")
            unavailable?.let { throw it }
            return current?.takeIf { !it.retired && !it.closed } ?: synchronized(this) {
                current?.takeIf { !it.retired && !it.closed } ?: build().also { current = it }
            }
        }

        private fun build(): PingedDatabase =
            Room.inMemoryDatabaseBuilder(context, PingedDatabase::class.java).allowMainThreadQueries().build()
                .also { built += it }

        override fun <T> inOneTransaction(block: (PingedDatabase) -> T): T {
            val database = instance()
            return database.runInTransaction(Callable { block(database) })
        }

        override fun retire(poisoned: PingedDatabase): Boolean = synchronized(this) {
            events += "retire"
            if (current !== poisoned) return false
            current = null
            poisoned.retire()
            true
        }

        override fun announceReplacement() {
            events += "announce"
        }

        override fun lease() {
            events += "lease"
            leasesHeld++
        }

        override fun release() {
            events += "release"
            leasesHeld--
        }

        override suspend fun recordDamage() {
            events += "record"
        }

        fun closeAll() = built.forEach { runCatching { it.close() } }
    }

    private companion object {
        /** A test that deadlocks when its behaviour is reverted fails here instead of hanging the build. */
        const val TIMEOUT_MILLIS = 15_000L
    }
}
