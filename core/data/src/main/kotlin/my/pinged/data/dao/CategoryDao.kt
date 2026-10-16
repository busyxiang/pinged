package my.pinged.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.LocalDate
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

/**
 * A name another category already holds, compared ignoring case: two
 * categories called "Makan" and "makan" are one category to anyone reading
 * them. `category.name`'s unique index is the backstop and is case-sensitive,
 * so this is the check, and it names the clash where SQLite would say 2067.
 */
class CategoryNameTakenException(val name: String) :
    IllegalArgumentException("There is already a category called '$name'")

/** One category and what points at it: the Categories list's row (#155). */
data class CategoryUse(
    @Embedded val category: Category,
    @ColumnInfo(name = "transactions") val transactions: Int,
    @ColumnInfo(name = "rules") val rules: Int,
)

@Dao
interface CategoryDao {
    @Insert
    fun insertAll(categories: List<Category>)

    @Insert
    fun insert(category: Category): Long

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
     * **`@Transaction` does two jobs.** *Atomicity*: `insertAll` is sixteen
     * statements, and a process death partway leaves a table that is not empty, so
     * this guard never fills it in and `Uncategorized` may be one of the rows that
     * never landed. *Serialization*: the check and the insert are otherwise
     * check-then-act, and the loser of a race would die on `category.name`'s unique
     * index, taking `DatabaseFactory.build` -- the app's every entry point -- with
     * it. Room's write transactions are `BEGIN IMMEDIATE`, so the second opener
     * blocks here, then reads sixteen and returns.
     *
     * @return the number of rows written, so a caller can log a first run.
     */
    @Transaction
    fun seedIfEmpty(): Int {
        // The count first, then the list. As a default argument the list was
        // built at the call site on every process start -- 16 Category objects
        // allocated and thrown away each time the answer was "already seeded".
        if (countAll() != 0) return 0
        val categories = Seed.categories()
        insertAll(categories)
        return categories.size
    }

    /**
     * Gives a table seeded before [Seed.TOPPED_UP] existed the categories in
     * it, then moves Uncategorized to [Seed.UNCATEGORIZED_SORT_ORDER].
     * Called from [my.pinged.data.DatabaseFactory.build] after [seedIfEmpty],
     * and by `ImportJson` at the end of a restore, which replaces the table
     * with the backup's.
     *
     * **Runs only while Uncategorized sits below
     * [Seed.UNCATEGORIZED_SORT_ORDER]**, which is true of no table this build
     * seeded or topped up. So it runs once per database and once per old
     * backup, and a category deleted after it -- a new one included -- stays
     * deleted. A name already present is the user's and is left as it is;
     * inserting it would die on `category.name`'s unique index. No
     * Uncategorized at all is not this method's to repair: `ImportJson`
     * refuses that file, and [requireUncategorizedId] names it.
     *
     * Row data, not schema: no migration and no version bump. `@Transaction`
     * for the same two reasons as [seedIfEmpty].
     *
     * @return the number of rows written.
     */
    @Transaction
    fun topUp(): Int {
        val order = sortOrderOf(Seed.UNCATEGORIZED) ?: return 0
        if (order >= Seed.UNCATEGORIZED_SORT_ORDER) return 0
        // By `seed_name`, so a seeded category the user renamed is present.
        // The name is checked as well, because a category of the user's may
        // hold it, and the unique index would refuse the insert.
        val rows = all()
        val seeded = rows.mapNotNullTo(HashSet()) { it.seedName }
        val names = rows.mapTo(HashSet()) { it.name }
        val missing = Seed.categories().filter {
            it.seedName in Seed.TOPPED_UP && it.seedName !in seeded && it.name !in names
        }
        insertAll(missing)
        moveUncategorized(Seed.UNCATEGORIZED_SORT_ORDER)
        return missing.size
    }

    @Query("SELECT sort_order FROM category WHERE name = :name")
    fun sortOrderOf(name: String): Int?

    /** A targeted UPDATE of the one column, never a whole-row write. */
    @Query("UPDATE category SET sort_order = :sortOrder WHERE name = :name")
    fun moveUncategorized(sortOrder: Int, name: String = Seed.UNCATEGORIZED): Int

    @Query("SELECT * FROM category ORDER BY sort_order ASC")
    fun all(): List<Category>

    @Query("SELECT * FROM category WHERE seed_name IS NOT NULL")
    fun seeded(): List<Category>

    /**
     * The `category.id` of each seeded category still present, by its
     * `seed_name`: what resolves a dictionary entry's category, which names a
     * seeded category (spec #45), whatever the user has since called it.
     */
    fun seededIds(): Map<String, Long> = seeded().associate { it.seedName!! to it.id }

    /**
     * Every category and how many transactions and learned rules point at it,
     * for the Categories list (#155): the counts are the rename's reach and
     * the reason a delete is refused. Uncategorized last, being where Pinged
     * files what it cannot place rather than a category anyone picks.
     */
    @Query(
        """
        SELECT category.*,
            (SELECT COUNT(*) FROM txn WHERE txn.category_id = category.id) AS transactions,
            (SELECT COUNT(*) FROM merchant_rule WHERE merchant_rule.category_id = category.id) AS rules
        FROM category
        ORDER BY category.is_protected, category.sort_order, category.id
        """,
    )
    fun uses(): List<CategoryUse>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM category")
    fun maxSortOrder(): Int

