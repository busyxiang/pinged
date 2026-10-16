package my.pinged.ledger.unread

import java.time.ZoneId
import java.time.ZonedDateTime
import my.pinged.data.dao.UnreadCapture
import org.junit.Assert.assertEquals
import org.junit.Test

/** The card's stamp, in the board's `30 SEP, 9.14 AM` and whatever the device's locale. */
class UnreadStampTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")

    private fun at(hour: Int, minute: Int) = UnreadCapture(
        id = 1L,
        sourcePackage = "my.boost",
        postedAt = ZonedDateTime.of(2026, 9, 30, hour, minute, 0, 0, kl).toInstant().toEpochMilli(),
        title = null,
        text = "You spent RM23.50.",
        bigText = null,
        label = "Boost",
    )

    @Test fun morningAndEveningAsTheBoardWritesThem() {
        assertEquals("30 SEP, 9.14 AM", postedOf(at(9, 14), kl))
        assertEquals("30 SEP, 6.02 PM", postedOf(at(18, 2), kl))
    }

    @Test fun theDevicesLocaleDoesNotSpellIt() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ms-MY"))
            assertEquals("30 SEP, 9.14 AM", postedOf(at(9, 14), kl))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
