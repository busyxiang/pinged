package my.pinged.ledger.review

import java.time.ZoneId
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates

/** Noon on a fixed day, so the day and month totals name one period. */
internal val NOON: Long = java.time.LocalDate.of(2026, 8, 15).atTime(12, 0)
    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** [NOON]'s day. */
internal val DAY: LocalDate = LocalDates.of(NOON)

/** Row [id]'s card as [inbox] last read it. */
internal fun card(inbox: ReviewInbox, id: Long): ReviewCard =
    inbox.cards.orEmpty().single { it.txn.id == id }