    /**
     * Spec 4's "add": a category named [name], drawn with [iconKey], after
     * every category there is. Not protected and with no `seed_name`, so no
     * dictionary entry files under it until a person does.
     *
     * Refuses a blank or over-long name ([IllegalArgumentException]) and one
     * another category holds ([CategoryNameTakenException]). `@Transaction` so
     * the check, the sort order and the insert see one table.
     *
     * @return the new row's id.
     */
    @Transaction
    fun add(name: String, iconKey: String): Long {
        val clean = requireUsableName(name, exceptId = null)
        require(iconKey.isNotBlank()) { "A category needs an icon" }
        return insert(Category(name = clean, iconKey = iconKey, sortOrder = maxSortOrder() + 1))
    }

    /**
     * Spec 4's rename and change of icon, from the one sheet that edits both.
     * A targeted UPDATE of the two columns: `seed_name`, `sort_order` and
     * `is_protected` are never written, so a renamed seeded category keeps
     * its dictionary entries.
     *
     * Global and retroactive by design (spec 4): no transaction stores a
     * name, so every row filed here shows the new one, in every month.
     *
     * Refuses a protected or missing category ([IllegalArgumentException]),
     * which is Uncategorized: the gate and the categorizer find it by its
     * name. Otherwise as [add] refuses.
     */
    @Transaction
    fun edit(categoryId: Long, name: String, iconKey: String) {
        val protected = isProtected(categoryId)
            ?: throw IllegalArgumentException("No such category: $categoryId")
        require(!protected) { "Category $categoryId is protected and cannot be edited" }
        val clean = requireUsableName(name, exceptId = categoryId)
        require(iconKey.isNotBlank()) { "A category needs an icon" }
        updateNameAndIcon(categoryId, clean, iconKey)
    }

    /** [edit]'s write. `is_protected = 0` in the statement too, as the last word. */
    @Query("UPDATE category SET name = :name, icon_key = :iconKey WHERE id = :categoryId AND is_protected = 0")
    fun updateNameAndIcon(categoryId: Long, name: String, iconKey: String): Int

    /** [name] trimmed, or a refusal; see [add]. [exceptId] is the row being renamed. */
    private fun requireUsableName(name: String, exceptId: Long?): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A category needs a name" }
        require(clean.length <= MAX_NAME_LENGTH) { "A category name is at most $MAX_NAME_LENGTH characters" }
        if (all().any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) }) {
            throw CategoryNameTakenException(clean)
        }
        return clean
    }

    /**
     * Spec 9.4's category chips, most used first (#100): every category but
     * [uncategorized], ordered by how many **counted** transactions ([TxnDao.COUNTED]:
     * committed, not excluded) fall on a `local_date` in `[from, to]`, then by
     * `sort_order`, so a category with no uses keeps its place and a fresh
     * install reads in `sort_order`. Captured and manual rows count alike.
     *
     * Uncategorized is left out by name, as [uncategorizedIdOrNull] finds it:
     * leaving the category empty is how the entry screen says "later".
     */
    @Query(RANKED_BY_USE_SQL)
    fun rankedByUse(from: LocalDate, to: LocalDate, uncategorized: String = Seed.UNCATEGORIZED): List<Category>

    /**
     * **Import only, and named so it cannot be reached for by accident.**
     *
     * A restore has to put the user's categories back under their own ids, because
     * `txn.category_id` and `merchant_rule.category_id` in the same file refer to
     * them -- and the target is never empty, since `DatabaseFactory.build` seeds
     * sixteen rows before anything can read it. Inserting on top collides on the
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

    /**
     * Whether [categoryId] names a category: a writer handed an id from
     * before a restore or a delete-everything asks before writing it, since
     * `txn.category_id`'s foreign key otherwise throws on the write.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM category WHERE id = :categoryId)")
    fun exists(categoryId: Long): Boolean

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

    companion object {
        /**
         * The longest name [add] and [edit] accept. Twice the seed's longest,
         * "Insurance & takaful" at 19, which already takes two lines in the
         * chooser's tile at CI's 320dp.
         */
        const val MAX_NAME_LENGTH = 40

        /**
         * [rankedByUse]'s query. The count is a sub-select over the period's
         * rows, so `txn` is reached by its `local_date` range
         * (`QueryPlanTest.theCategoryRankingTakesTheDayRange`) and only the
         * sixteen categories are sorted. `id` breaks the last tie so the row
         * never reorders between reads.
         */
        const val RANKED_BY_USE_SQL =
            """
            SELECT category.* FROM category
            LEFT JOIN (
                SELECT category_id, COUNT(*) AS uses FROM txn
                WHERE """ + TxnDao.COUNTED + """
                  AND local_date BETWEEN :from AND :to
                GROUP BY category_id
            ) AS used ON used.category_id = category.id
            WHERE category.name != :uncategorized
            ORDER BY COALESCE(used.uses, 0) DESC, category.sort_order, category.id
            """
    }
}
