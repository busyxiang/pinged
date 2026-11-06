package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.UserTemplateRule
import my.pinged.parse.Direction
import my.pinged.parse.PackagePack
import my.pinged.parse.ParsePack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

/**
 * `SourcesLine.read` against the real encrypted database (#365): what makes
 * an app a read source, what names it on a month, and what makes a month
 * "by hand". The wording is `SourcesLineTest`'s.
 *
 * September 2026 throughout, with October as the month an app did not pay in.
 */
@RunWith(AndroidJUnit4::class)
class SourcesLineReadTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        db = freshDatabase()
        source(TNG, "Touch 'n Go", enabled = true)
        source(MAE, "MAE", enabled = true)
    }

    @After fun tearDown() { db.close() }

    /**
     * The shipped pack's two packages, and no rule in either: only the packages
     * decide. Labelled as `capture_source` labels them, so every expected line
     * reads the same whichever names them; `SourcesLineTest` holds which does.
     */
    private val pack = ParsePack(packVersion = 1, packages = listOf(PackagePack(TNG, "Touch 'n Go"), PackagePack(MAE, "MAE")))

    private fun line(month: YearMonth = SEPTEMBER) = SourcesLine.read(db, pack, month).text

    private fun source(pkg: String, label: String, enabled: Boolean) {
        db.captureSourceDao().insertIfNew(CaptureSource(pkg, label, enabled = enabled, firstSeenAt = 0L))
    }

    private fun teach(pkg: String) {
        db.userTemplateRuleDao().insertOrIgnore(
            UserTemplateRule(
                sourcePackage = pkg,
                pattern = "Paid RM(?<amount>[0-9.]+)",
                direction = Direction.EXPENSE,
                hasMerchantGroup = false,
                sampleText = "Paid RM1.00",
                createdAt = 0L,
            ),
        )
    }

    private var nextCaptureId = 1L

    private fun txn(
        pkg: String?,
        yyyymmdd: Int = 20260915,
        handEntered: Boolean = false,
        state: TxnState = TxnState.COMMITTED,
        isExcluded: Boolean = false,
    ) {
        db.txnDao().insert(
            sampleTxn(
                pkg = pkg,
                rawCaptureId = if (handEntered) null else nextCaptureId++,
                localDate = LocalDate(yyyymmdd),
                categoryId = db.categoryDao().requireUncategorizedId(),
                state = state,
                isExcluded = isExcluded,
            ),
        )
    }

    @Test fun theTwoAppsThePackReadsAreNamed() {
        assertEquals(
            "With only TNG and MAE switched on and nothing taught, the line did not name exactly those two",
            "FROM MAE AND TOUCH 'N GO",
            line(),
        )
    }

    @Test fun enablingAnAppNoRuleReadsWarnsOfItAndDisablingItTakesTheWarningAway() {
        source(GRAB, "GrabPay", enabled = true)
        assertEquals("FROM MAE AND TOUCH 'N GO. GRABPAY IS ON BUT PINGED CANNOT READ IT YET", line())

        db.captureSourceDao().setEnabled(GRAB, false)
        assertEquals("FROM MAE AND TOUCH 'N GO", line())
    }

    @Test fun oneTaughtMessageMovesAnAppFromTheWarningToFrom() {
        source(GRAB, "GrabPay", enabled = true)
        teach(GRAB)

        assertEquals(
            "A taught message did not make its app a read source",
            "FROM GRABPAY, MAE AND TOUCH 'N GO",
            line(),
        )
    }

    @Test fun aTaughtMessageFromAnAppSwitchedOffReadsNothing() {
        source(GRAB, "GrabPay", enabled = false)
        teach(GRAB)

        assertEquals("FROM MAE AND TOUCH 'N GO", line())
    }

    @Test fun aReadSourceThatPaidNothingIsNamed() {
        txn(TNG)

        assertEquals("FROM MAE AND TOUCH 'N GO", line())
    }

    @Test fun anAppSwitchedOffIsNamedOnlyOnTheMonthsItPaidIn() {
        source(GRAB, "GrabPay", enabled = false)
        txn(GRAB, yyyymmdd = 20260915)

        assertEquals(
            "An app switched off since was dropped from the month it paid in",
            "FROM GRABPAY, MAE AND TOUCH 'N GO",
            line(SEPTEMBER),
        )
        assertEquals(
            "An app switched off was named on a month it did not pay in",
            "FROM MAE AND TOUCH 'N GO",
            line(OCTOBER),
        )
    }

    @Test fun onlyACountedTransactionNamesAnApp() {
        source(GRAB, "GrabPay", enabled = false)
        txn(GRAB, state = TxnState.PENDING)
        txn(GRAB, isExcluded = true)
        txn(GRAB, state = TxnState.REJECTED)

        assertEquals(
            "A held, excluded or rejected row named an app whose money is in no total",
            "FROM MAE AND TOUCH 'N GO",
            line(),
        )
    }

    @Test fun aCountedManualEntryEndsTheLineAndByHand() {
        txn(pkg = null, handEntered = true)

        assertEquals("FROM MAE AND TOUCH 'N GO, AND BY HAND", line(SEPTEMBER))
        assertEquals("FROM MAE AND TOUCH 'N GO", line(OCTOBER))
    }

    @Test fun aManualEntryKeptOutIsNotByHand() {
        txn(pkg = null, handEntered = true, isExcluded = true)

        assertEquals("FROM MAE AND TOUCH 'N GO", line())
    }

    @Test fun noReadSourceIsNoAppIsReadYetAndAManualEntryMakesItOnlyByHand() {
        db.captureSourceDao().setEnabled(TNG, false)
        db.captureSourceDao().setEnabled(MAE, false)
        assertEquals("NO APP IS READ YET", line())

        txn(pkg = null, handEntered = true)
        assertEquals("ONLY BY HAND", line())
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
        val SEPTEMBER: YearMonth = YearMonth.of(2026, 9)
        val OCTOBER: YearMonth = YearMonth.of(2026, 10)
    }
}
