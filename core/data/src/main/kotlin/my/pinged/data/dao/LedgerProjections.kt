package my.pinged.data.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import my.pinged.data.LocalDate
import my.pinged.data.entity.Txn

/**
 * One day's net spending, in one currency.
 *
 * Read by the list's day header and never summed from the rows the header sits
 * above: a page boundary can fall mid-day (§9.1), so a subtotal derived from
 * loaded rows is short by whatever is on the next page and short silently.
 */
data class DayTotal(
    val localDate: LocalDate,
    val currency: String,
    val netSen: Long,
)

/** A period total, per currency. Never one row across currencies. */
data class CurrencyTotal(
    val currency: String,
    val netSen: Long,
)

/** A period total for one category, per currency, for the pinned summary's top three. */
data class CategoryTotal(
    val categoryId: Long,
    val currency: String,
    val netSen: Long,
)

/**
 * A row of the home list: the transaction, and spec 6.4's resolution of the
 * merchant it belongs to.
 *
 * @property identityKey the merchant the row groups under; null exactly when
 *   `merchant_key` is.
 * @property displayName what the row shows: the user's name for that merchant,
 *   else the row's own `merchant_display`, else its `merchant_raw`.
 */
data class FeedRow(
    @Embedded val txn: Txn,
    @ColumnInfo(name = "identity_key") val identityKey: String?,
    @ColumnInfo(name = "display_name") val displayName: String?,
)

/** A period total for one merchant (section 8), per currency. */
data class MerchantTotal(
    val identityKey: String,
    val displayName: String?,
    val currency: String,
    val netSen: Long,
    val txnCount: Int,
)
