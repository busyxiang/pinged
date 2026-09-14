package my.pinged.data.entity

import my.pinged.data.LocalDate
import androidx.room.ColumnInfo
import my.pinged.data.LocalDates
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason

enum class TxnState { COMMITTED, PENDING, REJECTED }

/**
 * Which of spec 7.1's five gate conditions sent this transaction to the review
 * inbox. Spec 9.2 requires each card to show "the specific reason it needs
 * review" and actions appropriate to it, which `state` and `confidence` cannot
 * distinguish -- they are the same five ways.
 *
 * **Declared in precedence order**, which is the answer to "more than one
 * condition fired": spec 7.1's gate is `if any of the following hold`, so
 * several routinely hold at once, and spec 9.2 asks for one reason and one
 * action set per card. Exactly one value is stored, chosen by
 * [PendingReasons.mostSpecific]. The rule is **prefer the reason that cannot be
 * recovered from the stored row later**:
 *
 * 1. [DUPLICATE_SUSPECT], whose resolution can make the row cease to exist
 *    (spec 7.2 offers Merge or Keep both) and whose verdict is the least
 *    recoverable -- layer 2 is a txn-to-txn judgement over a ten-minute window
 *    and nothing stores the pair.
 * 2. [TRANSFER_SUSPECT], the only reason with its own prompt and third action
 *    (spec 7.3). Its answer decides whether the money counts at all.
 * 3. [RULE_REVIEW], same source but weaker: `confidence: REVIEW` says only "I
 *    am not sure".
 * 4. [MERCHANT_MISSING], exactly recomputable from the row, so it loses nothing
 *    by being displaced -- but still outranks the threshold, because a row with
 *    no merchant cannot be asked the transfer or category question sensibly.
 * 5. [OVER_THRESHOLD], a function of `amount_sen` and a setting, and the only
 *    one of the five that says nothing is wrong with the parse.
 *
 * Stored as TEXT. `EnumVocabularyTest` freezes the five strings because Room's
 * identity hash cannot see a renamed constant: a rename would compile, pass
 * schema validation, then throw on a read of the user's own history.
 *
 * Nullable, because a `COMMITTED` or `REJECTED` row has no reason -- but not a
 * licence to hold a contradictory pair. Room cannot declare a CHECK constraint,
 * so [requireStorable] enforces both directions at the write path.
 *
 * No index: spec 9.2's inbox filters on `state`, never on this column.
 */
enum class PendingReason {
    /** Spec 7.2 layer 2, and layer 1 rule 1 beyond its ten-minute window. */
    DUPLICATE_SUSPECT,

    /** The matched rule declares `kind: TRANSFER_SUSPECT` (spec 7.3). */
    TRANSFER_SUSPECT,

    /** The matched rule declares `confidence: REVIEW`. */
    RULE_REVIEW,

    /** An amount was extracted but the merchant is missing or empty. */
    MERCHANT_MISSING,

    /** `amount_sen` exceeds the user's threshold, default RM500. */
    OVER_THRESHOLD,
}

object PendingReasons {
    /**
     * Written out rather than read off `entries`: declaration order would
     * change silently the first time someone tidies the enum, re-filing every
     * future review item. Spelled as a list, adding a value fails
     * `PendingReasonTest.everyReasonHasAPlaceInThePrecedenceOrder` until
     * somebody decides where it belongs. The order's reasoning is on
     * [PendingReason].
     */
    val PRECEDENCE: List<PendingReason> = listOf(
        PendingReason.DUPLICATE_SUSPECT,
        PendingReason.TRANSFER_SUSPECT,
        PendingReason.RULE_REVIEW,
        PendingReason.MERCHANT_MISSING,
        PendingReason.OVER_THRESHOLD,
    )

    /**
     * The single reason to store when [fired] holds every gate condition that
     * matched. Null when none did, which is the `COMMITTED` case.
     */
    fun mostSpecific(fired: Collection<PendingReason>): PendingReason? =
        PRECEDENCE.firstOrNull { it in fired }
}

/**
 * Both directions of **both** of this table's flag/reason pairs, checked at the
 * write path because nothing below it can be: Room has no CHECK-constraint
 * support and this schema carries no triggers.
 *
 * The expensive direction is `is_excluded = 0` with `exclusion_reason` left on
 * the row: spec 7.3's money that moved without being spent then counts as
 * spending in every total and chart.
 *
 * One function called from every insert, so a new write path inherits it by
 * using the DAO. `PendingReasonTest` records how far that reaches: raw SQL goes
 * around it, and no Kotlin-side guard can change that before v1.
 */
fun Txn.requireStorable() {
    // Spec 4: "amount_sen | Long | always positive". core:parse's Amount.toSen
    // returns null for a non-positive value, but the import path takes
    // amount_sen unchecked. A real CHECK (amount_sen > 0) would move the
    // identity hash, so this is the pre-v1 answer, as it is for the two reason
    // pairs below.
    require(amountSen > 0) {
        "amount_sen must be positive, was $amountSen. Spec 4 declares it so, " +
            "and direction is what distinguishes money out from money in; a " +
            "signed amount would mean the sign and the direction can disagree."
    }

    if (state == TxnState.PENDING) {
        require(pendingReason != null) {
            "A PENDING transaction must record which of spec 7.1's gate " +
                "conditions fired; pending_reason was null. The review inbox " +
                "(spec 9.2) has to state the specific reason and offer actions " +
                "appropriate to it, and it cannot invent one after the fact."
        }
    } else {
        require(pendingReason == null) {
            "state=$state carries pending_reason=$pendingReason. Only a PENDING " +
                "transaction has a review reason; a reason left on a committed " +
                "or rejected row is a claim about a review that will never happen."
        }
    }

    if (isExcluded) {
        require(exclusionReason != null) {
            "An excluded transaction must say why it is excluded; " +
                "exclusion_reason was null. Spec 7.3 keeps excluded rows " +
                "visible and greyed rather than deleting them precisely so the " +
                "user can see that money moved without being spent, and a row " +
                "that cannot name its reason cannot be shown that way. " +
                "ExclusionReason.USER exists for 'the user said so', so there " +
                "is no exclusion with no reason to give."
        }
    } else {
        require(exclusionReason == null) {
            "is_excluded=false carries exclusion_reason=$exclusionReason. This " +
                "is the direction that costs money: the reason says spec 7.3 " +
                "classified this as a transfer, reload or card payment, and the " +
                "flag says count it as spending anyway -- so it lands in every " +
                "total and every chart while claiming not to be spending."
        }
    }
}

