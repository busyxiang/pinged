package my.pinged.capture

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import my.pinged.data.Databases
import my.pinged.data.entity.Arrival
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 9.6's just-in-time prompt, from stage one: which notifications reach
 * [SourcePrompt] at all. `SourcePromptTest` covers what it does once they do.
 *
 * Against the real database and the real `NotificationManager`, so the prompt
 * this asserts on is one the system actually holds.
 */
@RunWith(AndroidJUnit4::class)
class SourcePromptIngestTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotificationsAndForgetEveryPrompt() = runBlocking {
        CaptureFixtures.shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        manager.cancelAll()
        SourcePrompt.forget(context)
    }

    @After
    fun clearTheShade() {
        manager.cancelAll()
    }

    private fun ingest(pkg: String, text: String = "Payment of RM12.00 to TEST MERCHANT"): Long? = runBlocking {
        CaptureIngest.ingest(
            context = context,
            sbn = CaptureFixtures.posted(CaptureFixtures.notification(context, title = "Bank", text = text), pkg),
            arrival = Arrival.POSTED,
            now = System.currentTimeMillis(),
        )
    }

    /** The prompts in the shade, by the package each one is for. */
    private fun prompts(): List<String> = CaptureFixtures.waitForValue(timeoutMillis = 2_000L) {
        manager.activeNotifications.filter { it.notification.channelId == SourcePrompt.CHANNEL_ID }
            .map { it.tag }.takeIf { it.isNotEmpty() }
    }.orEmpty()

    @Test
    fun aSuggestedPackageSwitchedOffIsPromptedFor() {
        CaptureFixtures.allowList(context, TNG, enabled = false)

        assertNull("Nothing from a disabled package is stored", ingest(TNG, text = "RM9.99 to SECRET SHOP"))

        assertEquals(listOf(TNG), prompts())
        val shown = manager.activeNotifications.single { it.tag == TNG }.notification.extras
        // Keyed on the identifier alone: not a word of what the bank said.
        assertFalse(shown.toString().contains("SECRET SHOP"))
    }

    @Test
    fun aSuggestedPackageNeverSeenBeforeIsPromptedFor() {
        // A bank installed after onboarding has no capture_source row at all, and
        // the gate reads a missing row as "no". So must the prompt.
        forgetSource(MAYBANK)

        ingest(MAYBANK)

        assertEquals(listOf(MAYBANK), prompts())
    }

    @Test
    fun aSuggestedPackageSwitchedOnIsNotPromptedFor() {
        CaptureFixtures.allowList(context, TNG, enabled = true)
        try {
            ingest(TNG)
            assertTrue(prompts().isEmpty())
        } finally {
            CaptureFixtures.allowList(context, TNG, enabled = false)
        }
    }

    @Test
    fun aPackageOutsideThePackIsNotPromptedFor() {
        ingest("com.example.chat")

        assertTrue(prompts().isEmpty())
    }

    /** Test setup only: the DAO has no delete, because nothing in the app removes one row. */
    private fun forgetSource(pkg: String) {
        Databases.shared(context).openHelper.writableDatabase
            .execSQL("DELETE FROM capture_source WHERE pkg = ?", arrayOf(pkg))
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAYBANK = "com.maybank2u.life"
    }
}
