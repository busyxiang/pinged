package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * How a rule's [MerchantRule.pattern] is compared against `merchant_raw`.
 *
 * An enum, not a string, because `match_type` is the **leading column** of
 * merchant_rule's unique index. As free text, "EXACT" and "exact" were two rows
 * under that index, so one merchant could resolve to two categories decided by
 * whichever row the plan reached first -- the exact failure the `COLLATE
 * NOCASE` beside it was added to make impossible.
 *
 * Spec 4 types this column Enum. Declared String with the vocabulary in a line
 * comment, it sat outside EnumVocabularyTest's freeze.
 */
enum class MatchType {
    EXACT,

    /** Spec 6.2. Nothing writes this yet. */
    CONTAINS,
}

/**
 * Where a rule came from, which is how spec 4's "LEARNED always outranks
 * BUNDLED" is expressed.
 *
 * The ranking itself rides on [MerchantRule.priority]; this column is what a
 * reader consults to know why. A mis-spelled value here mis-ranks silently:
 * there is no comparison to fail, just a rule that quietly stops winning.
 */
enum class RuleOrigin {
    BUNDLED,
    LEARNED,
}

@Entity(
    tableName = "merchant_rule",
    // Same constraint and the same reasoning as txn.category_id, and the
    // failure here is quieter: an orphaned learned rule does not crash, it
    // just stops categorizing. The user taught the app "MCD KLCC is Food &
    // Drinks", deleted an unrelated-looking category, and from then on that
    // merchant lands in Uncategorized again with no message anywhere. Spec 6.1
    // describes learned rules as the thing that makes one tap permanent, so
    // silently voiding them is a correctness bug, not cosmetics.
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        // Spec 15.1 lists merchant_rule(pattern) as the index behind
        // learned-rule lookup, which runs per capture in stage two. The lookup
        // is `pattern = ? ORDER BY priority DESC` -- spec 4 says "LEARNED
        // always outranks BUNDLED" and priority is how that is expressed -- so
        // the bare pattern index left a `USE TEMP B-TREE FOR ORDER BY` on it,
        // measured on emulator-5554. With priority in the index SQLite walks
        // it backwards instead.
        Index(value = ["pattern", "priority"]),
        // The foreign-key child index; also backs CategoryDao.countRules for
        // the delete sheet.
        Index("category_id"),
        // Two equal-priority LEARNED rows for one pattern made the resolved category
        // depend on which row the plan reached first, so one merchant could file under
        // two categories on two days with nothing able to explain it. `match_type` and
        // `scoped_package` are in the key because an EXACT and a CONTAINS rule for the
        // same string are different rules (spec 6.2), and a scoped rule is different
        // from a global one.
        //
        // **This index only started refusing the case it was built for once
        // `scoped_package` became NOT NULL.** SQLite treats NULLs in a unique index as
        // distinct, so while the column was nullable two *unscoped* LEARNED EXACT rules
        // for one pattern -- precisely what spec 6.1's writer produces -- both
        // inserted. Room's `@Index` takes only `value`, `name`, `unique` and `orders`,
        // so a `COALESCE(scoped_package, '')` expression index is not expressible; the
        // fix had to be at the column, and pre-v1 it is free.
        //
        // `pattern` carries `COLLATE NOCASE`, so this index is case-insensitive on it
        // and a case variant is a duplicate rather than a third rule.
        Index(value = ["match_type", "pattern", "scoped_package"], unique = true),
    ],
)
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "match_type") val matchType: MatchType,
    /**
     * The string matched against `merchant_raw`. **`COLLATE NOCASE`, because spec 4
     * declares this column case-insensitively matched and the column was not.**
     *
     * SQLite's default is BINARY, so `MCD KLCC` and `mcd klcc` were two distinct
     * rows to the unique index -- and the case-insensitive lookup then matched
     * *both*, at equal priority. Measured on emulator-5554 before the collation:
     * both inserted, and the lookup returned `[MCD KLCC => 1, mcd klcc => 7]`.
     *
     * **Spec 6.1's writer is not the exposure; a pack is.** Learned patterns are
     * normalized to uppercase before being written, so the learning path could not
     * produce a case variant of its own rules -- but the bundled dictionary is
     * authored mixed-case and an imported pack (spec 5.9) is authored by a
     * stranger. Normalization being the only thing holding the constraint up is the
     * "correct by accident" shape this schema keeps removing.
     *
     * NOCASE folds ASCII `A-Z` only, which is the whole of the vocabulary spec 6.1
     * produces. A non-ASCII pattern from a pack still has case-sensitive halves,
     * and no SQLite collation available here fixes that.
     *
     * The `(pattern, priority)` index inherits the collation, so the lookup still
     * plans as an index search with no sort -- asserted in `QueryPlanTest`.
     */
    @ColumnInfo(name = "pattern", collate = ColumnInfo.NOCASE)
    val pattern: String,
    @ColumnInfo(name = "merchant_display") val merchantDisplay: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val origin: RuleOrigin,
    val priority: Int,
    @ColumnInfo(name = "hit_count") val hitCount: Int = 0,
    /**
     * The package this rule is restricted to, or `""` for "not scoped". **NOT NULL
     * with an empty-string sentinel, and the sentinel is the point.**
     *
     * "Unscoped" has to be one value rather than an absence, because the unique
     * index above is what stops one merchant resolving to two categories and SQLite
     * treats every NULL in a unique index as distinct. Nullable, the index refused
     * two rules scoped to the same package and accepted two unscoped ones -- and
     * unscoped is what spec 6.1's writer produces. `""` collides with itself.
     *
     * No Android package name is the empty string, so the sentinel cannot collide
     * with a real scope, and comparisons stay a plain `=` instead of the
     * `IS NULL OR = ?` that nullable scoping forces on every lookup.
     *
     * A DDL `DEFAULT ''` as well, so a writer going in through raw SQL and omitting
     * the column lands on "unscoped" rather than a NOT NULL failure. Unlike
     * `txn.category_id`, whose default would have to be a seeded row's id, this one
     * is a constant that SQLite can hold.
     */
    @ColumnInfo(name = "scoped_package", defaultValue = "''")
    val scopedPackage: String = UNSCOPED,
) {
    companion object {
        /**
         * The "not scoped to a package" sentinel, named once so that the
         * learned-rule writer, the lookup and the tests cannot disagree about
         * it. A literal `""` at three call sites is how one of them eventually
         * becomes a null again.
         */
        const val UNSCOPED = ""
    }
}
