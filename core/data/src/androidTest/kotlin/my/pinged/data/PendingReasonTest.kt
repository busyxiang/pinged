package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.ExclusionReason
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.PendingReasons
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `txn.pending_reason`: the column that lets spec 7.1's closing claim be true.
 *
 * Spec 7.1 lists five conditions any one of which sends a transaction to the
 * review inbox, and ends "the app can always state exactly why an item needs
 * review". It could not: `state = PENDING` plus `confidence = REVIEW` is the
 * same two values for all five, so a duplicate suspect, a wallet reload, an
 * RM800 dinner and an unparsed merchant were indistinguishable on disk.
 *
 * **Why these tests rather than a constraint.** The column is nullable, because
 * a `COMMITTED` row has no reason, and Room can declare neither a CHECK
 * constraint nor a trigger. The guard is
 * [my.pinged.data.entity.requireStorable], called from every DAO insert, and
 * these tests are the only thing that makes it real -- both directions, both
 * write paths.
 *
 * **`is_excluded`/`exclusion_reason` is covered in this same class**, because
 * it is the same defect in the same table and leaving one pair guarded and the
 * other not teaches the next writer that the unguarded shape is fine. That pair
 * fails the more expensive way round: `exclusion_reason = TRANSFER` with
 * `is_excluded = 0` counts spec 7.3's money-that-moved as spending.
 */
