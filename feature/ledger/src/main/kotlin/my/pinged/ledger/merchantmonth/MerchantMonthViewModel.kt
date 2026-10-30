package my.pinged.ledger.merchantmonth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import my.pinged.capture.GuardedRunner
import my.pinged.data.CaptureRecord
import my.pinged.data.LocalDates
import my.pinged.data.PingedDatabase
import my.pinged.ledger.home.RowActions
import my.pinged.ledger.home.rowScreen
import my.pinged.read.ScreenRead
import my.pinged.ui.RINGGIT
import java.time.LocalDate
import java.time.YearMonth

/**
 * Merchant month's holder (#344; CONTEXT.md, Merchant month): one merchant
 * identity's month across every category, read whole through `:core:read`'s
 * [ScreenRead].
 *
 * [merchantKey] and [month] are the whole of what it is built from -- the
 * `MerchantMonth` key carries nothing else -- so a holder rebuilt after a
 * process death reads afresh and shows nothing stale. The question is fixed.
 *
 * **The key is resolved on every read**, to its merge target or itself, so a
 * merge made from one of the screen's own rows re-reads as the merchant it
 * joined, a key separated off takes its rows with it, and a rename re-reads
 * the name. Nothing here closes the screen: a month left with nothing counted
 * is a zero header.
 *
 * **Every write is followed by a re-read**, as Day's is: the rows are a list,
 * not the Ledger's `PagingSource`, and nothing invalidates them. [today] is
 * asked as each read begins.
 */
class MerchantMonthViewModel(
    app: Application,
    val merchantKey: String,
    val month: YearMonth,
    private val today: () -> LocalDate = LocalDate::now,
) : AndroidViewModel(app) {

    private val runner = GuardedRunner(app)

    /**
     * The month's read. `MerchantMonthScreen` reads it again on resume and on
     * `Databases.rewrites` through `rememberReadState`.
     */
    internal val read: ScreenRead<Unit, MerchantMonthRead> =
        ScreenRead.fixed(viewModelScope, runner, "The merchant's month cannot be read") { db -> readMonth(db, today()) }

    /** The row's category and merchant actions, shared with the Ledger; the month is read again after each write. */
    val rows = RowActions(
        scope = viewModelScope,
        runner = runner,
        screen = read.rowScreen(),
    )

    /**
     * The identity, its month's line and rows, the kept-out aggregate and its
     * first held row, the name it goes by when no line names it, the month's
     * capture record and the chooser's half, all through [db] in one
     * transaction, so the header and the rows beneath it are one snapshot.
     */
    private fun readMonth(db: PingedDatabase, today: LocalDate): MerchantMonthRead {
        val txns = db.txnDao()
        val range = LocalDates.monthRange(month)
        val totals = txns.merchantMonth(merchantKey, range.start, range.endInclusive)
        return MerchantMonthRead(
            identity = db.merchantIdentityDao().identityOf(merchantKey),
            // Over all time through `index_txn_merchant_key`, so only for a
            // month whose ringgit line, which names it, is not there.
            name = if (totals.none { it.currency == RINGGIT }) txns.merchantName(merchantKey) else null,
            month = month,
            today = today,
            notCaptured = CaptureRecord.read(db, today, month.atDay(1)..month.atEndOfMonth()).month(month).notCaptured,
            totals = totals,
            keptOut = txns.merchantMonthKeptOut(merchantKey, range.start, range.endInclusive),
            firstHeld = txns.merchantMonthFirstHeld(merchantKey, range.start, range.endInclusive, RINGGIT),
            rows = txns.merchantMonthRows(merchantKey, range.start, range.endInclusive, RINGGIT),
            // Through [db]: the same instance and the same snapshot.
            chooser = rows.readChooser(db),
        )
    }
}
