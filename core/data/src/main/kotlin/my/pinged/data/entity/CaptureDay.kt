package my.pinged.data.entity

import my.pinged.data.LocalDate
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spec section 4. One row per local date, so the daily rhythm grid can tell
 * "you spent nothing" apart from "Pinged was not watching" -- both of which
 * are an absence of txn rows. Nothing reads this until charts ship, and
 * nothing can backfill it, which is why it is recorded from milestone 1.
 *
 * Spec section 15.1: the primary key is `local_date`, which is the index.
 */
@Entity(tableName = "capture_day")
data class CaptureDay(
    @PrimaryKey @ColumnInfo(name = "local_date") val localDate: LocalDate,
    @ColumnInfo(name = "listener_bound") val listenerBound: Boolean,
    @ColumnInfo(name = "saw_any_notification") val sawAnyNotification: Boolean,
)
