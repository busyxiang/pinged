package my.pinged.ledger.bill

import java.time.LocalDate
import java.time.ZoneId
import my.pinged.data.dao.BillChoice
import my.pinged.data.entity.TxnState
import my.pinged.ledger.ledgerTxn

/**
 * A bill as `TxnDao.billChoice` reads it, for a screen test with no database:
 * [name] at [amountSen] on [day], filed under [category], with [paidBackSen]
 * paid back against it. [id] is set, since a pick reports it.
 */
internal fun billChoice(
    id: Long,
    name: String,
    amountSen: Long,
    day: LocalDate,
    category: String? = "Food & Drinks",
    paidBackSen: Long = 0L,
    state: TxnState = TxnState.COMMITTED,
    excluded: Boolean = false,
): BillChoice = BillChoice(
    txn = ledgerTxn(
        amountSen = amountSen,
        occurredAt = day.atTime(19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        state = state,
        isExcluded = excluded,
        id = id,
    ).copy(merchantRaw = name.uppercase(), merchantDisplay = name, merchantKey = name.uppercase()),
    displayName = name,
    categoryName = category,
    paidBackSen = paidBackSen,
)

/** 18 September 2026, the README's Yuen Kee dinner. */
internal val SEP_18: LocalDate = LocalDate.of(2026, 9, 18)

/** 20 September 2026, the day Arjun Nair's RM32.00 arrived. */
internal val SEP_20: LocalDate = LocalDate.of(2026, 9, 20)

/**
 * A bill as a list row names it ([my.pinged.data.dao.FeedRow.bill]): [name]
 * on [day], [typed] for a manual entry, [counts] while it counts.
 */
internal fun rowBill(
    id: Long,
    name: String,
    day: LocalDate,
    counts: Boolean = true,
    typed: Boolean = false,
): my.pinged.data.dao.RowBill = my.pinged.data.dao.RowBill(
    id = id,
    createdAt = day.atTime(19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    typed = typed,
    name = name,
    localDate = my.pinged.data.LocalDates.of(day),
    counts = counts,
)

/**
 * A row as the list reads it, for a screen test with no database: [name] at
 * [amountSen] on [day], money received when [direction] says so, paying back
 * [bill] or paid back [paidBackSen]. Captured unless [typed].
 */
internal fun listRow(
    id: Long,
    name: String,
    amountSen: Long,
    day: LocalDate,
    direction: my.pinged.parse.Direction = my.pinged.parse.Direction.EXPENSE,
    bill: my.pinged.data.dao.RowBill? = null,
    paidBackSen: Long = 0L,
    categoryId: Long = 1L,
    state: TxnState = TxnState.COMMITTED,
    excluded: Boolean = false,
    typed: Boolean = false,
    source: String? = "Touch 'n Go",
): my.pinged.data.dao.FeedRow {
    val txn = ledgerTxn(
        amountSen = amountSen,
        occurredAt = day.atTime(13, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        categoryId = categoryId,
        state = state,
        isExcluded = excluded,
        exclusionReason = my.pinged.parse.ExclusionReason.USER.takeIf { excluded },
        id = id,
        rawCaptureId = if (typed) null else my.pinged.ledger.nextCaptureId(),
    ).copy(
        direction = direction,
        merchantRaw = name.uppercase(),
        merchantDisplay = name,
        merchantKey = name.uppercase(),
        sourceLabel = source,
        billTxnId = bill?.id,
    )
    return my.pinged.data.dao.FeedRow(txn, txn.merchantKey, name, bill, paidBackSen)
}
