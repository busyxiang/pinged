package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import my.pinged.data.entity.MatchType
import my.pinged.data.entity.MerchantAlias
import my.pinged.data.entity.MerchantName
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RuleOrigin

/**
 * The statements every writer of spec 6.4's two tables shares, and spec 5.5's
 * [carry]. A parent of `RawCaptureDao` too, so a re-read's key change and its
 * carry are one transaction on one instance without a second DAO passed in.
 */
interface MerchantDecisions {
    /** The canonical key [key] is merged into, or null when it is canonical itself. */
    @Query("SELECT canonical_key FROM merchant_alias WHERE merchant_key = :key")
    fun canonicalOf(key: String): String?

    /**
     * Undo a merge of [key], whatever it was merged into. Its rows group under
     * their own key again at the next read; nothing else moves. Returns the
     * rows deleted, 0 when [key] was not merged.
     */
    @Query("DELETE FROM merchant_alias WHERE merchant_key = :key")
    fun separate(key: String): Int

    @Query("SELECT COUNT(*) FROM merchant_alias WHERE canonical_key = :canonicalKey")
    fun memberCount(canonicalKey: String): Int

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

    /**
     * The category of the learned rule whose pattern is exactly [pattern], or
     * null. No alias resolution: [carry] asks about a key, not an identity.
     */
    @Query(
        """
        SELECT category_id FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """ AND pattern = :pattern
        """,
    )
    fun learnedCategoryOn(
        pattern: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Long?

    /** Re-points a learned rule's pattern: a targeted `UPDATE` of that one column. */
    @Query(
        """
        UPDATE merchant_rule SET pattern = :to
        WHERE """ + MerchantSql.LEARNED + """ AND pattern = :from
        """,
    )
    fun moveLearned(
        from: String,
        to: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Int

    /**
     * Deletes the learned rule whose pattern is exactly [pattern]: that one key,
     * with no alias resolution and none of the rules of keys merged into it.
     * [carry]'s tool for a rule it has made redundant. Forgetting a merchant
     * for the user is [deleteLearnedOfIdentity]. Returns the
     * rules deleted.
     */
    @Query(
        """
        DELETE FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """ AND pattern = :pattern
        """,
    )
    fun deleteLearnedOn(
        pattern: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Int

    @Query("SELECT EXISTS(SELECT 1 FROM txn WHERE merchant_key = :key)")
    fun keyInUse(key: String): Boolean

    /**
     * Spec 5.5: a re-read moved a row from [from] to [to]; carry the user's
     * merges, name and learned rule with it. Call inside the transaction that moved it, after
     * the move, so [keyInUse] no longer counts that row.
     *
     * - [from] merged into `c`: [to] is aliased to `c` as well.
     * - [from] canonical, and some row still keyed by it: [to] is aliased to
     *   [from], which keeps its members and its name.
     * - [from] canonical, and no row keyed by it: [to] takes its place. Its
     *   members are repointed to [to] and its name moved, so the one-level rule
     *   holds and no identity is left that only aliases resolve to. A learned
     *   rule on [from] has its pattern moved to [to] in the same write.
     *
     * [planCarry] decides, including which [to] the user already decided about
     * is left alone (issue #52); this reads what it asks and makes the writes it
     * returns. A [from] with no decision -- unmerged, no members, no name, no
     * rule -- writes nothing, and returns after the four reads that say so.
     */
    fun carry(from: String, to: String) {
        if (from == to) return
        val canonical = canonicalOf(from)
        // A merged [from]'s own name, members and rule are dormant or moot: its identity's are read instead.
        val source = CarrySource(
            canonical = canonical,
            name = if (canonical == null) nameOf(from) else null,
            rule = if (canonical == null) learnedCategoryOn(from) else null,
            members = if (canonical == null) memberCount(from) else 0,
        )
        val plan = planCarry(from, to, source) {
            CarryTarget(
                canonical = canonicalOf(to),
                members = memberCount(to),
                name = nameOf(to),
                rule = learnedCategoryOn(to),
                stillKept = keyInUse(from),
                identityRule = if (canonical == null) source.rule else learnedCategoryOn(canonical),
            )
        }
        when (plan) {
            CarryPlan.None -> Unit
            is CarryPlan.Alias -> insertAlias(MerchantAlias(merchantKey = plan.key, canonicalKey = plan.canonical))
            is CarryPlan.TakeOver -> {
                // [to] may be aliased to [from] by an earlier row of this sweep, or be
                // a member the user merged in; either way it is canonical from here.
                separate(to)
                repoint(from = from, to = to)
                if (plan.name != null) {
                    setName(to, plan.name)
                    deleteName(from)
                }
                when (plan.rule) {
                    RuleMove.NONE -> Unit
                    RuleMove.DROP_SOURCE -> deleteLearnedOn(from)
                    RuleMove.MOVE_SOURCE -> moveLearned(from = from, to = to)
                    RuleMove.REPLACE_TARGET -> {
                        deleteLearnedOn(to)
                        moveLearned(from = from, to = to)
                    }
                }
            }
        }
    }
}

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
interface MerchantIdentityDao : MerchantDecisions {
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
        carryRule(s, t)
        return true
    }

