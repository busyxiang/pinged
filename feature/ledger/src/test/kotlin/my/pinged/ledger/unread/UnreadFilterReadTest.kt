package my.pinged.ledger.unread

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.pinged.data.NamedSource
import my.pinged.data.entity.Arrival
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.read.test.ScriptedRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Could not read filtered to one app (#368): with unread captures from MAE
 * and GrabPay, the GrabPay filter reads GrabPay's alone, and the list
 * unfiltered reads both. Over an in-memory ledger through `ScriptedRunner`,
 * as `UnreadReadTest` is.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadFilterReadTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val runner = ScriptedRunner(app)

    private fun seed() = runBlocking {
        runner.read("The test cannot be seeded") { db ->
            db.rawCaptureDao().insert(unmatched(1, MAE))
            db.rawCaptureDao().insert(unmatched(2, GRAB))
            db.rawCaptureDao().insert(unmatched(3, MAE))
            db.rawCaptureDao().insert(unmatched(4, GRAB))
        }
    }

    private fun shown(model: UnreadViewModel): List<String> {
        model.read.refresh()
        return model.read.state.value.answer!!.items.map { it.sourcePackage }
    }

    @Test fun theGrabPayFilterListsOnlyGrabPaysAndTheListUnfilteredListsBoth() {
        seed()

        val filtered = UnreadViewModel(app, runner, source = NamedSource(GRAB, "GRABPAY"))
        assertEquals("The GrabPay filter did not list GrabPay's alone", listOf(GRAB, GRAB), shown(filtered))

        val every = UnreadViewModel(app, runner, source = null)
        assertEquals("The list unfiltered did not list both apps", listOf(GRAB, MAE, GRAB, MAE), shown(every))
    }

    private fun unmatched(index: Int, pkg: String) = RawCapture(
        sourcePackage = pkg,
        postedAt = 1_600_000_000_000L + index,
        whenMillis = null,
        capturedAt = 1_600_000_000_000L + index,
        sbnKey = "0|$pkg|$index|null|0",
        notifId = index,
        notifTag = null,
        userHandle = 0,
        channelId = "txn",
        flags = 0,
        arrival = Arrival.POSTED,
        title = null,
        text = "Paid RM$index.00 to a merchant",
        bigText = null,
        subText = null,
        extrasJson = null,
        contentHash = "unread-$index",
        parseStatus = ParseStatus.UNMATCHED,
    )

    private companion object {
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
    }
}
