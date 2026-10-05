package my.pinged.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "capture_source")
data class CaptureSource(
    /**
     * Spec section 4 names this column `package`; it is `pkg` here because
     * `package` is a Kotlin hard keyword and every DAO query in the plan
     * reads `WHERE pkg = :pkg`. The column and the property agree, which is
     * what matters for a frozen schema.
     */
    @PrimaryKey val pkg: String,
    val label: String,
    val enabled: Boolean = false,
    @ColumnInfo(name = "is_authoritative") val isAuthoritative: Boolean = false,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_notification_at") val lastNotificationAt: Long? = null,
    @ColumnInfo(name = "expected_monthly_count") val expectedMonthlyCount: Int? = null,
)
