package my.pinged.capture

import android.app.Notification
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric, because building a real [Notification] is the only way to test
 * the thing that actually breaks: `Bundle.getString` returns null for a
 * `SpannableString`, and banks bold the amount (spec section 3).
 */
@RunWith(RobolectricTestRunner::class)
class NotificationFieldsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // The bug that decides whether this product works at all.
    @Test
    fun `a spannable text field is extracted, not dropped`() {
        val bolded = SpannableString("Payment of RM12.00 to X successful").apply {
            setSpan(StyleSpan(Typeface.BOLD), 11, 18, 0)
        }
        val n = UnitFixtures.builder(context).setContentTitle("Bank").setContentText(bolded).build()

        val fields = NotificationFields.from(UnitFixtures.sbn(n))!!

        assertEquals("Payment of RM12.00 to X successful", fields.text)
    }

    @Test
    fun `a spannable title is extracted too`() {
        val bolded = SpannableString("Maybank").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 7, 0)
        }
        val n = UnitFixtures.builder(context).setContentTitle(bolded).setContentText("x").build()

        assertEquals("Maybank", NotificationFields.from(UnitFixtures.sbn(n))!!.title)
    }

    @Test
    fun `bigText is captured alongside text`() {
        val n = UnitFixtures.builder(context)
            .setContentTitle("Bank")
            .setContentText("short")
            .setStyle(Notification.BigTextStyle().bigText("the long version"))
            .build()

        val fields = NotificationFields.from(UnitFixtures.sbn(n))!!

        assertEquals("short", fields.text)
        assertEquals("the long version", fields.bigText)
    }

    @Test
    fun `subText is captured`() {
        val n = UnitFixtures.builder(context).setContentText("x").setSubText("Savings 1234").build()

        assertEquals("Savings 1234", NotificationFields.from(UnitFixtures.sbn(n))!!.subText)
    }

    @Test
    fun `a notification with no text at all yields all nulls`() {
        val fields = NotificationFields.from(UnitFixtures.sbn(UnitFixtures.builder(context).build()))!!

        assertNull(fields.title)
        assertNull(fields.text)
        assertNull(fields.bigText)
        assertNull(fields.subText)
    }

    /**
     * `NO_EXTRAS` rather than `UNMATCHED` is stage two's business, but stage
     * one has to be able to say which it is: a custom-RemoteViews notification
     * has no reachable text, and the unread list must not invite the user to
     * teach a rule for a message the app cannot see (spec section 3).
     */

    @Test
    fun `a whitespace only field is treated as absent`() {
        // The text field is a non-breaking space, as an escape. Kotlin's
        // isBlank() and trim() both call Char.isWhitespace(), which is false
        // for U+00A0, so a field holding only one of these is "not blank" by
        // the obvious test and empty to a human.
        val n = UnitFixtures.builder(context).setContentTitle("   ").setContentText("\u00A0").build()

        val fields = NotificationFields.from(UnitFixtures.sbn(n))!!

        assertNull(fields.title)
        assertNull(fields.text)
    }

    // The 1970 bug: `notification.when` is app-controlled and frequently zero,
    // and a zero there dates a transaction to 1 January 1970, where it
    // vanishes from every month view while still counting as captured
    // (spec section 4).
    @Test
    fun `postTime is used and a zero when is not`() {
        val n = UnitFixtures.builder(context).setContentText("x").setWhen(0L).build()

        val fields = NotificationFields.from(UnitFixtures.sbn(n))!!

        assertEquals(1_000L, fields.postedAt)
        assertNull(fields.whenMillis)
    }

    @Test
    fun `a non zero when is kept`() {
        val n = UnitFixtures.builder(context).setContentText("x").setWhen(900L).build()

        assertEquals(900L, NotificationFields.from(UnitFixtures.sbn(n))!!.whenMillis)
    }

    @Test
    fun `a negative when is discarded like a zero one`() {
        val n = UnitFixtures.builder(context).setContentText("x").setWhen(-1L).build()

        assertNull(NotificationFields.from(UnitFixtures.sbn(n))!!.whenMillis)
    }

    @Test
    fun `the notification slot identity is carried through`() {
        val n = UnitFixtures.builder(context, "txn-alerts").setContentText("x").build()
        val posted = UnitFixtures.sbn(n, pkg = "com.maybank2u.life", id = 77, tag = "slot")

        val fields = NotificationFields.from(posted)!!

        assertEquals("com.maybank2u.life", fields.sourcePackage)
        assertEquals(77, fields.notifId)
        assertEquals("slot", fields.notifTag)
        assertEquals(posted.key, fields.sbnKey)
        assertEquals("txn-alerts", fields.channelId)
        assertEquals(n.flags, fields.flags)
    }

    @Test
    fun `a null tag stays null rather than becoming a string`() {
        val n = UnitFixtures.builder(context).setContentText("x").build()

        assertNull(NotificationFields.from(UnitFixtures.sbn(n, tag = null))!!.notifTag)
    }

    @Test
    fun `the hash text folds whitespace and prefers bigText over text`() {
        val n = UnitFixtures.builder(context)
            .setContentTitle("Bank")
            .setContentText("short")
            // A zero-width space, written as an escape: source files here are
            // ASCII only, and an invisible character in a literal is exactly
            // the kind of thing that survives review unnoticed.
            .setStyle(Notification.BigTextStyle().bigText("Paid  RM1.00\nto\u200BX"))
            .build()

        assertEquals("bank paid rm1.00 tox", NotificationFields.from(UnitFixtures.sbn(n))!!.normalizedForHash)
    }

    @Test
    fun `the hash text falls back to text when there is no bigText`() {
        val n = UnitFixtures.builder(context).setContentTitle("Bank").setContentText("Paid RM1.00").build()

        assertEquals("bank paid rm1.00", NotificationFields.from(UnitFixtures.sbn(n))!!.normalizedForHash)
    }
}
