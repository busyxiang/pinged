package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.Seed
import my.pinged.data.entity.Category

/**
 * A category cannot be deleted because rows still point at it. The way out is
 * [CategoryDao.mergeInto], and the counts are here so the sheet can say what
 * it is about to move.
 *
 * A named type rather than a bare [IllegalStateException], which is also what
 * `requireUncategorizedId` and `StaleCaptureException` raise -- selecting on
 * the class would offer to merge a category in response to an unrelated
 * failure.
 */
class CategoryInUseException(
    val categoryId: Long,
    val transactionCount: Int,
    val ruleCount: Int,
) : IllegalStateException(
    "Category $categoryId is in use by $transactionCount transaction(s) and " +
        "$ruleCount merchant rule(s); merge it into another category instead",
)

@Dao
interface CategoryDao {
    @Insert
    fun insertAll(categories: List<Category>)

    @Query("SELECT COUNT(*) FROM category")
    fun countAll(): Int

    /**
     * Puts [Seed.categories] in, exactly once, for the life of the database file.
     * Called from [my.pinged.data.DatabaseFactory.build], which explains why that
     * is the seeding point and not `RoomDatabase.Callback.onCreate`.
     *
     * **The guard is "is the table empty", not "is Uncategorized missing".** Spec 4
     * lets the user rename, add and delete categories, so a table holding anything
     * is theirs; re-seeding by name would resurrect what they curated away.
     * Emptiness is the one state no user action can produce, because
     * `Uncategorized` is `is_protected` and [deleteIfUnused] refuses it.
     *
     * **`@Transaction` does two jobs.** *Atomicity*: `insertAll` is fourteen
     * statements, and a process death partway leaves a table that is not empty, so
     * this guard never fills it in and `Uncategorized` may be one of the rows that
     * never landed. *Serialization*: the check and the insert are otherwise
     * check-then-act, and the loser of a race would die on `category.name`'s unique
     * index, taking `DatabaseFactory.build` -- the app's every entry point -- with
     * it. Room's write transactions are `BEGIN IMMEDIATE`, so the second opener
     * blocks here, then reads fourteen and returns.
     *
     * @return the number of rows written, so a caller can log a first run.
     */
    @Transaction
    fun seedIfEmpty(): Int {
        // The count first, then the list. As a default argument the list was
        // built at the call site on every process start -- 14 Category objects
        // allocated and thrown away each time the answer was "already seeded".
        if (countAll() != 0) return 0
        val categories = Seed.categories()
        insertAll(categories)
        return categories.size
    }

    @Query("SELECT * FROM category ORDER BY sort_order ASC")
    fun all(): List<Category>

    /**
     * **Import only, and named so it cannot be reached for by accident.**
     *
     * A restore has to put the user's categories back under their own ids, because
     * `txn.category_id` and `merchant_rule.category_id` in the same file refer to
     * them -- and the target is never empty, since `DatabaseFactory.build` seeds
     * fourteen rows before anything can read it. Inserting on top collides on the
     * primary key and on `category.name`, and re-keying would mean rewriting every
     * id in the file.
     *
     * Safe only because `ImportJson` refuses to start unless `txn` and
     * `merchant_rule` are empty. If that check were ever wrong this statement fails
     * with SQLite 787 rather than orphaning a row: the constraint is the backstop,
     * not the plan.
     *
     * Outside an import there is no legitimate caller -- spec 4's "delete only when
     * unused" is [deleteIfUnused].
     */
    @Query("DELETE FROM category")
    fun deleteAllForImport(): Int

    /**
     * The confidence gate and the categorizer both resolve Uncategorized by id
     * (spec 4), never by name, so this is the single place the name crosses into
     * SQL. A bound parameter defaulted to [Seed.UNCATEGORIZED] rather than a
     * literal, so the seed and the lookup cannot drift apart.
     *
     * **`Long?`, not `Long`.** Declared non-null, Room generates
     * `if (statement.step()) cursor.getLong(0) else 0L`, so a missing row returns
     * `0` -- not a valid `autoGenerate` id, and never a match. The foreign key does
     * still catch it, but at an insert far from the missing seed row and reported
     * as a constraint failure rather than as "the seeded category table is not
     * there". Callers that cannot proceed use [requireUncategorizedId].
     */
    @Query("SELECT id FROM category WHERE name = :name LIMIT 1")
    fun uncategorizedIdOrNull(name: String = Seed.UNCATEGORIZED): Long?

    /**
     * [uncategorizedIdOrNull], for the callers that have no answer without it:
     * the confidence gate and the categorizer both have to file an unknown
     * merchant somewhere, and spec 7.1 says that somewhere is this row. It
     * fails naming the actual problem rather than passing a number down the
     * money path.
     */
    fun requireUncategorizedId(name: String = Seed.UNCATEGORIZED): Long =
        uncategorizedIdOrNull(name) ?: throw IllegalStateException(
            "No category named '$name'. The seeded categories are missing, so " +
                "there is nowhere to file an uncategorized transaction (spec 7.1). " +
                "Seed the database before writing transactions.",
        )

