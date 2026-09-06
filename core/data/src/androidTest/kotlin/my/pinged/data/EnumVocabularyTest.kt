package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.RuleOrigin
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.Arrival
import my.pinged.parse.ExclusionReason
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Freezes the **text** every TEXT-stored enum writes to disk.
 *
 * Room stores an enum as its constant name, and none of those names appear in
 * `schemas/1.json`: the schema records `parse_status` as a `TEXT` column and
 * stops. So Room's identity hash **cannot see a renamed enum constant at all.**
 * Renaming `MATCHED` to `PARSED` in v1.1 would compile, pass schema validation,
 * require no `Migration`, and then meet `MATCHED` on disk in Room's generated
 * converter, whose `else` branch throws -- on a read of the user's own history,
 * long after the commit that caused it.
 *
 * The assertions are against literal strings, not `entries` mapped through
 * themselves: a round-trip test renames right along with the constant. The
 * count assertions make *adding* a value deliberate too.
 *
 * `Direction` and `Confidence` live in `:core:parse`, which this module cannot
 * modify, which makes freezing them here more important rather than less.
 */
@RunWith(AndroidJUnit4::class)
class EnumVocabularyTest {
    private lateinit var db: PingedDatabase
    private var uncategorized = 0L

    @Before fun setUp() {
        db = freshDatabase()
        uncategorized = db.categoryDao().requireUncategorizedId()
    }

    @After fun tearDown() = db.close()

    /** The distinct values SQLite actually holds in one column, as text. */
    private fun textIn(table: String, column: String): List<String> =
        db.openHelper.readableDatabase
            .column("SELECT DISTINCT $column FROM $table WHERE $column IS NOT NULL ORDER BY $column")
            .filterNotNull()