    /**
     * Spec 6.4 as amended by #35, a learned rule at a merge. Rules are read
     * through the identity, so when [target] has one it already files both
     * keys and [source]'s stays stored and unread for Separate. When only
     * [source] had one it is copied onto [target] with `hit_count = 0`, and
     * [source]'s stays where it is, so Separate gives it back. **No `txn` row
     * is written**: re-filing is the chooser's retroactive fix, and not doing
     * it here is what keeps Separate exact.
     */
    fun carryRule(source: String, target: String) {
        if (ruleOn(target) != null) return
        val rule = learnedRuleOn(source) ?: return
        insertRule(rule.copy(id = 0, pattern = target, hitCount = 0))
    }

    /** The rule holding the unique key `(EXACT, [pattern], unscoped)` whatever its origin, or null. */
    @Query(
        """
        SELECT * FROM merchant_rule
        WHERE match_type = :matchType AND pattern = :pattern AND scoped_package = :scopedPackage
        """,
    )
    fun ruleOn(
        pattern: String,
        matchType: MatchType = MatchType.EXACT,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): MerchantRule?

    /** [pattern]'s `LEARNED` `EXACT` unscoped rule, the one a teaching save writes, or null. */
    @Query(
        """
        SELECT * FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """ AND pattern = :pattern
        """,
    )
    fun learnedRuleOn(
        pattern: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): MerchantRule?

    /** Plain `@Insert` (`ABORT`): the unique index refusing it means [carryRule]'s check is wrong. */
    @Insert
    fun insertRule(rule: MerchantRule): Long

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

    /**
     * The keys merged into [canonicalKey], each with the name its latest
     * transaction derives and its own transaction count, counted as [choices]
     * counts an identity's, so the sheet's figures add up to the identity's.
     */
    @Query(
        """
        SELECT merchant_alias.merchant_key AS merchantKey,
               (SELECT COALESCE(txn.merchant_display, txn.merchant_raw) FROM txn
                WHERE txn.merchant_key = merchant_alias.merchant_key
                ORDER BY txn.occurred_at DESC LIMIT 1) AS derivedName,
               (SELECT COUNT(*) FROM txn
                WHERE txn.merchant_key = merchant_alias.merchant_key AND txn.state != 'REJECTED') AS txnCount,
               NOT EXISTS(SELECT 1 FROM merchant_rule WHERE """ + MerchantSql.LEARNED + """
                  AND merchant_rule.pattern = merchant_alias.merchant_key) AS noOwnRule
        FROM merchant_alias WHERE canonical_key = :canonicalKey ORDER BY merchant_key
        """,
    )
    fun membersOf(
        canonicalKey: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): List<MerchantMember>

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
               g.txn_count AS txnCount,
               (SELECT category.name FROM merchant_rule JOIN category ON category.id = merchant_rule.category_id
                WHERE """ + MerchantSql.LEARNED + """
                  AND merchant_rule.pattern = g.identity_key) AS ruleCategory
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
    fun choices(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): List<MerchantChoice>

    // ---- learned rules ------------------------------------------------------

    /**
     * The category the learned rule for [key]'s merchant identity files under,
     * or null. One statement, which is stage two's per-capture read: the
     * identity is `COALESCE(canonical_key, key)` as `MerchantSql.IDENTITY` has
     * it, resolved by a primary-key subquery on `merchant_alias`, and the rule is
     * found through the unique index on `(match_type, pattern, scoped_package)`.
     *
     * A rule on a key that has been merged away is not found, because the
     * identity it would be read under is the target's: it stays stored for
     * Separate, as `merchant_name` does. A `BUNDLED`, `CONTAINS` or scoped row is
     * not a learned rule and is not read.
     */
    @Query(
        """
        SELECT category_id FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """
          AND pattern = """ + MerchantSql.IDENTITY_OF_KEY + """
        """,
    )
    fun learnedCategoryFor(
        key: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Long?

    /**
     * Every learned rule's identity and category, for the ledger's read-time
     * "filed by the user's rule" marking. The table has one row per merchant
     * the user has taught, so it is read whole rather than once per row.
     */
    @Query(
        """
        SELECT pattern, category_id AS categoryId FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """
        """,
    )
    fun learnedRules(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): List<LearnedRow>

    /**
     * The settings list (#50): one row per live learned rule, A-Z by name.
     *
     * - **Live only.** A rule whose pattern is itself merged away (`merchant_alias`
     *   `merchant_key`) is the dormant source rule kept for Separate; it is not
     *   a merchant the user sees, so it is neither listed nor counted.
     * - **name** is the user's merchant name for the identity, else what the
     *   identity's own latest non-rejected row (highest `id`) shows now, else
     *   the rule's `merchant_display`. That column is the row's name when the
     *   rule was written and nothing updates it when a pack upgrade re-keys the
     *   merchant ([MerchantDecisions.moveLearned]) or a merge copies the rule
     *   ([MerchantIdentityDao.carryRule]), so it is only the last resort, for
     *   a rule with no payments left to name it.
     * - **payments** is spec 6.3's count: committed, non-excluded rows of the
     *   identity (the pattern, or any key merged into it), counted live because
     *   `merchant_rule.hit_count` is unused and nothing increments it. Both
     *   branches are `merchant_key` lookups.
     *
     * `COLLATE NOCASE` orders `mr diy` among the Ms; the pattern then breaks ties
     * so two shops with one name keep a stable order.
     */
    @Query(
        """
        SELECT merchant_rule.pattern AS identity,
               COALESCE(
                   (SELECT display FROM merchant_name WHERE merchant_key = merchant_rule.pattern),
                   (SELECT COALESCE(t.merchant_display, t.merchant_raw) FROM txn t
                    WHERE t.merchant_key = merchant_rule.pattern AND t.state != 'REJECTED' ORDER BY t.id DESC LIMIT 1),
                   merchant_rule.merchant_display
               ) AS name,
               merchant_rule.category_id AS categoryId,
               category.name AS categoryName,
               (SELECT COUNT(*) FROM txn
                WHERE """ + TxnDao.COUNTED + """
                  AND (txn.merchant_key = merchant_rule.pattern
                       OR txn.merchant_key IN (""" + MerchantSql.ALIASED_TO + """merchant_rule.pattern))
               ) AS payments
        FROM merchant_rule
        JOIN category ON category.id = merchant_rule.category_id
        WHERE """ + MerchantSql.LEARNED + """ AND """ + MerchantSql.LIVE_RULE + """
        ORDER BY name COLLATE NOCASE ASC, merchant_rule.pattern ASC
        """,
    )
    fun learnedMerchants(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): List<LearnedMerchant>

    /** The row count of [learnedMerchants], for the settings row's value. */
    @Query(
        """
        SELECT COUNT(*) FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """ AND """ + MerchantSql.LIVE_RULE + """
        """,
    )
    fun learnedCount(
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Int

    /**
     * Forget the teaching for the whole identity [identity] (#50): its learned
     * rule and the dormant ones on keys merged into it, which would otherwise
     * come back through Separate. One statement; **no `txn` row is touched**.
     * Returns the rules deleted. The one-key form [MerchantDecisions.carry] uses is
     * `MerchantDecisions.deleteLearnedOn`.
     */
    @Query(
        """
        DELETE FROM merchant_rule
        WHERE """ + MerchantSql.LEARNED + """
          AND pattern IN """ + MerchantSql.KEYS_OF_IDENTITY + """
        """,
    )
    fun deleteLearnedOfIdentity(
        identity: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): Int

    /**
     * A teaching save (#48): the tapped row to [categoryId] **filed by the
     * rule, not by hand**, and the merchant's learned rule written or updated,
     * in one transaction. Returns false, writing nothing, when the row has no
     * merchant key (or is gone): there is nothing to learn from, and the caller
     * saves a one-off.
     *
     * `user_edited` goes to 0, which is what makes `user_edited` mean exactly
     * "a one-off": a row the rule filed stays one the rule can later re-file.
     * Targeted statements throughout -- an `UPDATE` naming its columns, then an
     * `INSERT` only if it found no row -- never a whole-row `REPLACE`.
     */
    @Transaction
    fun teach(txnId: Long, categoryId: Long, now: Long): Boolean {
        val key = merchantKeyOf(txnId) ?: return false
        val identity = identityOf(key)
        fileByRule(txnId, categoryId, now)
        val updated = updateLearned(identity, categoryId, MatchType.EXACT, RuleOrigin.LEARNED, MerchantRule.UNSCOPED)
        if (updated == 0) {
            insertRule(
                MerchantRule(
                    matchType = MatchType.EXACT,
                    pattern = identity,
                    merchantDisplay = displayOf(txnId) ?: identity,
                    categoryId = categoryId,
                    origin = RuleOrigin.LEARNED,
                    priority = MerchantRuleDao.LEARNED_PRIORITY,
                ),
            )
        }
        return true
    }

    /**
     * What the chooser would also fix if the user taught [categoryId] on the
     * row [txnId] (#49): how many past payments of the merchant would move, and
     * which one-offs stand in the way.
     *
     * One read of one statement, so the two halves are of the same moment.
     * [RetroPreview.movable] is the eligible rows that are not one-offs and not
     * already in [categoryId]; [RetroPreview.conflicts] are the eligible
     * one-offs in any other category, per category. A one-off already in
     * [categoryId] is neither. Eligibility is [MerchantSql.ELIGIBLE]. Dictionary-filed,
     * own-rule, old-rule and Uncategorized rows are all `user_edited = 0` and
     * so all movable: only `user_edited = 1` is confirmed history.
     */
    fun retroPreview(txnId: Long, categoryId: Long): RetroPreview {
        val key = merchantKeyOf(txnId) ?: return RetroPreview(0, emptyList())
        val groups = retroGroups(identityOf(key), txnId, categoryId)
        return RetroPreview(
            movable = groups.filter { !it.oneOff }.sumOf { it.total },
            conflicts = groups.filter { it.oneOff }.map { CategoryCount(it.categoryId, it.total) },
        )
    }

    /**
     * The eligible rows of [identity] other than [txnId] and not already in
     * [categoryId], counted per `(category_id, user_edited)`: what
     * [retroPreview] splits into the rows that would move (`user_edited = 0`)
     * and the one-offs that stand in the way (`user_edited = 1`).
     */
    @Query(
        """
        SELECT category_id AS categoryId, user_edited AS oneOff, COUNT(*) AS total FROM txn
        WHERE """ + MerchantSql.ELIGIBLE + """ AND id != :txnId AND category_id != :categoryId
        GROUP BY category_id, user_edited
        """,
    )
    fun retroGroups(identity: String, txnId: Long, categoryId: Long): List<RetroGroup>

    /**
     * A teaching save that also fixes the merchant's past payments (#49): the
     * rule, the tapped row and the move of every other movable row, in **one
     * transaction that re-checks the conflict inside itself**. A payment the
     * user confirmed can land between the sheet's count and the tap, and the
     * count the sheet showed is then stale; this one is read after the rule is
     * written and before any row moves, under the write lock.
     *
     * A conflict is all-or-nothing: nothing moves, and the rule still saves.
     * Returns `taught = false`, writing nothing, for a row with no merchant key
     * (see [teach]).
     *
     * **A moved row has only `category_id` written.** `user_edited` stays 0,
     * which is what lets a later correction of the rule move it again, and
     * `updated_at` stays too: the ticket's "no other column changes" is taken
     * literally, and nothing here is a decision about that row by a person.
     *
     * The move is one bulk `UPDATE` and so holds the write lock for its
     * duration, under the caller's `Databases.leasing` lease.
     *
     * **Measured on emulator-5554, a Pixel 10a AVD and not a phone**, shared
     * with other work while it ran, by `RetroactiveFixTimingTest`: the whole
     * `teachAndFix`, moving every row of one merchant in a ledger twice that
     * size, five runs each, after [MerchantSql.ELIGIBLE] took `+state`. 10,000 rows moved
     * (20,002 in the ledger): 40, 27, 26, 28, 26 ms. 100,000 moved (200,002 in
     * the ledger, the largest seeded): 6347, 3065, 4009, 3157, 3983 ms. The cost
     * per row is not constant (about 3us at 10,000, 31us at 100,000 on the
     * fastest run), so the figures do not extrapolate downward in a straight
     * line.
     *
     * **At 100,000 rows this breaks `CLAUDE.md`'s rule that no lease outlasts a
     * gate.** A gate waits `Databases.RESET_LEASE_WAIT_MILLIS` (5000) for the
     * lease and the first run, 6347 ms, is past it; the other four are 61% to
     * 80% of it, so one more loaded host would put them past as well. A gate
     * raised during such a move then closes the instance under a lease that is
     * still in its transaction: harmless only because both production callers
     * of a gate unlink the file next, which leaves the lock on a dead inode. A
     * gate already up is refused before the move starts (`Databases.shared`);
     * nothing can stop one statement once it is running.
     *
     * Not chunked: the ticket asks for one transaction, and splitting it would
     * let a capture land between chunks. No cheaper correct way to stay inside
     * the 5000 ms was found, so the risk is documented and not removed. The
     * device export this milestone is specified from holds 80 transactions; one
     * merchant reaching 10,000 is 27 years at a payment a day, so the 100,000
     * case is a bound and not a ledger anyone has. The 10,000 figure is asserted
     * to stay under the 5000 ms, and the 100,000 one is recorded only.
     */
    @Transaction
    fun teachAndFix(txnId: Long, categoryId: Long, now: Long): TeachOutcome {
        if (!teach(txnId, categoryId, now)) return TeachOutcome(taught = false, moved = 0, conflicts = 0)
        val preview = retroPreview(txnId, categoryId)
        val conflicts = preview.conflicts.sumOf { it.count }
        if (conflicts > 0) return TeachOutcome(taught = true, moved = 0, conflicts = conflicts)
        val identity = identityOf(requireNotNull(merchantKeyOf(txnId)))
        return TeachOutcome(taught = true, moved = moveToCategory(identity, txnId, categoryId), conflicts = 0)
    }

    /**
     * Every movable row of [identity] to [categoryId], touching `category_id`
     * alone. `user_edited = 0` is in the `WHERE` as well as being checked by
     * [retroPreview], so a one-off can never be overwritten even by a caller
     * that skipped the check.
     */
    @Query(
        """
        UPDATE txn SET category_id = :categoryId
        WHERE """ + MerchantSql.ELIGIBLE + """ AND id != :txnId AND category_id != :categoryId AND user_edited = 0
        """,
    )
    fun moveToCategory(identity: String, txnId: Long, categoryId: Long): Int

    @Query("SELECT merchant_key FROM txn WHERE id = :txnId")
    fun merchantKeyOf(txnId: Long): String?

    @Query("SELECT COALESCE(merchant_display, merchant_raw) FROM txn WHERE id = :txnId")
    fun displayOf(txnId: Long): String?

    /** [key] resolved through `merchant_alias`, as `MerchantSql.IDENTITY` does for a row. */
    fun identityOf(key: String): String = canonicalOf(key) ?: key

    @Query("UPDATE txn SET category_id = :categoryId, user_edited = 0, updated_at = :now WHERE id = :txnId")
    fun fileByRule(txnId: Long, categoryId: Long, now: Long): Int

    /**
     * Re-points the rule on the unique key `(match_type, pattern, scoped_package)`.
     * `origin` is set as well as matched on the key alone, so a row of another
     * origin already holding the key is taken over rather than colliding with
     * the insert and failing the save.
     */
    @Query(
        """
        UPDATE merchant_rule SET category_id = :categoryId, origin = :origin
        WHERE match_type = :matchType AND pattern = :pattern AND scoped_package = :scopedPackage
        """,
    )
    fun updateLearned(
        pattern: String,
        categoryId: Long,
        matchType: MatchType,
        origin: RuleOrigin,
        scopedPackage: String,
    ): Int

    /**
     * What spec 6.4's sheet shows for [ownKey], or null when no transaction
     * carries it any more. Not one transaction: the sheet is a read, and a
     * transaction would hold the one connection for its five statements.
     */
    fun sheetOf(ownKey: String): MerchantSheetRead? {
        val identity = identityOf(ownKey)
        val choices = choices()
        val self = choices.firstOrNull { it.identityKey == identity } ?: return null
        return MerchantSheetRead(
            identityKey = identity,
            self = self,
            others = choices.filter { it.identityKey != identity },
            members = if (identity == ownKey) membersOf(identity) else emptyList(),
            noOwnRule = ownRuleCategory(ownKey) == null,
        )
    }

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

    /**
     * The category [key]'s own learned rule files under, whether or not [key] is
     * merged away (a merged-away rule is stored and unread). Null with no rule.
     */
    @Query(
        """
        SELECT category.name FROM merchant_rule JOIN category ON category.id = merchant_rule.category_id
        WHERE """ + MerchantSql.LEARNED + """
          AND merchant_rule.pattern = :key
        """,
    )
    fun ownRuleCategory(
        key: String,
        matchType: MatchType = MatchType.EXACT,
        origin: RuleOrigin = RuleOrigin.LEARNED,
        scopedPackage: String = MerchantRule.UNSCOPED,
    ): String?

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

/** One row of [MerchantIdentityDao.learnedMerchants]. */
data class LearnedMerchant(
    val identity: String,
    val name: String,
    val categoryId: Long,
    val categoryName: String,
    val payments: Int,
)

/** One row of [MerchantIdentityDao.learnedRules]. */
data class LearnedRow(val pattern: String, val categoryId: Long)

/** What [MerchantIdentityDao.retroPreview] found. */
data class RetroPreview(val movable: Int, val conflicts: List<CategoryCount>)

/** [count] one-offs the user set to [categoryId] by hand. */
data class CategoryCount(val categoryId: Long, val count: Int)

/** One group of [MerchantIdentityDao.retroGroups]. */
data class RetroGroup(val categoryId: Long, val oneOff: Boolean, val total: Int)

/**
 * What [MerchantIdentityDao.teachAndFix] did: whether a rule was written, how many
 * past rows moved, and how many confirmed one-offs stopped them (then 0 moved).
 */
data class TeachOutcome(val taught: Boolean, val moved: Int, val conflicts: Int)

/**
 * What [MerchantIdentityDao.sheetOf] read.
 *
 * @property self the merchant the sheet is about, by identity.
 * @property others every other merchant, most recently paid first.
 * @property members the keys merged into [identityKey], read only when the sheet's own key is it.
 * @property noOwnRule whether the sheet's own key has no learned rule of its own.
 */
data class MerchantSheetRead(
    val identityKey: String,
    val self: MerchantChoice,
    val others: List<MerchantChoice>,
    val members: List<MerchantMember>,
    val noOwnRule: Boolean,
)

/** One row of [MerchantIdentityDao.choices]. */
data class MerchantChoice(
    val identityKey: String,
    val displayName: String?,
    val derivedName: String?,
    val txnCount: Int,
    /** The category the identity's learned rule files under, or null when it has none. */
    val ruleCategory: String? = null,
)

/** One row of [MerchantIdentityDao.membersOf]. */
data class MerchantMember(
    val merchantKey: String,
    val derivedName: String?,
    /** True when [merchantKey] has no learned rule of its own, so Separate leaves it with none. */
    val noOwnRule: Boolean = false,
    /** The transactions captured under [merchantKey] itself. */
    val txnCount: Int = 0,
)

/**
 * Spec 6.4's resolution, as SQL fragments every query that names or groups a
 * merchant is built from, so there is one answer to "which shop is this row".
 *
 * `const val`s, concatenated into `@Query`, because Room reads the annotation
 * at compile time.
 *
 * **Three shapes, one per job, and `QueryPlanTest` pins each plan.** Forcing the
 * join onto all of them would cost [ELIGIBLE] the `merchant_key` index:
 * - row to identity: [ALIAS_JOIN] and [IDENTITY], for aggregates over `txn`;
 * - key to identity: [IDENTITY_OF_KEY], a primary-key subquery for a point
 *   lookup such as stage two's per-capture read;
 * - identity to rows: [KEYS_OF_IDENTITY], for scans the `merchant_key` index serves.
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

    /**
     * The predicate that names a learned rule: `EXACT`, `LEARNED` and unscoped,
     * the one row a teaching save writes per identity. The three binds are
     * `matchType`, `origin` and `scopedPackage`, which every query using it
     * declares as parameters defaulting to those values, so a literal never
     * appears beside the enum it must agree with. Unqualified, so it serves
     * a query over `merchant_rule` whatever alias it gives it.
     */
    const val LEARNED = "match_type = :matchType AND origin = :origin AND scoped_package = :scopedPackage"

    /** The identity of the key bound as `:key`, resolved by a primary-key lookup on `merchant_alias`. */
    const val IDENTITY_OF_KEY = "COALESCE((SELECT canonical_key FROM merchant_alias WHERE merchant_key = :key), :key)"

    /** The keys merged into the identity that follows it, as a select list ending where its operand goes. */
    const val ALIASED_TO = "SELECT merchant_key FROM merchant_alias WHERE canonical_key = "

    /** The identity bound as `:identity` and every key merged into it: the keys its rows can carry. */
    const val KEYS_OF_IDENTITY = "(SELECT :identity UNION " + ALIASED_TO + ":identity)"

    /**
     * The rows a retroactive fix may count or move (#49): the merchant
     * identity `:identity`, committed and not excluded. Spelled as
     * [KEYS_OF_IDENTITY] rather than as [IDENTITY] over a join, so the
     * `merchant_key` index serves it, which `QueryPlanTest` asserts. `+state` is
     * SQLite's unary plus, as in [TxnDao.COUNTED]: without it the planner took
     * `index_txn_state_occurred_at` on the equality and read every committed
     * payment of every merchant. `PENDING` rows are left to the inbox and
     * `REJECTED` rows are not transactions.
     */
    const val ELIGIBLE = "+state = 'COMMITTED' AND is_excluded = 0 AND merchant_key IN " + KEYS_OF_IDENTITY

    /**
     * A `merchant_rule` row that is not a dormant rule: its pattern is not a
     * merged-away key. The query names the table `merchant_rule`, unaliased.
     */
    const val LIVE_RULE =
        "NOT EXISTS (SELECT 1 FROM merchant_alias WHERE merchant_alias.merchant_key = merchant_rule.pattern)"
}
