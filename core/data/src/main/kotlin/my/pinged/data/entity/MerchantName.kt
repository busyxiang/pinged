package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spec 4 and 6.4: the name of a merchant, keyed by its `merchant_key`.
 *
 * Read only for a canonical key: names join on the resolved identity. A row
 * whose key has since been merged into another is kept but dormant, so that
 * `MerchantIdentityDao.separate` gives the merchant its name back.
 *
 * Never blank. An unmerged merchant never stores the name its transactions
 * would show anyway (`MerchantIdentityDao.rename` deletes the row instead); a
 * merged one does, because unnamed its rows would fall back to their own.
 */
@Entity(tableName = "merchant_name")
data class MerchantName(
    @PrimaryKey @ColumnInfo(name = "merchant_key") val merchantKey: String,
    val display: String,
)
