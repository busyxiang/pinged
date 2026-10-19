package my.pinged.ledger

import java.util.concurrent.atomic.AtomicLong
import my.pinged.data.LocalDate
import my.pinged.data.LocalDates
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction
import my.pinged.parse.ExclusionReason

// The transaction the ledger draws, for both test source sets: the
// instrumented classes that insert it, and the JVM ones that draw it under
// Robolectric. Plain data, so nothing here touches a database or a device.

/** [ledgerTxn]'s `merchant_display`, which is the row's first line. */
internal const val MERCHANT = "Warung Pak Ali"

/** [ledgerTxn]'s `source_label`, which the row prints after its category. */
internal const val SOURCE_LABEL = "Touch 'n Go eWallet"

/**
 * A `raw_capture_id` no other row in the process holds: the column is unique,
 * and these ids name no capture, so they start far above any a test inserts.
 */
private val captureIds = AtomicLong(1_000_000_000L)

/** The next of those ids, for a fixture that builds its own captured row. */
internal fun nextCaptureId(): Long = captureIds.getAndIncrement()

/**
 * A captured transaction of the shape the ledger draws. Not `core:data`'s
 * `sampleTxn`, which lives in that module's own androidTest source set and is
 * not on this one's compile path.
 *
 * Captured, because it carries a source: a row with a null `raw_capture_id`
 * is a manual entry (spec 4) and draws as one. Its capture id names no
 * `raw_capture` row -- the column has no foreign key -- so an export of it is
 * one `ImportJson` refuses; `review`'s `manual` is the manual-entry fixture.
 */
internal fun ledgerTxn(
    amountSen: Long,
    occurredAt: Long = 1_000L,
    localDate: LocalDate = LocalDates.of(occurredAt),
    categoryId: Long = 1L,
    state: TxnState = TxnState.COMMITTED,
    pendingReason: PendingReason? = null,
    isExcluded: Boolean = false,
    exclusionReason: ExclusionReason? = null,
    /**
     * Left at Room's `autoGenerate` default for the fixtures that get
     * inserted, and set by the ones that never reach a database: the chip
     * reports the row it is on, and a callback assertion against 0 would
     * agree with a screen that reported nothing at all.
     */
    id: Long = 0L,
    rawCaptureId: Long? = nextCaptureId(),
) = Txn(
    id = id,
    rawCaptureId = rawCaptureId,
    amountSen = amountSen,
    direction = Direction.EXPENSE,
    occurredAt = occurredAt,
    localDate = localDate,
    merchantRaw = "WARUNG PAK ALI",
    merchantDisplay = MERCHANT,
    merchantKey = "WARUNG PAK ALI",
    categoryId = categoryId,
    sourcePackage = "my.com.tngdigital.ewallet",
    sourceLabel = SOURCE_LABEL,
    confidence = Confidence.HIGH,
    state = state,
    pendingReason = pendingReason,
    isExcluded = isExcluded,
    exclusionReason = exclusionReason,
    createdAt = occurredAt,
    updatedAt = occurredAt,
)
