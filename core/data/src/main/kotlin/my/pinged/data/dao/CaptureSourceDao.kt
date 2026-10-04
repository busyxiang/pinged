package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import my.pinged.data.entity.CaptureSource

@Dao
interface CaptureSourceDao {
    /**
     * Discovery (spec 9.6) creating a row it has not seen before. `IGNORE`, not
     * `REPLACE`.
     *
     * **`@Insert(REPLACE)` here destroyed the user's allow-list consent,
     * unrecoverably.** REPLACE is not an update: SQLite implements it as a delete
     * followed by an insert, so every column not carried by the incoming object
     * reverts to that object's value -- and `CaptureSource.enabled` defaults to
     * `false`. Spec 10.3's liveness path is exactly such a write: it knows a
     * package and a timestamp and nothing else.
     *
     * Unrecoverable rather than annoying because of the default-deny gate: a
     * disabled source's notifications are discarded *before* any write, so there is
     * no raw capture to re-parse once the user notices weeks later that one bank
     * has gone quiet. Android will not replay the past.
     *
     * So there is no whole-row upsert on this DAO. Every mutation below is a
     * targeted `UPDATE` naming its own column, which makes a partial write
     * inexpressible rather than discouraged.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfNew(source: CaptureSource): Long

    /** The allow-list toggle. The only thing that writes `enabled`. */
    @Query("UPDATE capture_source SET enabled = :enabled WHERE pkg = :pkg")
    fun setEnabled(pkg: String, enabled: Boolean): Int

    /**
     * Spec 7.2's `is_authoritative`: which side wins a duplicate pair. A
     * settings toggle, and nothing else writes it.
     */
    @Query("UPDATE capture_source SET is_authoritative = :authoritative WHERE pkg = :pkg")
    fun setAuthoritative(pkg: String, authoritative: Boolean): Int

    /** Re-reading a package's display name after an app update or a relabel. */
    @Query("UPDATE capture_source SET label = :label WHERE pkg = :pkg")
    fun setLabel(pkg: String, label: String): Int

    /**
     * Spec 10.3's per-source liveness. This is the write that used to be a
     * whole-row REPLACE, and the only column it has any business touching.
     */
    @Query("UPDATE capture_source SET last_notification_at = :at WHERE pkg = :pkg")
    fun setLastNotificationAt(pkg: String, at: Long): Int

    /** The rolling average behind the same check. */
    @Query("UPDATE capture_source SET expected_monthly_count = :count WHERE pkg = :pkg")
    fun setExpectedMonthlyCount(pkg: String, count: Int?): Int

    @Query("SELECT COUNT(*) FROM capture_source")
    fun countAll(): Int

    /**
     * The artboard's `4 ON`. A count and not the rows, because the settings row
     * shows a number and loading every allow-list row to call `.size` on it is
     * work the screen throws away.
     */
    @Query("SELECT COUNT(*) FROM capture_source WHERE enabled = 1")
    fun enabledCount(): Int

    /**
     * Every enabled source.
     *
     * **Not** what the listener gate reads: `CaptureIngest` asks
     * `byPackage(pkg)?.enabled`, a primary-key lookup, once per notification.
     * This is the list form, for a caller that wants all of them at once. Two
     * tests used to assert the gate read *this*, which it does not.
     *
     * `WHERE enabled = 1` leads no index, so this is the module's one full
     * scan. The table holds one row per notifying app, so that is the correct
     * plan rather than a missing index.
     */
    @Query("SELECT * FROM capture_source WHERE enabled = 1")
    fun enabled(): List<CaptureSource>

    @Query("SELECT * FROM capture_source WHERE pkg = :pkg LIMIT 1")
    fun byPackage(pkg: String): CaptureSource?

    @Query("SELECT * FROM capture_source ORDER BY label ASC")
    fun all(): List<CaptureSource>

    /**
     * A restore writing the user's allow-list back, whole.
     *
     * **This is not the whole-row upsert the note on [insertIfNew] rules out,
     * and the difference is `ABORT` versus `REPLACE`.** The hazard there is a
     * writer with partial knowledge overwriting a column it never meant to
     * touch -- `enabled` being the one that costs the user their capture
     * history. A plain `@Insert` cannot overwrite anything: it either creates
     * the row or fails. And the caller here has complete knowledge by
     * definition, because every column came out of an export of this same
     * table.
     *
     * `ABORT` rather than `IGNORE` for the same reason the import refuses a
     * non-empty ledger: two rows for one package in a backup file is a
     * corrupt file, and silently keeping whichever arrived first would decide
     * the user's consent by file order.
     */
    @Insert
    fun insertForImport(source: CaptureSource): Long

    /**
     * The batch form, for [my.pinged.ledger.transfer.ImportJson].
     *
     * Room's `@Insert(List)` runs the same number of INSERT statements as a
     * loop does -- see `CategoryDao.seedIfEmpty` -- but acquires the prepared
     * statement once and rebinds per row instead of acquiring and releasing
     * per row. Measured on a Pixel 10a emulator at 20,000 rows inside one
     * transaction: 997ms per-row against 231ms in batches of 500.
     */
    @Insert
    fun insertAllForImport(sources: List<CaptureSource>)

    /**
     * **Import only.** The file's allow-list replaces the target's rather than
     * merging with it, because a merge has no correct answer: a package the
     * file has enabled and the fresh install has discovered-and-disabled would
     * have to resolve one way or the other, and either answer silently
     * overrides something the user chose.
     *
     * What that costs is bounded and worth naming. A source discovered on this
     * device since the export -- a bank the user installed last week -- is
     * dropped, and spec 9.6's discovery re-creates it through [insertIfNew]
     * the next time a notification from it arrives, disabled, which is where a
     * newly discovered source starts anyway.
     */
    @Query("DELETE FROM capture_source")
    fun deleteAllForImport(): Int
}
