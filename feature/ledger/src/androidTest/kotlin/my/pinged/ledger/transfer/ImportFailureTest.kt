package my.pinged.ledger.transfer

import my.pinged.data.LocalDate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import my.pinged.data.PingedDatabase
import my.pinged.data.entity.CaptureSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The six ways an import goes wrong, and what each one does to the database.
 *
 * Every case asserts two things: that the failure is reported as one of this
 * module's own exceptions with a message a person can act on, and -- the part
 * that matters -- that the database is exactly as it was.
 *
 * "Exactly as it was" is checked as *fourteen categories and nothing else*,
 * which is what `DatabaseFactory.build` leaves behind. That is deliberately
 * awkward to satisfy: the import deletes those fourteen before writing the
 * file's, and inserts every capture before it reaches the first transaction, so
 * any trace shows up as a missing category or a surviving capture.
 */
@RunWith(AndroidJUnit4::class)
class ImportFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db: PingedDatabase? = null

    @After fun tearDown() {
        db?.close()
        db = null
    }

    private fun fresh(): PingedDatabase {
        db?.close()
        db = null
        return freshDatabase(context).also { db = it }
    }

    /** A real export of a real ledger, as text, ready to be damaged. */
    private fun goodExport(): String {
        val source = fresh()
        seedOneOfEverything(source)
        val bytes = exportBytes(source)
        return String(bytes, Charsets.UTF_8)
    }

    private fun importInto(target: PingedDatabase, document: String) =
        ImportJson.read(target, ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))

    /** Nothing of the file arrived, and nothing of the seed left. */
    private fun assertUntouched(target: PingedDatabase) {
        assertEquals("a capture survived a failed import", 0, target.rawCaptureDao().countAll())
        assertEquals("a transaction survived a failed import", 0, target.txnDao().countAll())
        assertEquals("a learned rule survived", 0, target.merchantRuleDao().countAll())
        assertEquals("a capture source survived", 0, target.captureSourceDao().countAll())
        assertEquals("a capture day survived", 0, target.captureDayDao().countAll())
        assertEquals("a merged merchant survived", 0, target.merchantIdentityDao().countAliases())
        assertEquals("a merchant's name survived", 0, target.merchantIdentityDao().countNames())
        assertEquals(
            "the seeded categories were deleted and not put back",
            14,
            target.categoryDao().all().size,
        )
    }

    // ---- a file that is not a backup ------------------------------------

    @Test fun malformedJsonIsReportedNotCrashed() {
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, "{ not json")
        }
        assertTrue(thrown.message!!.contains("Nothing was imported"))
        assertUntouched(target)
    }

    @Test fun aFileThatDoesNotNameItsFormatFirstIsRefused() {
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, """{"categories":[],"format":1}""")
        }
        assertTrue(thrown.message!!.contains(Backup.FIELD_FORMAT))
        assertUntouched(target)
    }

    // ---- a file from a future version -----------------------------------

    /**
     * Refused by number, before a row of it is read.
     *
     * The document below is otherwise a valid backup -- it is a real export
     * with one digit changed -- so an importer that gated on anything other
     * than the version would import it happily and drop whatever the next
     * version added.
     */
    @Test fun aFutureFormatVersionIsRefusedByNumberAndNotByWhatItFailsOn() {
        val future = Backup.FORMAT_VERSION + 1
        val document = goodExport().replaceFirst(
            "{\"${Backup.FIELD_FORMAT}\":${Backup.FORMAT_VERSION}", "{\"${Backup.FIELD_FORMAT}\":$future",
        )
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message has to name both versions: ${thrown.message}",
            thrown.message!!.contains("format version $future") &&
                thrown.message!!.contains("versions ${Backup.OLDEST_READABLE} to ${Backup.FORMAT_VERSION}"),
        )
        assertTrue(thrown.message!!.contains("update Pinged"))
        assertUntouched(target)
    }

    // ---- a truncated file -----------------------------------------------

    /**
     * Cut in the middle of the transactions, which is the interesting place:
     * by then the fourteen seeded categories have been deleted and fifteen
     * written in their place, and every source, day, learned rule and capture
     * in the file is in the database. An importer that committed per chunk
     * would leave all of it behind, and the user would be looking at a ledger
     * with every capture and no money in it.
     */
    @Test fun aTruncatedFileLeavesNothingBehind() {
        val document = goodExport()
        val cut = document.indexOf("\"${Backup.TXNS}\":[") + 40
        assertTrue("the fixture is too small to cut", cut in 1 until document.length)
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document.substring(0, cut))
        }
        assertTrue(thrown.message!!.contains("Nothing was imported"))
        assertUntouched(target)
    }

    @Test fun aFileMissingAWholeSectionIsRefused() {
        // Everything up to the transactions, closed off so that it is valid
        // JSON. The reader gets to the end of a well-formed document and finds
        // it has never seen a `txns` section.
        val document = goodExport()
        val truncatedButValid =
            document.substring(0, document.indexOf(",\"${Backup.TXNS}\":")) + "}"
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, truncatedButValid)
        }
        assertTrue(
            "The message has to say which section: ${thrown.message}",
            thrown.message!!.contains(Backup.TXNS),
        )
        assertUntouched(target)
    }

    @Test fun sectionsOutOfOrderAreRefused() {
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(
                target,
                """{"${Backup.FIELD_FORMAT}":1,"${Backup.TXNS}":[],"${Backup.CATEGORIES}":[]}""",
            )
        }
        assertTrue(thrown.message!!.contains("out of order"))
        assertUntouched(target)
    }

    /**
     * A file that says format 1 and carries spec 6.4's sections disagrees with
     * itself, and is refused rather than half-believed.
     */
    @Test fun aFormatOneFileCarryingAMergeIsRefused() {
        val document = goodExport().replaceFirst(
            "{\"${Backup.FIELD_FORMAT}\":${Backup.FORMAT_VERSION}", "{\"${Backup.FIELD_FORMAT}\":1",
        )
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message has to name the section: ${thrown.message}",
            thrown.message!!.contains(Backup.MERCHANT_ALIASES),
        )
        assertUntouched(target)
    }

    /**
     * `merchant_alias` is kept one level deep by its only writer, and a file
     * can say anything: an alias pointing at a merged merchant rolls back.
     */
    @Test fun aChainedMergeRollsTheWholeImportBack() {
        val document = goodExport().editingOnly(Backup.MERCHANT_ALIASES) {
            it.replaceFirst(
                "[{",
                "[{\"merchant_key\":\"STARBUCKS KLCC\",\"canonical_key\":\"ELSEWHERE\"},{",
            )
        }
        val target = fresh()
        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains("merged"))
        assertUntouched(target)
    }

    /**
     * Spec #45: `BUNDLED` stays in the enum because `EnumVocabularyTest` freezes
     * it, but the dictionary is parser-pack data and nothing writes such a row,
     * so a file carrying one did not come from Pinged. Refused by name, and
     * rolled back like any other refusal.
     */
    @Test fun aBundledRuleRowIsRefused() {
        val document = goodExport().editingOnly(Backup.MERCHANT_RULES) {
            it.replaceFirst("\"origin\":\"LEARNED\"", "\"origin\":\"BUNDLED\"")
        }
        assertTrue("The fixture has no rule to turn into a BUNDLED one", document.contains("\"origin\":\"BUNDLED\""))
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message has to name the value: ${thrown.message}",
            thrown.message!!.contains("BUNDLED") && thrown.message!!.contains("Nothing was imported"),
        )
        assertUntouched(target)
    }

    // ---- a file whose rows do not resolve --------------------------------

    /**
     * **The failure mode that must not half-write, and the reason the whole import
     * is one transaction.**
     *
     * `txn.category_id` is an `ON DELETE RESTRICT` foreign key, so a transaction
     * naming an absent category fails with SQLite 787 -- and transactions are the
     * *last* section, so by then the import has deleted fourteen categories and
     * written fifteen, two sources, two days, two rules and four captures.
     */
    @Test fun aTransactionNamingAMissingCategoryRollsTheWholeImportBack() {
        val document = goodExport().editingOnly(Backup.TXNS) {
            it.replace("\"category_id\":", "\"category_id\":9999")
        }
        val target = fresh()
        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message should point at the likely cause: ${thrown.message}",
            thrown.message!!.contains("category"),
        )
        assertUntouched(target)
    }

    /**
     * `txn.raw_capture_id` has a unique index and **no** foreign key, so
     * SQLite accepts a transaction whose capture does not exist. Nothing in
     * the app can produce one; an edited backup can, and then the money has no
     * evidence behind it. The check that catches it runs at the end of the
     * import's own transaction.
     */
    @Test fun aTransactionPointingAtAMissingCaptureIsRefused() {
        val document = goodExport().editingOnly(Backup.TXNS) {
            it.replace("\"raw_capture_id\":", "\"raw_capture_id\":8888")
        }
        val target = fresh()
        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains("captured notification"))
        assertUntouched(target)
    }

    /** The same gap on `raw_capture.duplicate_of_id`, which has no key either. */
    @Test fun aCaptureMarkedDuplicateOfNothingIsRefused() {
        val document = goodExport().replace("\"duplicate_of_id\":1", "\"duplicate_of_id\":7777")
        val target = fresh()
        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains("duplicates"))
        assertUntouched(target)
    }

    /**
     * A ledger with no Uncategorized row restores cleanly and then throws on
     * the first capture, which is the definition of a restore that looks
     * successful and is not.
     */
    @Test fun aBackupWithNoUncategorizedCategoryIsRefused() {
        val document = goodExport().replace("\"$UNCATEGORIZED_NAME\"", "\"Miscellaneous\"")
        val target = fresh()
        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains(UNCATEGORIZED_NAME))
        assertUntouched(target)
    }

    @Test fun aRowMissingOneColumnNamesTheColumn() {
        val document = goodExport().replace(",\"pending_reason\":\"DUPLICATE_SUSPECT\"", "")
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message has to name the table and the column: ${thrown.message}",
            thrown.message!!.contains("txn") && thrown.message!!.contains("pending_reason"),
        )
        assertUntouched(target)
    }

    /**
     * An enum constant this build does not know is refused rather than
     * guessed at. The alternative -- treating it as null, or as the first
     * value -- changes what the row means, and for `parse_status` it changes
     * whether the capture is re-parsed later.
     */
    @Test fun anUnknownEnumConstantIsRefused() {
        val document =
            goodExport().replace("\"parse_status\":\"GAVE_UP\"", "\"parse_status\":\"SHRUGGED\"")
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message should name the value and the vocabulary: ${thrown.message}",
            thrown.message!!.contains("SHRUGGED") && thrown.message!!.contains("GAVE_UP"),
        )
        assertUntouched(target)
    }

    /**
     * `Txn.requireStorable` is on the DAO, so the import inherits it
     * by going through `TxnDao.insert` rather than round the back. A backup
     * carrying a `COMMITTED` transaction that still names a review reason is a
     * row no other writer in this app can produce.
     */
    @Test fun aSelfContradictingTransactionIsRefused() {
        val document = goodExport().replace("\"state\":\"PENDING\"", "\"state\":\"COMMITTED\"")
        val target = fresh()
        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains("contradicts itself"))
        assertUntouched(target)
    }

    // ---- a database that already holds a ledger --------------------------

    /**
     * Refused, and refused before anything is written.
     *
     * The plan asked for "re-importing twice does not duplicate", and this is that
     * met the safe way round. The alternative -- key the inserts on the exported id
     * so a second import replaces -- makes a *first* import into a device that has
     * been capturing since the export overwrite this month's transaction 41 with
     * last month's, silently.
     */
    @Test fun importingIntoALedgerThatAlreadyHasDataIsRefusedAndChangesNothing() {
        val document = goodExport()
        val target = fresh()
        val first = importInto(target, document)
        assertEquals(4, first.rawCaptures)

        val thrown = assertThrows(ImportNotEmptyException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "The message has to say what is in the way and what to do: ${thrown.message}",
            thrown.message!!.contains("4 captured notification(s)") &&
                thrown.message!!.contains("delete all data"),
        )

        // Unchanged, not doubled and not replaced.
        assertEquals(4, target.rawCaptureDao().countAll())
        assertEquals(2, target.txnDao().countAll())
        assertEquals(2, target.merchantRuleDao().countAll())
        assertEquals(15, target.categoryDao().all().size)
    }

    /**
     * A fresh install that has already bound its listener and discovered a
     * source is still importable. Those two tables are bookkeeping the file
     * replaces; only the three tables holding irreplaceable rows block an
     * import, and getting that boundary wrong in the safe-looking direction
     * would make restore unusable in the one flow it exists for.
     */
    @Test fun aFreshInstallThatHasAlreadyBoundItsListenerCanStillImport() {
        val document = goodExport()
        val target = fresh()
        target.captureDayDao().recordListenerBound(LocalDate(20_260_301), true)
        target.captureSourceDao().insertIfNew(
            CaptureSource(pkg = "com.example.newbank", label = "New Bank", firstSeenAt = 1L),
        )

        val report = importInto(target, document)

        assertEquals(4, report.rawCaptures)
        assertEquals(
            "the file's allow-list replaces the target's",
            setOf("my.com.tngdigital.ewallet", "com.maybank2u.life"),
            target.captureSourceDao().all().map { it.pkg }.toSet(),
        )
        assertEquals(2, target.captureDayDao().countAll())
    }

    // ---- row ids --------------------------------------------------------

    /**
     * A negative row id restores cleanly and then vanishes from every backup,
     * in **every table's id and not just `txn`'s**.
     *
     * Export walks `txn`, `raw_capture` and `merchant_rule` with a keyset
     * cursor -- `WHERE id > :after`, starting at 0 -- so a row numbered below
     * one is skipped by every export this device will ever write. The user
     * restores a file, sees the transaction in the app, backs up, and it is not
     * in the backup.
     *
     * The first pair of these mutated `"id":1,"raw_capture_id"`, which is the
     * txn row and nothing else, so `rowId` could have been reverted on
     * `category`, `merchant_rule` or `raw_capture` individually with the suite
     * staying green. Each section is identified by the column following its id.
     */
    @Test fun aNegativeRowIdIsRefusedInEverySection() {
        for (nextColumn in listOf("name", "match_type", "source_package", "raw_capture_id")) {
            val document = goodExport()
                .replace(""""id":1,"$nextColumn"""", """"id":-1,"$nextColumn"""")
            val target = fresh()
            val thrown = assertThrows(
                "a negative id in the section keyed by '$nextColumn' was accepted",
                ImportFormatException::class.java,
            ) { importInto(target, document) }
            assertTrue(thrown.message!!.contains("Row ids must be 1 or more"))
            assertUntouched(target)
        }
    }

    /**
     * `raw_capture.user_reject_rule_id` is the one reference in this format
     * with no foreign key and no dangling-count check, because the table it
     * points at is spec 5.7's and this milestone does not create it. Its shape
     * is still checkable, and an id below one could never have been assigned.
     */
    @Test fun aNegativeUserRejectRuleIdIsRefused() {
        val document = goodExport().replace(""""user_reject_rule_id":77""", """"user_reject_rule_id":-77""")
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) { importInto(target, document) }
        assertTrue(thrown.message!!.contains("Row ids must be 1 or more"))
        assertUntouched(target)
    }

    @Test fun aNegativeRowIdIsRefused() {
        // goodExport() builds its ledger through fresh(), which closes the
        // previous handle -- so the document has to exist before the target
        // does, exactly as every other case in this file arranges it.
        val document = goodExport().replace(""""id":1,"raw_capture_id"""", """"id":-1,"raw_capture_id"""")
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains("Row ids must be 1 or more"))
        assertTrue(thrown.message!!.contains("Nothing was imported"))
        assertUntouched(target)
    }

    /**
     * And zero, which Room's `autoGenerate` reads as "assign me one" -- so the
     * row would be stored under an id the file did not name, and the file's own
     * references to it would point at nothing.
     */
    @Test fun aZeroRowIdIsRefused() {
        // goodExport() builds its ledger through fresh(), which closes the
        // previous handle -- so the document has to exist before the target
        // does, exactly as every other case in this file arranges it.
        val document = goodExport().replace(""""id":1,"raw_capture_id"""", """"id":0,"raw_capture_id"""")
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document)
        }
        assertTrue(thrown.message!!.contains("Row ids must be 1 or more"))
        assertUntouched(target)
    }

    // ---- the database itself failing --------------------------------------

    /**
     * A storage failure that is not a constraint violation is reported as one of
     * this module's own exceptions, not thrown raw at the caller.
     *
     * A full disk and an IO error are realistic in exactly spec 11.1's situation --
     * restoring 50,000 captures onto a new phone -- and uncaught they fall outside
     * the sealed `ImportException` hierarchy `Backup` documents as "everything an
     * import can refuse for". The catch was added with nothing testing it: deleting
     * it left the suite green.
     *
     * A missing table stands in for the class -- same `SQLiteException` route as a
     * full disk, reachable by a caller with a damaged database, and arrangeable on
     * an emulator.
     */
    @Test fun aStorageFailureThatIsNotAConstraintIsStillAnImportException() {
        val document = goodExport()
        val target = fresh()
        // Not one of the three `requireEmptyLedger` counts, so the failure
        // lands where the import is writing rather than before it starts.
        target.openHelper.writableDatabase.execSQL("DROP TABLE capture_day")

        val thrown = assertThrows(ImportIntegrityException::class.java) {
            importInto(target, document)
        }
        assertTrue(
            "the message should tell the user this is their device and not the file",
            thrown.message!!.contains("storage problem on this device"),
        )
        assertTrue(thrown.message!!.contains("Nothing was imported"))
        assertEquals("a capture survived", 0, target.rawCaptureDao().countAll())
        assertEquals("a transaction survived", 0, target.txnDao().countAll())
    }

    /**
     * Two documents concatenated are refused.
     *
     * A branch that tried to report this in its own words was removed as
     * unreachable -- `android.util.JsonReader` is not lenient, so a second object
     * raises `MalformedJsonException` first -- and nothing was left asserting the
     * refusal, so the bare `reader.peek()` that performs it could be deleted with
     * the suite still green.
     */
    @Test fun twoBackupsInOneFileAreRefused() {
        val document = goodExport()
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document + document)
        }
        assertTrue(thrown.message!!.contains("Nothing was imported"))
        assertUntouched(target)
    }

    // ---- one row's size --------------------------------------------------

    /**
     * A row is bounded, so nothing in a file decides how much memory this process
     * uses.
     *
     * Two unbounded things met in `readRow`: the map built from the names it meets,
     * and the string `JsonReader.nextString` materialises whole. A million
     * unrecognised names is a million entries; one enormous field is as large as
     * the file. Either is an out-of-memory kill during a restore.
     *
     * Both are refused while the row is being read rather than after, which is the
     * only point at which the second one can be.
     */
    @Test fun aRowOfAMillionUnknownNamesIsRefused() {
        val document = goodExport()
        val target = fresh()
        val padding = (1..60_000).joinToString(",") { """"unknown_$it":1""" }
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document.replace(""""id":1,"raw_capture_id"""", """"id":1,$padding,"raw_capture_id""""))
        }
        assertTrue(thrown.message!!.contains("larger than"))
        assertUntouched(target)
    }

    @Test fun aSingleEnormousFieldIsRefused() {
        val document = goodExport()
        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) {
            importInto(target, document.replace(""""merchant_raw":"STARBUCKS KLCC"""", """"merchant_raw":"${"K".repeat(1_100_000)}""""))
        }
        assertTrue(thrown.message!!.contains("larger than"))
        assertUntouched(target)
    }

    /** And an ordinary row is nowhere near the bound, which is the other half. */
    @Test fun anOrdinaryBackupIsWellInsideTheRowBudget() {
        val document = goodExport()
        val target = fresh()
        assertEquals(4, importInto(target, document).rawCaptures)
    }

    // ---- cancellation ----------------------------------------------------

    /**
     * Spec 15.5 wants these jobs cancellable. Cancelling an import leaves
     * nothing, for the same reason a truncated file does: it throws out of the
     * one transaction the whole thing runs in.
     */
    @Test fun cancellingAnImportLeavesNothing() {
        val source = fresh()
        source.runInTransaction(
            Runnable {
                repeat(ImportJson.CHECK_EVERY * 3) {
                    source.rawCaptureDao().insert(bulkCapture(it))
                }
            },
        )
        val bytes = exportBytes(source)

        val target = fresh()
        assertThrows(TransferCancelledException::class.java) {
            ImportJson.read(target, ByteArrayInputStream(bytes), isCancelled = { true })
        }
        assertUntouched(target)
    }
    /**
     * `android.util.JsonReader.nextLong` falls back to `Double.parseDouble`
     * and casts, and the round trip through `double` compares equal for one
     * past `Long.MAX_VALUE` -- so no `NumberFormatException` was raised, the
     * catch that exists to say "a number in this backup is not one this build
     * can read" never fired, and `amount_sen` was stored saturated at
     * `Long.MAX_VALUE` sen. Money is `Long` sen and this is the one place a
     * file-supplied amount enters.
     */
    @Test fun anAmountBeyondLongIsRefusedRatherThanSaturated() {
        val good = goodExport()
        val bad = good.replace("\"amount_sen\":1234", "\"amount_sen\":9223372036854775808")
        assertTrue("the fixture must contain the amount being edited", bad != good)

        val target = fresh()
        val thrown = assertThrows(ImportFormatException::class.java) { importInto(target, bad) }
        assertTrue(thrown.message!!.contains("Nothing was imported"))
        assertUntouched(target)
    }

    /**
     * The cancellation check used to sit below the progress modulo, so
     * cancelling a restore of anything under [ImportJson.CHECK_EVERY] rows did
     * nothing: it ran to completion, and because the ledger was then non-empty
     * `requireEmptyLedger` refused the retry -- so undoing it needed spec
     * 11.3's delete-all-data.
     */
    @Test fun aCancelledImportOfASmallBackupLeavesNothingBehind() {
        val document = goodExport()
        val target = fresh()
        assertThrows(TransferCancelledException::class.java) {
            ImportJson.read(
                target,
                ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)),
                isCancelled = { true },
            )
        }
        assertUntouched(target)
    }

}