    // ---- blast radius --------------------------------------------------
    // What the delete/merge sheet shows BEFORE it offers an action. Spec 4's
    // Editing paragraph allows deleting a category "only when unused", so the
    // sheet has to be able to say what "used" means for this particular
    // category, in both tables, rather than offering a button that fails.

    /** Served by `txn(category_id)`; a full scan of `txn` without it. */
    @Query("SELECT COUNT(*) FROM txn WHERE category_id = :categoryId")
    fun countTransactions(categoryId: Long): Int

    /** Served by `merchant_rule(category_id)`. */
    @Query("SELECT COUNT(*) FROM merchant_rule WHERE category_id = :categoryId")
    fun countRules(categoryId: Long): Int

    /**
     * Null when there is no such category, so callers can tell "missing" from
     * "not protected" instead of treating both as permission to proceed.
     */
    @Query("SELECT is_protected FROM category WHERE id = :categoryId")
    fun isProtected(categoryId: Long): Boolean?

    // ---- primitives ----------------------------------------------------
    // Deliberately not part of the intended API surface: they are the pieces
    // [deleteIfUnused] and [mergeInto] are built from. Calling deleteRow
    // directly is how the enforcement test proves the constraint is live, and
    // that is the only reason it is reachable.

    @Query("DELETE FROM category WHERE id = :categoryId")
    fun deleteRow(categoryId: Long): Int

    @Query("UPDATE txn SET category_id = :toId WHERE category_id = :fromId")
    fun reassignTransactions(fromId: Long, toId: Long): Int

    @Query("UPDATE merchant_rule SET category_id = :toId WHERE category_id = :fromId")
    fun reassignRules(fromId: Long, toId: Long): Int

    // ---- the two user-facing actions -----------------------------------

    /**
     * Spec 4: "delete only when unused". Throws rather than returning a boolean,
     * because the sheet says different things about the two refusals:
     * [IllegalArgumentException] for a protected or missing category (no action
     * exists), [IllegalStateException] for one merely in use (offer Merge).
     *
     * The count checks are the readable error, not the guarantee -- `ON DELETE
     * RESTRICT` on both child tables is, and it throws inside this transaction.
     */
    @Transaction
    fun deleteIfUnused(categoryId: Long) {
        requireDeletable(categoryId)
        val txns = countTransactions(categoryId)
        val rules = countRules(categoryId)
        if (txns > 0 || rules > 0) {
            throw CategoryInUseException(categoryId, txns, rules)
        }
        deleteRow(categoryId)
    }

    /**
     * Move everything that points at [fromId] onto [toId], then delete [fromId].
     * The way out of a blocked delete: without a merge path a category with one
     * transaction in it can never be removed.
     *
     * **The order of the three statements is the point of choosing RESTRICT.** The
     * DELETE comes last, so the constraint is a backstop over this very method: add
     * a third table with a `category_id`, or drop one of the reassignments, and the
     * DELETE fails loudly and `@Transaction` rolls the merge back. Delete-first
     * would orphan the rows; `SET NULL`/`CASCADE` would quietly null or destroy
     * them. A half-merged learned rule stops categorizing and a half-merged
     * transaction leaves the money uncounted, and neither says so.
     *
     * Refusals, all [IllegalArgumentException]: merging a category into itself,
     * which would delete the surviving row after moving its contents onto itself;
     * either side missing; or a **protected source** -- `Uncategorized` cannot be
     * merged away, because spec 7.1's gate resolves to it by id.
     *
     * A protected **target** is fine, and `Uncategorized` is the useful case: spec
     * 4 protects it against being renamed, re-iconed or deleted, and a merge does
     * none of those. It is also the honest destination for a transaction whose
     * category has gone away. Refusing it would leave the user choosing between a
     * category they do not want and mis-filing their own spending.
     */
    @Transaction
    fun mergeInto(fromId: Long, toId: Long) {
        require(fromId != toId) { "Cannot merge category $fromId into itself" }
        requireDeletable(fromId)
        // Existence only. is_protected does not disqualify a target, and the
        // null here is "no such row", not "not protected".
        if (isProtected(toId) == null) {
            throw IllegalArgumentException("No such category: $toId")
        }

        reassignTransactions(fromId, toId)
        reassignRules(fromId, toId)
        // Last, deliberately. See the note above.
        deleteRow(fromId)
    }

    private fun requireDeletable(categoryId: Long) {
        val protected = isProtected(categoryId)
            ?: throw IllegalArgumentException("No such category: $categoryId")
        require(!protected) { "Category $categoryId is protected and cannot be deleted" }
    }
}
