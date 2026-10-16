package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Spec 15.2's search index: an external-content FTS4 table over three of
 * [Txn]'s text columns, whose `rowid` is `txn.id`.
 *
 * External content, so **Room generates the triggers** that keep it in step
 * with `txn` on every insert, update and delete; no DAO write maintains it by
 * hand, and none may. The triggers fire on any `UPDATE txn`, a category tap
 * included, not only on a change to these columns.
 *
 * `unicode61`, not the default `simple`, which splits on ASCII
 * non-alphanumerics only and so handles "Touch 'n Go" and a non-ASCII name
 * badly. `SearchTest` runs it under the shipped SQLCipher build.
 *
 * Never read as rows: a query asks it for `rowid`s with `MATCH` and reads
 * everything else from `txn` (`SearchSql`). It holds no text of its own, so
 * the export has no section for it and `Wipe` needs nothing more.
 */
@Fts4(contentEntity = Txn::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "txn_fts")
data class TxnFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    @ColumnInfo(name = "merchant_display") val merchantDisplay: String?,
    @ColumnInfo(name = "merchant_raw") val merchantRaw: String?,
    val note: String?,
)
