package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import my.pinged.data.entity.CaptureDay
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 12's claim, read back: a ledger exported and re-imported is the same
 * ledger.
 *
 * `ExportCompletenessTest` catches a forgotten column on the *write* side; this
 * catches one on the read side, and can only do so because the fixture leaves
 * no field at a value a dropped column would also produce -- see
 * `assertMaximal`.
 */
@RunWith(AndroidJUnit4::class)
class RoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    /** Closes the previous handle before deleting the file it is holding open. */
    private fun fresh(): PingedDatabase {
        db?.close()
        db = null
        return freshDatabase(context).also { db = it }
    }

    private fun <T : Any> Iterable<T>.byKey(key: (T) -> Any): Map<Any, Any> =
        associateBy({ key(it) }, { it })

    /** Everything in the database, keyed by table and then by primary key. */
    private fun contentsOf(source: PingedDatabase): Map<String, Map<Any, Any>> = mapOf(
        "category" to source.categoryDao().all().byKey { it.id },
        "capture_source" to source.captureSourceDao().all().byKey { it.pkg },
        "capture_day" to source.captureDayDao().all().byKey { it.localDate },
        "merchant_rule" to source.merchantRuleDao().pageFrom(0L, 1_000).byKey { it.id },
        "raw_capture" to source.rawCaptureDao().pageFrom(0L, 1_000).byKey { it.id },
        "txn" to source.txnDao().pageFrom(0L, 1_000).byKey { it.id },
        "merchant_alias" to source.merchantIdentityDao().allAliases().byKey { it.merchantKey },
        "merchant_name" to source.merchantIdentityDao().allNames().byKey { it.merchantKey },
    )

    /**
     * The comparison, field by field and by reflection. Not
     * `assertEquals(before, after)`, which passes and fails in the same cases but
     * prints a `Txn` with twenty-one components as two near-identical lines for the
     * reader to diff by eye.
     */
    private fun assertSameContents(
        before: Map<String, Map<Any, Any>>,
        after: Map<String, Map<Any, Any>>,
    ) {
        assertEquals(before.keys, after.keys)
        before.forEach { (table, rows) ->
            val restored = after.getValue(table)
            assertEquals(
                "$table: different primary keys after the round trip",
                rows.keys,
                restored.keys,
            )
            rows.forEach { (key, row) ->
                val originalFields = fieldValues(row)
                val restoredFields = fieldValues(restored.getValue(key))
                originalFields.forEach { (field, value) ->
                    assertEquals(
                        "$table[$key].$field did not survive the export and the import. " +
                            "A field that comes back at its Kotlin default is a column " +
                            "the export or the import never touched.",
                        value,
                        restoredFields[field],
                    )
                }
            }
        }
    }

    @Test fun everyFieldOfEveryEntitySurvives() {
        val original = fresh()
        val seeded = seedOneOfEverything(original)

        // The fixture is only worth as much as its least distinctive row.
        assertMaximal(original.rawCaptureDao().pageFrom(0L, 100).last())
        assertMaximal(original.txnDao().pageFrom(0L, 100).last())
        assertMaximal(original.merchantRuleDao().pageFrom(0L, 100).last())
        assertMaximal(original.captureSourceDao().all().first { it.pkg == "com.maybank2u.life" })
        assertMaximal(original.captureDayDao().all().last())
        assertMaximal(original.categoryDao().all().first { it.name == UNCATEGORIZED_NAME })

        val before = contentsOf(original)
        val bytes = exportBytes(original)

        val restored = fresh()
        val report = ImportJson.read(restored, ByteArrayInputStream(bytes))

        assertEquals(15, report.categories)
        assertEquals(2, report.captureSources)
        assertEquals(2, report.captureDays)
        assertEquals(2, report.merchantRules)
        assertEquals(4, report.rawCaptures)
        assertEquals(2, report.txns)
        assertEquals(1, report.merchantAliases)
        assertEquals(1, report.merchantNames)
        assertEquals(29, report.rows)
        assertEquals(Backup.FORMAT_VERSION, report.formatVersion)
        assertNotNull("The export stamps its own time and the report carries it", report.exportedAt)

        assertSameContents(before, contentsOf(restored))

        // Named individually as well, because these three are the newest
        // values in the schema and the ones an export written from an older
        // reading of spec 4 would not know about.
        val captures = restored.rawCaptureDao().pageFrom(0L, 100)
        assertEquals(ParseStatus.GAVE_UP, captures.first { it.contentHash == "h2" }.parseStatus)
        assertEquals(Arrival.CATCHUP, captures.first { it.contentHash == "h4" }.arrival)
        assertEquals(Arrival.POSTED, captures.first { it.contentHash == "h1" }.arrival)
        val pending = restored.txnDao().pageFrom(0L, 100).first { it.state == TxnState.PENDING }
        assertEquals(PendingReason.DUPLICATE_SUSPECT, pending.pendingReason)
        assertEquals(seeded.userCategory, pending.categoryId)
    }

    /**
     * A format-1 file -- every export before spec 6.4 -- still restores, with
     * nothing merged and nothing renamed, which is what its ledger held.
     */
    @Test fun aFormatOneFileRestoresWithNoMerges() {
        val original = fresh()
        seedOneOfEverything(original)
        val current = String(exportBytes(original), Charsets.UTF_8)
        val formatOne = current
            .replaceFirst("{\"${Backup.FIELD_FORMAT}\":${Backup.FORMAT_VERSION}", "{\"${Backup.FIELD_FORMAT}\":1")
            .let { it.substring(0, it.indexOf(",\"${Backup.MERCHANT_ALIASES}\":")) + "}" }

        val restored = fresh()
        val report = ImportJson.read(restored, ByteArrayInputStream(formatOne.toByteArray(Charsets.UTF_8)))

        assertEquals(1, report.formatVersion)
        assertEquals(2, report.txns)
        assertEquals(0, report.merchantAliases)
        assertEquals(0, restored.merchantIdentityDao().countAliases())
        assertEquals(0, restored.merchantIdentityDao().countNames())
    }

    /**
     * A user-added category comes back, and comes back with the id its
     * transactions and its learned rules name.
     *
     * The seeded fourteen would survive an import that never touched the
     * `category` table at all, so on their own they say nothing about whether
     * the section works.
     */
    @Test fun aCategoryTheUserAddedComesBackWithItsOwnId() {
        val original = fresh()
        val seeded = seedOneOfEverything(original)
        val bytes = exportBytes(original)

        val restored = fresh()
        ImportJson.read(restored, ByteArrayInputStream(bytes))

        val kopi = restored.categoryDao().all().first { it.name == "Kopi" }
        assertEquals(seeded.userCategory, kopi.id)
        assertEquals(99, kopi.sortOrder)
        assertEquals(
            1,
            restored.txnDao().pageFrom(0L, 100).count { it.categoryId == kopi.id },
        )
    }

    /**
     * The restored ledger is a working one, not just a readable one.
     *
     * `id` is `INTEGER PRIMARY KEY AUTOINCREMENT`, so the next id comes from
     * `sqlite_sequence` and not from `max(rowid)`. An import that wrote explicit
     * ids without leaving that counter above them would hand the next capture an id
     * already taken, and the first thing the user did after restoring would fail.
     */
    @Test fun theRestoredLedgerCanStillTakeANewCapture() {
        val original = fresh()
        seedOneOfEverything(original)
        val bytes = exportBytes(original)

        val restored = fresh()
        ImportJson.read(restored, ByteArrayInputStream(bytes))

        val highest = restored.rawCaptureDao().pageFrom(0L, 100).maxOf { it.id }
        val next = restored.rawCaptureDao().insert(
            restored.rawCaptureDao().byId(highest).copy(id = 0, contentHash = "after-restore"),
        )
        assertTrue(
            "The next capture got id $next, which is not above the restored $highest.",
            next > highest,
        )
        assertEquals(5, restored.rawCaptureDao().countAll())
    }

    /**
     * An empty ledger round-trips too, and the file still has all six
     * sections. A backup taken the day the app is installed is the one a user
     * is most likely to take by accident, and it must not be the one that
     * fails to import.
     */
    @Test fun anEmptyLedgerRoundTrips() {
        val original = fresh()
        val bytes = exportBytes(original)

        val restored = fresh()
        val report = ImportJson.read(restored, ByteArrayInputStream(bytes))

        assertEquals(14, report.categories)
        assertEquals(0, report.rawCaptures)
        assertEquals(0, report.txns)
        assertEquals(0, restored.rawCaptureDao().countAll())
        assertEquals(14, restored.categoryDao().all().size)
        assertNotNull(restored.categoryDao().uncategorizedIdOrNull())
    }

    /**
     * The guard under the guard.
     *
     * `assertMaximal` is what makes `everyFieldOfEveryEntitySurvives` mean
     * anything, and it used to hold a hand-written list of the values a dropped
     * column would produce. Two of its seven entries, `ParseStatus.NEW` and
     * `"MYR"`, are the only non-obvious defaults in the schema -- so a derivation
     * that finds them is finding real defaults rather than restating zeroes, and a
     * field defaulted tomorrow is found the same way.
     */
    @Test fun theDefaultsAreReadOffTheEntitiesRatherThanListed() {
        assertEquals(ParseStatus.NEW, defaultsOf(RawCapture::class.java)["parseStatus"])
        assertEquals("MYR", defaultsOf(Txn::class.java)["currency"])
        assertEquals(false, defaultsOf(Txn::class.java)["isExcluded"])
        assertEquals(0L, defaultsOf(Txn::class.java)["id"])
        // CaptureDay declares no defaults at all, so there is nothing to find.
        assertEquals(emptyMap<String, Any?>(), defaultsOf(CaptureDay::class.java))
    }
}
