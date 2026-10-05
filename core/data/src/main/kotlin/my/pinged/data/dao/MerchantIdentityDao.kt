package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.MerchantAlias
import my.pinged.data.entity.MerchantName

/**
 * Spec 6.4: which `merchant_key`s are one shop, and what the user calls it.
 *
 * **No `txn` row is written here.** A merge is a `merchant_alias` row and a
 * rename a `merchant_name` row, both resolved at read time through
 * [MerchantSql], so every row keeps the key its own capture produced and
 * [separate] restores a split exactly.
 *
 * Every write is a targeted statement naming its own columns, never a
 * whole-row `REPLACE`, for the reason `CaptureSourceDao` gives.
 */
@Dao
interface MerchantIdentityDao {
    /** The canonical key [key] is merged into, or null when it is canonical itself. */
    @Query("SELECT canonical_key FROM merchant_alias WHERE merchant_key = :key")
    fun canonicalOf(key: String): String?

    /**
     * Merge [source] into [target], spec 6.4's four steps, in one transaction.
     *
     * [targetName] is the name the sheet showed for the target. The merged
     * merchant keeps it: without it a merged row would go on showing its own
     * capture's name -- `Yuenkeehometowncafe` under `Restoran Yuen Kee Home Town
     * Cafe` -- which is half of what the merge was for.
     *
     * Returns false, writing nothing, when both already resolve to one merchant.
     */
    @Transaction
    fun merge(source: String, target: String, targetName: String): Boolean {
        val s = canonicalOf(source) ?: source
        val t = canonicalOf(target) ?: target
        if (s == t) return false
        // Before the insert, so nothing already merged into `s` is left
        // pointing at a key that is about to stop being canonical.
        repoint(from = s, to = t)
        // Plain ABORT: `s` is canonical, so a conflict means the two steps
        // above are wrong, and that should fail loudly rather than be absorbed.
        insertAlias(MerchantAlias(merchantKey = s, canonicalKey = t))
        // `s`'s own name is kept, not deleted. Nothing reads it while `s` is
        // merged, since names join on the resolved identity, and [separate]
        // then gives it back.
        if (nameOf(t) == null) setName(t, targetName)
        return true
    }

    /**
     * Undo a merge of [key], whatever it was merged into. Its rows group under
     * their own key again at the next read; nothing else moves. Returns the
     * rows deleted, 0 when [key] was not merged.
     */
    @Query("DELETE FROM merchant_alias WHERE merchant_key = :key")
    fun separate(key: String): Int

    /**
     * Name the merchant [key] resolves to.
     *
     * A blank [name], or the one [derivedName] the transactions would show
     * anyway, deletes the stored name rather than restating the default --
     * **unless the merchant is merged**. Unnamed, each row falls back to its
     * own capture's name, so a merged merchant would show its halves apart
     * again; it keeps [derivedName] stored instead.
     */
    @Transaction
    fun rename(key: String, name: String, derivedName: String?) {
        val canonical = canonicalOf(key) ?: key
        val trimmed = name.trim()
        val restatesTheDefault = trimmed.isEmpty() || trimmed == derivedName
        when {
            !restatesTheDefault -> setName(canonical, trimmed)
            memberCount(canonical) == 0 || derivedName.isNullOrBlank() -> deleteName(canonical)
            else -> setName(canonical, derivedName)
        }
    }

    @Query("SELECT COUNT(*) FROM merchant_alias WHERE canonical_key = :canonicalKey")
    fun memberCount(canonicalKey: String): Int

    /** The keys merged into [canonicalKey], each with the name its latest transaction derives. */
    @Query(
        """
        SELECT merchant_alias.merchant_key AS merchantKey,
               (SELECT COALESCE(txn.merchant_display, txn.merchant_raw) FROM txn
                WHERE txn.merchant_key = merchant_alias.merchant_key
                ORDER BY txn.occurred_at DESC LIMIT 1) AS derivedName
        FROM merchant_alias WHERE canonical_key = :canonicalKey ORDER BY merchant_key
        """,
    )
    fun membersOf(canonicalKey: String): List<MerchantMember>

