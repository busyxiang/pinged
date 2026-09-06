package my.pinged.data

import java.time.Instant
import java.time.ZoneId

/**
 * A calendar day as `yyyymmdd`, the form the `local_date` column holds.
 *
 * A value class rather than a bare `Int` because a wrong value here is
 * invisible: the home list orders by `occurred_at` so the row still looks
 * right, while `index_txn_local_date_occurred_at` files it in the wrong month.
 * An epoch-millis or a month number satisfies an `Int` parameter; neither
 * satisfies this.
 *
 * Room stores it as the underlying `INTEGER`.
 */
@JvmInline
value class LocalDate(val yyyymmdd: Int)

object LocalDates {
    /**
     * `yyyymmdd` for an instant, in [zone].
     *
     * Spec 15.7: the zone is the device's current zone, resolved **once** at parse
     * time and written into the column -- "one fixed zone" means fixed at the
     * moment of the write, not a hardcoded region. The alternative it rules out is
     * deriving the day at query time through `strftime(..., 'localtime')`, which
     * depends on the process zone, cannot use the index, and reshuffles history
     * when the user travels.
     *
     * [zone] is a parameter so tests can assert day boundaries deterministically.
     */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return LocalDate(d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth)
    }
}
