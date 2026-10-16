package my.pinged.capture

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import my.pinged.data.Databases
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.UserRejectRule
import my.pinged.parse.Skeleton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 5.7 through the real worker and the pack that ships: a user reject rule
 * hides a later unmatched capture of its shape, and -- spec 13's load-bearing
 * negative case -- never suppresses a capture a template matches.
 */
@RunWith(AndroidJUnit4::class)
class UserRejectStageTwoTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = Databases.rawCaptureDao(context)
    private val rejects = Databases.userRejectRuleDao(context)

    /** Unique per test method, so the shared database's other rows never share a skeleton. */
    private val marker = "ZZ" + System.nanoTime()
    private val base = System.currentTimeMillis()

    @Before fun prepare() = ParseFixtures.prepare(context)

    private fun rule(title: String, text: String): Long = rejects.insertOrIgnore(
        UserRejectRule(
            sourcePackage = ParseFixtures.TNG,
            skeleton = Skeleton.ofCapture(title, text, null),
            sampleText = text,
            createdAt = base,
        ),
    )

    @Test fun aRuleMatchingAPaymentTemplateDoesNotSuppressThePayment() {
        rule(marker, "Payment of RM1.00 to 99 SPEEDMART successful")
        val payment = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Payment of RM32.00 to 99 SPEEDMART successful",
            sbnKey = "$marker-pay",
            postedAt = base - 5_000L,
        )

        assertEquals(ListenableWorker.Result.success(), ParseFixtures.runWorker(context))

        val capture = captures.byId(payment)
        assertEquals(ParseStatus.MATCHED, capture.parseStatus)
        assertNull("a reject rule decided a matched capture", capture.userRejectRuleId)
        assertNotNull("the payment was suppressed", ParseFixtures.txnForCapture(context, payment))
    }

    @Test fun anUnmatchedCaptureOfADismissedShapeIsHiddenAndAnotherShapeIsNot() {
        val ruleId = rule(marker, "Your FD of RM5,000.00 has matured.")
        val sameShape = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Your FD of RM80.00 has matured.",
            sbnKey = "$marker-fd",
            postedAt = base - 5_000L,
        )
        val otherShape = ParseFixtures.insertCapture(
            context,
            title = marker,
            text = "Your FD of RM80.00 has been renewed.",
            sbnKey = "$marker-renewed",
            postedAt = base - 4_000L,
        )

        assertEquals(ListenableWorker.Result.success(), ParseFixtures.runWorker(context))

        assertEquals(ParseStatus.UNMATCHED, captures.byId(sameShape).parseStatus)
        assertEquals(ruleId, captures.byId(sameShape).userRejectRuleId)
        assertEquals(ParseStatus.UNMATCHED, captures.byId(otherShape).parseStatus)
        assertNull(captures.byId(otherShape).userRejectRuleId)
    }
}