    /** And the declared type, so nobody quietly turns one into an INTEGER. */
    private fun declaredType(table: String, column: String): String {
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { c ->
            val nameIdx = c.getColumnIndexOrThrow("name")
            val typeIdx = c.getColumnIndexOrThrow("type")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return c.getString(typeIdx)
            }
        }
        throw AssertionError("No column $table.$column")
    }

    // ---- raw_capture.parse_status ---------------------------------------

    @Test fun parseStatusIsStoredAsTheseExactStrings() {
        // Written out rather than derived from ParseStatus, on purpose: a list
        // built from the enum renames along with it. Sorted, because the query
        // below orders by the column.
        val frozen = listOf(
            "DUPLICATE_OF", "GAVE_UP", "MATCHED", "NEW", "NO_EXTRAS",
            "REJECTED", "UNMATCHED", "UPDATE_OF",
        )

        ParseStatus.entries.forEachIndexed { i, status ->
            db.rawCaptureDao().insert(
                sampleCapture(hash = "h$i", sbnKey = "k$i", status = status),
            )
        }

        assertEquals("TEXT", declaredType("raw_capture", "parse_status"))
        assertEquals(frozen, textIn("raw_capture", "parse_status"))
        assertEquals(
            "A ParseStatus value was added or removed; decide deliberately, then " +
                "update the frozen list above",
            frozen.size,
            ParseStatus.entries.size,
        )
    }

    // ---- raw_capture.arrival --------------------------------------------

    @Test fun arrivalIsStoredAsTheseExactStrings() {
        val frozen = listOf("CATCHUP", "POSTED")
        Arrival.entries.forEachIndexed { i, arrival ->
            db.rawCaptureDao().insert(
                sampleCapture(hash = "a$i", sbnKey = "ak$i", arrival = arrival),
            )
        }
        assertEquals("TEXT", declaredType("raw_capture", "arrival"))
        assertEquals(frozen, textIn("raw_capture", "arrival"))
        assertEquals(frozen.size, Arrival.entries.size)
    }

    // ---- txn.direction, txn.confidence, txn.state, txn.exclusion_reason --

    @Test fun txnEnumsAreStoredAsTheseExactStrings() {
        var i = 0
        for (direction in Direction.entries) {
            for (confidence in Confidence.entries) {
                for (state in TxnState.entries) {
                    for (reason in ExclusionReason.entries) {
                        db.txnDao().insert(
                            sampleTxn(occurredAt = 1_000L + i, categoryId = uncategorized).copy(
                                direction = direction,
                                confidence = confidence,
                                state = state,
                                // Spec 7.1's invariant, enforced at the write
                                // path: a PENDING row states which gate fired
                                // and a non-PENDING row states nothing. The
                                // five reason strings get their own case below.
                                pendingReason = if (state == TxnState.PENDING) {
                                    PendingReason.RULE_REVIEW
                                } else {
                                    null
                                },
                                isExcluded = true,
                                exclusionReason = reason,
                            ),
                        )
                        i++
                    }
                }
            }
        }

        assertEquals("TEXT", declaredType("txn", "direction"))
        assertEquals("TEXT", declaredType("txn", "confidence"))
        assertEquals("TEXT", declaredType("txn", "state"))
        assertEquals("TEXT", declaredType("txn", "exclusion_reason"))

        assertEquals(listOf("EXPENSE", "REFUND"), textIn("txn", "direction"))
        assertEquals(listOf("HIGH", "REVIEW"), textIn("txn", "confidence"))
        assertEquals(listOf("COMMITTED", "PENDING", "REJECTED"), textIn("txn", "state"))
        assertEquals(
            listOf("ATM_WITHDRAWAL", "CARD_PAYMENT", "TRANSFER", "USER"),
            textIn("txn", "exclusion_reason"),
        )

        assertEquals(2, Direction.entries.size)
        assertEquals(2, Confidence.entries.size)
        assertEquals(3, TxnState.entries.size)
        assertEquals(4, ExclusionReason.entries.size)
    }

    // ---- txn.pending_reason ---------------------------------------------

    /**
     * Spec 7.1's five gate conditions, frozen as text for the same reason
     * every other enum here is frozen: Room's identity hash records
     * `pending_reason` as a `TEXT` column and stops there, so renaming
     * `OVER_THRESHOLD` would compile, would pass schema validation, would need
     * no `Migration`, and would then throw in Room's generated converter on a
     * read of a review item the user has been carrying since v1.
     *
     * This column is worse than the others in one respect, which is why it is
     * here rather than folded into the case above: spec 9.2 renders the reason
     * and picks the card's actions from it, so an unreadable value does not
     * merely fail a query -- it takes down the screen whose entire job is to
     * say why an item needs review.
     */
    @Test fun pendingReasonIsStoredAsTheseExactStrings() {
        // Written out, and sorted, because the query below orders by the
        // column. Not derived from PendingReason.entries: a list built from the
        // enum renames along with it.
        val frozen = listOf(
            "DUPLICATE_SUSPECT", "MERCHANT_MISSING", "OVER_THRESHOLD",
            "RULE_REVIEW", "TRANSFER_SUSPECT",
        )

        PendingReason.entries.forEachIndexed { i, reason ->
            db.txnDao().insert(
                sampleTxn(
                    occurredAt = 500_000L + i,
                    categoryId = uncategorized,
                    state = TxnState.PENDING,
                    pendingReason = reason,
                ),
            )
        }

        assertEquals("TEXT", declaredType("txn", "pending_reason"))
        assertEquals(frozen, textIn("txn", "pending_reason"))
        assertEquals(
            "A PendingReason value was added or removed. Spec 7.1's gate list is " +
                "the vocabulary, so decide whether the spec moved, then update " +
                "the frozen list above and PendingReasons.PRECEDENCE",
            frozen.size,
            PendingReason.entries.size,
        )
    }

    /**
     * `exclusion_reason` is nullable and the null means "not excluded", which
     * is a value in the vocabulary even though it is not a constant.
     */
    @Test fun anUnexcludedTransactionStoresANullExclusionReason() {
        db.txnDao().insert(sampleTxn(categoryId = uncategorized))
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM txn WHERE exclusion_reason IS NULL").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
    }

    /**
     * And the same for `pending_reason`, where the null carries more weight:
     * it is the assertion that a `COMMITTED` row does not claim a review
     * reason. The Kotlin-side invariant is in `PendingReasonTest`; this is the
     * on-disk half of it.
     */
    @Test fun aCommittedTransactionStoresANullPendingReason() {
        db.txnDao().insert(sampleTxn(categoryId = uncategorized))
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM txn WHERE pending_reason IS NULL").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
    }

    /**
     * The other direction: text that is on disk and is *not* in the enum makes
     * a read throw. This is what a rename would do to the user's history, and
     * it is asserted here so the mechanism is on the record rather than
     * inferred from Room's generated source.
     */
    @Test fun anUnknownStringOnDiskMakesTheReadThrowRatherThanReturnADefault() {
        val id = db.rawCaptureDao().insert(sampleCapture(hash = "rename"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE raw_capture SET parse_status = 'PARSED' WHERE id = ?",
            arrayOf<Any>(id),
        )
        val failure = runCatching { db.rawCaptureDao().byId(id) }
        assertEquals(
            "Room's converter accepted an unknown value; a renamed constant " +
                "would then read as something else instead of failing",
            IllegalArgumentException::class.java,
            failure.exceptionOrNull()?.javaClass,
        )
    }

    // ---- merchant_rule.match_type and .origin ----------------------------

    /**
     * These two arrived late to this file, and the reason is the finding
     * itself: they were declared `String` with their vocabulary in a line
     * comment, so "every TEXT-stored enum is frozen here" was true and did not
     * cover them.
     *
     * `match_type` is the one that mattered. It is the **leading column** of
     * merchant_rule's unique index -- the same index whose trustworthiness the
     * `COLLATE NOCASE` on `pattern` was added to establish. As free text,
     * `EXACT` and `exact` were two rows under it, so one merchant could carry
     * two rules pointing at two categories, and which one won was whichever
     * the query plan reached first.
     */
    @Test fun merchantRuleVocabulariesAreStoredAsTheseExactStrings() {
        val food = db.categoryDao().all().first { !it.isProtected }.id
        MatchType.entries.forEachIndexed { i, type ->
            db.merchantRuleDao().insert(
                MerchantRule(
                    matchType = type,
                    pattern = "PATTERN $i",
                    merchantDisplay = "Merchant $i",
                    categoryId = food,
                    origin = RuleOrigin.entries[i % RuleOrigin.entries.size],
                    priority = 100,
                ),
            )
        }
        assertEquals("TEXT", declaredType("merchant_rule", "match_type"))
        assertEquals("TEXT", declaredType("merchant_rule", "origin"))
        assertEquals(listOf("CONTAINS", "EXACT"), textIn("merchant_rule", "match_type"))

        // Written out rather than derived, for the reason the rest of this
        // file gives: a list built from the enum renames along with it.
        assertEquals(2, MatchType.entries.size)
        assertEquals(2, RuleOrigin.entries.size)
        assertEquals(listOf("BUNDLED", "EXACT"), listOf(RuleOrigin.BUNDLED.name, MatchType.EXACT.name))
        assertEquals(listOf("LEARNED", "CONTAINS"), listOf(RuleOrigin.LEARNED.name, MatchType.CONTAINS.name))
    }
}
