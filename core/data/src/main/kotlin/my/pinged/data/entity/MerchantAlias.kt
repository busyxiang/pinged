package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spec 4 and 6.4: the user's statement that the merchant [merchantKey] is the shop
 * [canonicalKey]. Both are `txn.merchant_key` values, which is why neither is a
 * foreign key -- a key is a string several rows share, not a row.
 *
 * **One level, never a chain**: no [canonicalKey] is any row's [merchantKey], and no
 * row's [merchantKey] is its own [canonicalKey]. `MerchantIdentityDao.merge` is the
 * only writer and keeps both, so resolving an identity is one `LEFT JOIN`.
 */
@Entity(tableName = "merchant_alias")
data class MerchantAlias(
    @PrimaryKey @ColumnInfo(name = "merchant_key") val merchantKey: String,
    @ColumnInfo(name = "canonical_key") val canonicalKey: String,
)
