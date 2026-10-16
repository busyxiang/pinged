package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import my.pinged.data.Databases
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.PendingReason
import my.pinged.data.entity.TxnState
import my.pinged.data.entity.UserTemplateRule
import my.pinged.parse.Direction
import my.pinged.parse.TaughtTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.8 through the real worker and the pack that ships: a taught rule
 * reads what no pack rule does, holds its first three for review, and never
 * outranks a pack rule.
 */
@RunWith(AndroidJUnit4::class)
class TaughtStageTwoTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val taught = Databases.userTemplateRuleDao(context)

    /** A title no other test's capture has, so a rule here reads only this test's captures. */
    private val marker = "Taught" + System.nanoTime()
    private val base = System.currentTimeMillis()
    private val saved = mutableListOf<Long>()

    @Before fun prepare() = ParseFixtures.prepare(context)

    /** The database is shared by the whole APK: a rule left behind would read other classes' captures. */
    @After fun forget() = saved.forEach { taught.delete(it) }

    private fun teach(text: String, amount: Int, merchant: IntRange?, matchCount: Int = 0): Long {
        val derived = requireNotNull(TaughtTemplate.derive(TaughtTemplate.words(marker, text, null), amount, merchant))
        val id = taught.teach(
            UserTemplateRule(
                sourcePackage = ParseFixtures.TNG,
                pattern = derived.pattern,
                direction = Direction.EXPENSE,
                hasMerchantGroup = derived.hasMerchant,
                matchCount = matchCount,
                sampleText = text,
                createdAt = base,
            ),
            unread = emptyList(),
        )
        saved += id
        return id
    }

    private fun capture(text: String, key: String, title: String = marker) = ParseFixtures.insertCapture(
        context,
        title = title,
        text = text,
        sbnKey = "$marker-$key",
        postedAt = base - 5_000L,
    )

    private fun parse() = assertEquals(ListenableWorker.Result.success(), ParseFixtures.runWorker(context))

    @Test fun aTaughtRuleReadsWhatNoPackRuleDoesAndHoldsItForReview() {
        // Words 0 and 1 are the title's and "Kiosk".
        val id = teach("Kiosk debit RM1.00 at KEDAI AH SENG", amount = 3, merchant = 5..7)
        val (sen, amount) = ParseFixtures.uniqueAmount()
        val read = capture("Kiosk debit $amount at 99 SPEEDMART", "read")

        parse()

        assertEquals(ParseStatus.MATCHED, captures.byId(read).parseStatus)
        assertEquals(TaughtTemplate.ruleId(id), captures.byId(read).matchedRuleId)
        val txn = requireNotNull(ParseFixtures.txnForCapture(context, read))
        assertEquals(sen, txn.amountSen)
        assertEquals("99 SPEEDMART", txn.merchantRaw)
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.RULE_REVIEW, txn.pendingReason)
        assertEquals(1, taught.all().single { it.id == id }.matchCount)
    }

    /** Spec 5.8's first guard ends: the fourth commits on its own. */
    @Test fun afterThreeMatchesARuleCommitsSilently() {
        teach("Kiosk debit RM1.00 at KEDAI AH SENG", amount = 3, merchant = 5..7, matchCount = 3)
        val (_, amount) = ParseFixtures.uniqueAmount()
        val read = capture("Kiosk debit $amount at 99 SPEEDMART", "fourth")

        parse()

        assertEquals(TxnState.COMMITTED, ParseFixtures.txnForCapture(context, read)?.state)
    }

    /** Spec 5.8: no merchant tapped is spec 7.1's missing-merchant review, not a new state. */
    @Test fun aRuleWithNoMerchantLandsInReviewAsMissingMerchant() {
        teach("Kiosk debit RM1.00 from account 1234", amount = 3, merchant = null, matchCount = 3)
        val (_, amount) = ParseFixtures.uniqueAmount()
        val read = capture("Kiosk debit $amount from account 9876", "nomerchant")

        parse()

        val txn = requireNotNull(ParseFixtures.txnForCapture(context, read))
        assertEquals(TxnState.PENDING, txn.state)
        assertEquals(PendingReason.MERCHANT_MISSING, txn.pendingReason)
    }

    /** Spec 5.8's third guard: a pack rule that reads the message wins, and the taught one counts nothing. */
    @Test fun aPackRuleOutranksATaughtRuleOfTheSameMessage() {
        val id = teach("Payment of RM1.00 to KEDAI successful", amount = 3, merchant = 5..5, matchCount = 3)
        val (_, amount) = ParseFixtures.uniqueAmount()
        val read = capture("Payment of $amount to 99SPEEDMART successful", "pack")

        parse()

        assertEquals("tng-payment-v1", captures.byId(read).matchedRuleId)
        assertEquals(3, taught.all().single { it.id == id }.matchCount)
    }

    /** A taught merchant last in its message runs to the end: a failure word there is the pack's to decide. */
    @Test fun aPackRejectStandsOverATaughtRule() {
        teach("Kiosk debit RM1.00 at KEDAI", amount = 3, merchant = 5..5, matchCount = 3)
        val (_, amount) = ParseFixtures.uniqueAmount()
        val failed = capture("Kiosk debit $amount at KEDAI failed", "failed")

        parse()

        assertEquals(ParseStatus.REJECTED, captures.byId(failed).parseStatus)
        assertEquals(null, ParseFixtures.txnForCapture(context, failed))
    }

    /** A taught rule out of time has not said no, as a pack rule out of time has not (spec 5.5). */
    @Test fun aTaughtRuleThatRunsOutOfTimeLeavesTheCaptureForReparse() {
        // Nested quantifiers over a run of x with no y after it: exponential
        // backtracking, far past the 50ms a pattern is given.
        saved += taught.teach(
            UserTemplateRule(
                sourcePackage = ParseFixtures.TNG,
                pattern = "^$marker (?:x+x+)+y(?<amount>[0-9]+)$",
                direction = Direction.EXPENSE,
                hasMerchantGroup = false,
                sampleText = "x",
                createdAt = base,
            ),
            unread = emptyList(),
        )
        val slow = capture("x".repeat(40), "slow")

        parse()

        assertEquals(ParseStatus.GAVE_UP, captures.byId(slow).parseStatus)
    }

    @Test fun aMessageOfAnotherShapeStaysUnread() {
        teach("Kiosk debit RM1.00 at KEDAI AH SENG", amount = 3, merchant = 5..7)
        val unread = capture("Kiosk refund RM1.00 at 99 SPEEDMART", "other")

        parse()

        assertEquals(ParseStatus.UNMATCHED, captures.byId(unread).parseStatus)
    }
}