@RunWith(AndroidJUnit4::class)
class PendingReasonTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() = db.close()

    private fun txn(
        state: TxnState,
        reason: PendingReason?,
        rawCaptureId: Long? = null,
    ) = sampleTxn(categoryId = uncategorized, state = state, rawCaptureId = rawCaptureId)
        .copy(pendingReason = reason)

    /** The `pending_reason` text SQLite actually holds, in insertion order. */
    private fun reasonsOnDisk(): List<String?> =
        db.openHelper.readableDatabase.column("SELECT pending_reason FROM txn ORDER BY id ASC")

    // ---- direction one: PENDING implies a reason -----------------------

    /**
     * The row spec 9.2 cannot render. A card whose whole job is to state "the
     * specific reason it needs review" and offer "actions appropriate to the
     * reason" has, for this row, neither -- and no way to work either out,
     * because the rule that matched and the duplicate detector's verdict are
     * both gone by the time the inbox opens.
     */
    @Test fun aPendingTransactionWithoutAReasonIsRefused() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(txn(TxnState.PENDING, null))
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "pending with no reason: ${thrown.message}")
        assertTrue(
            "The failure does not name the column: ${thrown.message}",
            thrown.message!!.contains("pending_reason"),
        )
        assertEquals("A row was written anyway", 0, db.txnDao().countAll())
    }

    // ---- direction two: a reason implies PENDING -----------------------

    /**
     * The other direction. A `COMMITTED` row carrying `DUPLICATE_SUSPECT` claims
     * the money is counted *and* under review: the inbox filters on `state` so
     * nobody will see it, and any later code reading the reason -- spec 7.2's
     * merge, spec 7.3's "always exclude" -- reads a verdict already resolved.
     */
    @Test fun aCommittedTransactionCarryingAReasonIsRefused() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(txn(TxnState.COMMITTED, PendingReason.DUPLICATE_SUSPECT))
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "committed with a reason: ${thrown.message}")
        assertEquals(0, db.txnDao().countAll())
    }

    /** And `REJECTED`, which is the third state and not a special case. */
    @Test fun aRejectedTransactionCarryingAReasonIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(txn(TxnState.REJECTED, PendingReason.OVER_THRESHOLD))
        }
        assertEquals(0, db.txnDao().countAll())
    }

    // ---- the legal rows -------------------------------------------------

    @Test fun everyGateConditionRoundTripsThroughTheColumn() {
        PendingReason.entries.forEachIndexed { i, reason ->
            db.txnDao().insert(
                sampleTxn(
                    occurredAt = 1_000L + i,
                    categoryId = uncategorized,
                    state = TxnState.PENDING,
                    pendingReason = reason,
                ),
            )
        }
        assertEquals(
            PendingReason.entries.toList(),
            db.txnDao().pageFrom(0, 10).map { it.pendingReason },
        )
        assertEquals(
            PendingReason.entries.map { it.name },
            reasonsOnDisk(),
        )
    }

    @Test fun aCommittedTransactionIsAcceptedWithNoReasonAtAll() {
        db.txnDao().insert(sampleTxn(categoryId = uncategorized))
        assertNull(db.txnDao().pageFrom(0, 1).single().pendingReason)
        assertEquals(listOf<String?>(null), reasonsOnDisk())
    }

    // ---- the other write path, spec 4's stage two ----------------------

    /**
     * `RawCaptureDao.commitCapture` is how stage two writes in production, so
     * a guard that lived only on `TxnDao` would be a guard the app does not go
     * through. The throw has to land inside the `@Transaction` as well: a
     * `PENDING` row with no reason must not be able to leave the capture
     * marked `MATCHED`, because a capture off the queue with no transaction is
     * spec 4's audit trail saying money was handled when it was not. Same
     * shape as `CommitAtomicityTest.aFailedInsertRollsBackTheOutcome`.
     */
    @Test fun commitCaptureRefusesAReasonlessPendingRowAndKeepsTheCaptureOnTheQueue() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "reasonless"))

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.rawCaptureDao().commitCapture(
                captureId = capture,
                txn = txn(TxnState.PENDING, null, rawCaptureId = capture),
                status = ParseStatus.MATCHED,
                ruleId = "tng.reload.v1",
            )
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "commitCapture refusal: ${thrown.message}")

        assertEquals(0, db.txnDao().countAll())
        assertEquals(
            "The capture left the queue although no transaction was written",
            ParseStatus.NEW,
            db.rawCaptureDao().byId(capture).parseStatus,
        )
        assertEquals(1, db.rawCaptureDao().claimNext(10).size)
    }

    @Test fun commitCaptureAcceptsAPendingRowThatStatesItsReason() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "reload"))
        db.rawCaptureDao().commitCapture(
            captureId = capture,
            txn = txn(TxnState.PENDING, PendingReason.TRANSFER_SUSPECT, rawCaptureId = capture),
            status = ParseStatus.MATCHED,
            ruleId = "tng.reload.v1",
        )
        assertEquals(
            PendingReason.TRANSFER_SUSPECT,
            db.txnDao().pageFrom(0, 1).single().pendingReason,
        )
        assertEquals(ParseStatus.MATCHED, db.rawCaptureDao().byId(capture).parseStatus)
    }

    // ---- how far the guard reaches, stated rather than implied ---------

    /**
     * **The limit of a Kotlin-side invariant, on the record.**
     *
     * There is no CHECK constraint and no trigger, so raw SQL goes straight
     * around the guard and puts a contradictory row on disk. That is the
     * honest cost of the design: the enforcement is at the write path because
     * that is the only place it can be before v1, and these tests are the
     * mechanism, not the column.
     *
     * It is asserted rather than left implicit for the same reason
     * `EnumVocabularyTest` asserts that an unknown string on disk throws: the
     * guarantee should be readable from the tests, including the part of it
     * that does not hold. Anyone tempted to describe `pending_reason` as
     * "constrained" has to delete this test first.
     */
    @Test fun rawSqlCanStillWriteAContradictoryRowWhichIsWhyTheGuardIsATest() {
        val id = db.txnDao().insert(
            sampleTxn(
                categoryId = uncategorized,
                state = TxnState.PENDING,
                pendingReason = PendingReason.MERCHANT_MISSING,
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE txn SET pending_reason = NULL WHERE id = ?",
            arrayOf<Any>(id),
        )
        assertEquals(listOf<String?>(null), reasonsOnDisk())
        assertEquals(TxnState.PENDING, db.txnDao().pageFrom(0, 1).single().state)
        assertNull(db.txnDao().pageFrom(0, 1).single().pendingReason)
    }

    /** The unchecked DAO primitive is the same story, and exists for this. */
    @Test fun theUncheckedPrimitiveBypassesTheGuard() {
        db.txnDao().insertRow(txn(TxnState.PENDING, null))
        assertEquals(1, db.txnDao().countAll())
        assertNull(db.txnDao().pageFrom(0, 1).single().pendingReason)
    }

    // ---- the exclusion pair, spec 7.3 ---------------------------------

    /**
     * Excluded implies a reason. Spec 7.3 keeps excluded rows rather than
     * deleting them so the user can see that money moved without being spent,
     * and spec 9.1 renders them greyed with an exclusion badge -- a row that
     * cannot name its reason cannot be shown that way. `ExclusionReason.USER`
     * covers "the user said so", so no exclusion is left without an answer.
     */
    @Test fun anExcludedTransactionWithoutAReasonIsRefused() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(
                sampleTxn(categoryId = uncategorized).copy(isExcluded = true),
            )
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "excluded with no reason: ${thrown.message}")
        assertTrue(
            "The failure does not name the column: ${thrown.message}",
            thrown.message!!.contains("exclusion_reason"),
        )
        assertEquals(0, db.txnDao().countAll())
    }

    /**
     * **The direction that costs money.** The reason says spec 7.3 classified
     * this as a transfer, reload or card payment; the flag says count it as
     * spending anyway. Every total and every chart filters on
     * `is_excluded = 0` (see `QueryPlanTest`'s month forms), so the row is
     * counted as spending while carrying a column that says it is not, and
     * nothing anywhere reconciles the two.
     */
    @Test fun anUnexcludedTransactionCarryingAReasonIsRefused() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            db.txnDao().insert(
                sampleTxn(categoryId = uncategorized)
                    .copy(isExcluded = false, exclusionReason = ExclusionReason.TRANSFER),
            )
        }
        android.util.Log.i(OpenTest.REPORT_TAG, "unexcluded with a reason: ${thrown.message}")
        assertEquals(0, db.txnDao().countAll())
    }

    @Test fun everyExclusionReasonRoundTripsOnAnExcludedRow() {
        ExclusionReason.entries.forEachIndexed { i, reason ->
            db.txnDao().insert(
                sampleTxn(occurredAt = 2_000L + i, categoryId = uncategorized)
                    .copy(isExcluded = true, exclusionReason = reason),
            )
        }
        assertEquals(
            ExclusionReason.entries.toList(),
            db.txnDao().pageFrom(0, 10).map { it.exclusionReason },
        )
    }

    /** And the ordinary row: not excluded, no reason, accepted. */
    @Test fun anOrdinaryTransactionIsNeitherExcludedNorReasoned() {
        db.txnDao().insert(sampleTxn(categoryId = uncategorized))
        val row = db.txnDao().pageFrom(0, 1).single()
        assertEquals(false, row.isExcluded)
        assertNull(row.exclusionReason)
    }

    /**
     * The exclusion pair goes through `commitCapture` too, and rolls the
     * capture's outcome back with it, for the same reason the `pending_reason`
     * case does: a capture off the queue with no transaction is spec 4's audit
     * trail claiming money was handled when it was not.
     */
    @Test fun commitCaptureRefusesAReasonlessExclusionAndKeepsTheCaptureOnTheQueue() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "excl"))
        assertThrows(IllegalArgumentException::class.java) {
            db.rawCaptureDao().commitCapture(
                captureId = capture,
                txn = sampleTxn(rawCaptureId = capture, categoryId = uncategorized)
                    .copy(isExcluded = true),
                status = ParseStatus.MATCHED,
            )
        }
        assertEquals(0, db.txnDao().countAll())
        assertEquals(ParseStatus.NEW, db.rawCaptureDao().byId(capture).parseStatus)
        assertEquals(1, db.rawCaptureDao().claimNext(10).size)
    }

    /**
     * The same honesty as
     * [rawSqlCanStillWriteAContradictoryRowWhichIsWhyTheGuardIsATest]: the
     * limit is identical for this pair, because it is the same absence of a
     * CHECK constraint and not a property of either column.
     */
    @Test fun rawSqlCanStillContradictTheExclusionPairToo() {
        val id = db.txnDao().insert(
            sampleTxn(categoryId = uncategorized)
                .copy(isExcluded = true, exclusionReason = ExclusionReason.TRANSFER),
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE txn SET is_excluded = 0 WHERE id = ?",
            arrayOf<Any>(id),
        )
        val row = db.txnDao().pageFrom(0, 1).single()
        assertEquals(false, row.isExcluded)
        assertEquals(ExclusionReason.TRANSFER, row.exclusionReason)
    }

    // ---- precedence, spec 7.1's "any of the following" -----------------

    /**
     * Spec 7.1's gate is `if any of the following hold`, so several hold at
     * once routinely. Exactly one value is stored -- a set in a TEXT column is
     * not queryable and spec 9.2 asks for one reason and one action set per
     * card -- and [PendingReasons.mostSpecific] is the choice. The ordering
     * argument is on [PendingReason]: prefer the reason that cannot be
     * recovered from the stored row later, with resolution order breaking ties.
     */
    @Test fun thePrecedenceOrderIsFrozen() {
        assertEquals(
            listOf(
                PendingReason.DUPLICATE_SUSPECT,
                PendingReason.TRANSFER_SUSPECT,
                PendingReason.RULE_REVIEW,
                PendingReason.MERCHANT_MISSING,
                PendingReason.OVER_THRESHOLD,
            ),
            PendingReasons.PRECEDENCE,
        )
    }

    /**
     * A new gate condition has to be placed in the order deliberately. Without
     * this, a sixth value would silently sort last and quietly become the
     * reason nothing ever displays.
     */
    @Test fun everyReasonHasAPlaceInThePrecedenceOrder() {
        assertEquals(
            "A PendingReason is missing from, or duplicated in, " +
                "PendingReasons.PRECEDENCE",
            PendingReason.entries.toSet(),
            PendingReasons.PRECEDENCE.toSet(),
        )
        assertEquals(PendingReason.entries.size, PendingReasons.PRECEDENCE.size)
    }

    @Test fun mostSpecificPicksTheHighestPrecedenceReasonThatFired() {
        // An RM800 wallet reload matched by a rule that also declares
        // confidence: REVIEW. All three fire; spec 7.3's prompt is the useful
        // one, because it is the only one with an answer that changes whether
        // the money counts.
        assertEquals(
            PendingReason.TRANSFER_SUSPECT,
            PendingReasons.mostSpecific(
                listOf(
                    PendingReason.OVER_THRESHOLD,
                    PendingReason.RULE_REVIEW,
                    PendingReason.TRANSFER_SUSPECT,
                ),
            ),
        )
        // A duplicate suspect whose merchant also never parsed. Merge may make
        // the row cease to exist, so asking who the merchant is comes second.
        assertEquals(
            PendingReason.DUPLICATE_SUSPECT,
            PendingReasons.mostSpecific(
                listOf(PendingReason.MERCHANT_MISSING, PendingReason.DUPLICATE_SUSPECT),
            ),
        )
        // A large payment with no merchant: the missing merchant is the defect,
        // the threshold is just the amount.
        assertEquals(
            PendingReason.MERCHANT_MISSING,
            PendingReasons.mostSpecific(
                listOf(PendingReason.OVER_THRESHOLD, PendingReason.MERCHANT_MISSING),
            ),
        )
        // Order of the argument does not matter; precedence does.
        assertEquals(
            PendingReason.RULE_REVIEW,
            PendingReasons.mostSpecific(
                listOf(PendingReason.RULE_REVIEW, PendingReason.OVER_THRESHOLD),
            ),
        )
    }

    /** No gate fired, which is the `COMMITTED` case and not an error. */
    @Test fun mostSpecificIsNullWhenNothingFired() {
        assertNull(PendingReasons.mostSpecific(emptyList()))
    }

    /** Each condition alone resolves to itself. */
    @Test fun mostSpecificIsIdentityOnASingleReason() {
        for (reason in PendingReason.entries) {
            assertEquals(reason, PendingReasons.mostSpecific(listOf(reason)))
        }
    }

    /**
     * The third invariant `requireStorable` now carries, and the one that was
     * previously held for this module by someone else: spec 4 says amount_sen
     * is always positive, and that was true only because core:parse's
     * Amount.toSen rejects a non-positive value. The import path reads the
     * column unchecked and spec 9.4's manual entry has not been written, so
     * nothing on this side of the boundary was asserting it.
     */
    @Test fun aNonPositiveAmountIsRefusedAtTheWritePath() {
        listOf(0L, -1L).forEach { amount ->
            val failure = runCatching {
                db.txnDao().insert(
                    sampleTxn(occurredAt = 900_000L, categoryId = uncategorized)
                        .copy(amountSen = amount),
                )
            }.exceptionOrNull()
            assertEquals(
                "amount_sen=$amount was accepted",
                IllegalArgumentException::class.java,
                failure?.javaClass,
            )
        }
    }
}