@Entity(
    tableName = "txn",
    // Spec 4 allows deleting a category "only when unused", which without this
    // constraint is a Kotlin-side promise: an orphaned txn either vanishes from
    // every total (INNER JOIN) or crashes at render (LEFT JOIN into a non-null
    // Long). RESTRICT makes the worst outcome a visible error.
    //
    // Not SET DEFAULT, which cannot express "the Uncategorized row's id" -- that
    // is data, not a constant -- and not CASCADE, which would delete the
    // transactions along with the category.
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // Serves TxnDao.recent and nothing else, and that query is tests-only.
        // Kept because the schema is frozen and dropping an index moves Room's
        // identity hash, not because a screen needs it; TxnDao.recent records
        // the plan without it.
        Index("occurred_at"),
        // The month list and the day/month aggregates (spec 15.3). This
        // replaces the bare `Index("local_date")` spec 15.1 names: local_date
        // leads here, so SQLite uses it for anything the narrow one served --
        // QueryPlanTest asserts all three plans name this index. Keeping both
        // would maintain a second B-tree per insert and give the planner two
        // candidates differing only in width.
        Index(value = ["local_date", "occurred_at"]),
        Index(value = ["amount_sen", "occurred_at"]),
        Index(value = ["state", "occurred_at"]),
        Index("raw_capture_id", unique = true),
        // Room warns on an unindexed foreign-key child column, because every
        // parent delete then scans the child table -- and the category sheet's
        // "used by N transactions" count is a per-open query that would
        // otherwise scan txn.
        Index("category_id"),
        // Section 8's "Top merchants" groups on this and orders by the sum, so
        // the index turns a full scan plus a sort into an ordered walk.
        Index("merchant_key"),
    ],
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_capture_id") val rawCaptureId: Long?,
    @ColumnInfo(name = "amount_sen") val amountSen: Long,
    val currency: String = "MYR",
    val direction: Direction,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    /**
     * yyyymmdd in the device zone, computed once. Spec 15.7.
     *
     * Defaulted from [occurredAt] rather than left to the caller, because a
     * wrong value here is wrong in three places at once -- see [LocalDate].
     *
     * Still overridable, because the import path must preserve the `local_date`
     * the exporting device computed in *its* zone.
     */
    @ColumnInfo(name = "local_date") val localDate: LocalDate = LocalDates.of(occurredAt),
    @ColumnInfo(name = "merchant_raw") val merchantRaw: String?,
    @ColumnInfo(name = "merchant_display") val merchantDisplay: String?,
    /**
     * The merchant's identity, as opposed to its name.
     *
     * Neither existing column can group. `merchant_raw` is preserved untouched
     * by spec 5.4, so `GRAB*TRIP-A1` and `GRAB*TRIP-B2` are two merchants;
     * `merchant_display` is user-editable, so one edit splits a merchant out of
     * its own ranking. Section 8's "Top merchants" needs a SQL `GROUP BY`
     * (spec 15.3), not a Kotlin walk.
     *
     * `Merchant.clean` supplies it -- uppercased, acquirer prefix and terminal
     * code stripped, whitespace collapsed -- which is deliberately *not* spec
     * 6.1's learned-rule key, where `TNG*99SPEEDMART` keeps its `TNG`. The two
     * coincide wherever 6.1 operates, since chains and acquirer strings never
     * reach the learning path.
     *
     * Stored rather than derived: it depends on the pack's normalisation lists,
     * so re-deriving would have to reproduce the pack of each row's era. Spec
     * 5.5's re-parse refreshes it deliberately.
     *
     * **Null exactly when [merchantRaw] is.** A raw string that is entirely
     * payment rail (`TNG*`, `DUITNOWQR-`, a bare terminal code) cleans to the
     * empty string, so `ParsePass` refuses such a capture a merchant at all --
     * otherwise the row would commit with no `MERCHANT_MISSING` and fall into
     * section 8's NULL bucket.
     */
    @ColumnInfo(name = "merchant_key") val merchantKey: String?,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String?,
    @ColumnInfo(name = "source_label") val sourceLabel: String?,
    val confidence: Confidence,
    val state: TxnState,
    /**
     * Which of spec 7.1's five gate conditions sent this row to the review
     * inbox. Null exactly when [state] is not [TxnState.PENDING]; see
     * [PendingReason] for the vocabulary and the precedence order, and
     * [requireStorable] for the invariant.
     */
    @ColumnInfo(name = "pending_reason") val pendingReason: PendingReason? = null,
    @ColumnInfo(name = "is_excluded") val isExcluded: Boolean = false,
    /**
     * Why this row is kept but not counted (spec 7.3). Null exactly when
     * [isExcluded] is false, enforced by [requireStorable] in both directions.
     */
    @ColumnInfo(name = "exclusion_reason") val exclusionReason: ExclusionReason? = null,
    val note: String? = null,
    @ColumnInfo(name = "user_edited") val userEdited: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