    /**
     * Every merchant the ledger holds, by resolved identity, most recently paid
     * first: the "Same shop as..." list.
     *
     * [MerchantChoice.derivedName] is the latest transaction's own name; SQLite
     * takes a bare column from the row that supplied the group's single `MAX`.
     */
    @Query(
        """
        SELECT g.identity_key AS identityKey,
               COALESCE(merchant_name.display, g.derived) AS displayName,
               g.derived AS derivedName,
               g.txn_count AS txnCount
        FROM (
            SELECT """ + MerchantSql.IDENTITY + """ AS identity_key,
                   COUNT(*) AS txn_count,
                   MAX(txn.occurred_at) AS last_at,
                   COALESCE(txn.merchant_display, txn.merchant_raw) AS derived
            FROM txn """ + MerchantSql.ALIAS_JOIN + """
            WHERE txn.merchant_key IS NOT NULL AND txn.state != 'REJECTED'
            GROUP BY identity_key
        ) g
        LEFT JOIN merchant_name ON merchant_name.merchant_key = g.identity_key
        ORDER BY g.last_at DESC
        """,
    )
    fun choices(): List<MerchantChoice>

    @Query("UPDATE merchant_alias SET canonical_key = :to WHERE canonical_key = :from")
    fun repoint(from: String, to: String): Int

    @Insert
    fun insertAlias(alias: MerchantAlias)

    @Query("SELECT display FROM merchant_name WHERE merchant_key = :key")
    fun nameOf(key: String): String?

    /** SQLite's upsert, which updates in place; `REPLACE` would delete and re-insert. */
    @Query(
        """
        INSERT INTO merchant_name (merchant_key, display) VALUES (:key, :display)
        ON CONFLICT(merchant_key) DO UPDATE SET display = excluded.display
        """,
    )
    fun setName(key: String, display: String)

    @Query("DELETE FROM merchant_name WHERE merchant_key = :key")
    fun deleteName(key: String): Int

    // ---- backup ------------------------------------------------------------

    /**
     * The export's read. Whole, like `category`: the table holds a row per
     * merchant the user merged, which a person makes by hand.
     */
    @Query("SELECT * FROM merchant_alias ORDER BY merchant_key")
    fun allAliases(): List<MerchantAlias>

    @Query("SELECT * FROM merchant_name ORDER BY merchant_key")
    fun allNames(): List<MerchantName>

    /** ABORT, for the reason `MerchantRuleDao.insert` gives: a collision fails the import. */
    @Insert
    fun insertAllAliases(aliases: List<MerchantAlias>)

    @Insert
    fun insertAllNames(names: List<MerchantName>)

    @Query("SELECT COUNT(*) FROM merchant_alias")
    fun countAliases(): Int

    @Query("SELECT COUNT(*) FROM merchant_name")
    fun countNames(): Int

    /**
     * Aliases breaking the one-level rule: a `canonical_key` that is also a
     * `merchant_key`, or a key aliased to itself. Zero from anything [merge] wrote; an
     * import checks it, because a file can say anything.
     */
    @Query(
        """
        SELECT COUNT(*) FROM merchant_alias a
        WHERE a.canonical_key = a.merchant_key
           OR EXISTS (SELECT 1 FROM merchant_alias b WHERE b.merchant_key = a.canonical_key)
        """,
    )
    fun chainedAliasCount(): Int
}

/** One row of [MerchantIdentityDao.choices]. */
data class MerchantChoice(
    val identityKey: String,
    val displayName: String?,
    val derivedName: String?,
    val txnCount: Int,
)

/** One row of [MerchantIdentityDao.membersOf]. */
data class MerchantMember(
    val merchantKey: String,
    val derivedName: String?,
)

/**
 * Spec 6.4's resolution, as SQL fragments every query that names or groups a
 * merchant is built from, so there is one answer to "which shop is this row".
 *
 * `const val`s, concatenated into `@Query`, because Room reads the annotation
 * at compile time.
 */
object MerchantSql {
    /** Joins `merchant_alias` to a query over `txn`. */
    const val ALIAS_JOIN = "LEFT JOIN merchant_alias ON merchant_alias.merchant_key = txn.merchant_key"

    /** The merchant a `txn` row belongs to. Null exactly when `merchant_key` is. */
    const val IDENTITY = "COALESCE(merchant_alias.canonical_key, txn.merchant_key)"

    /** [ALIAS_JOIN], then the user's name for the identity. */
    const val JOINS = ALIAS_JOIN + " LEFT JOIN merchant_name ON merchant_name.merchant_key = " + IDENTITY

    /** The name a `txn` row shows, under [JOINS]. */
    const val NAME = "COALESCE(merchant_name.display, txn.merchant_display, txn.merchant_raw)"
}
