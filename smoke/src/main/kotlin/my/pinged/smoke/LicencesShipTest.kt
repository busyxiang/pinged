package my.pinged.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fonts' SIL OFL texts are in R8's output (spec 8, "Typefaces, and how they
 * ship"; issue #123).
 *
 * The resource shrinker drops a resource nothing references, and only a
 * minified build runs it: the debug APK has these files whether or not
 * anything reads them. Looked up by name, because the shrinker also renames
 * the files it keeps (`res/raw/ofl_karla.txt` becomes `res/vu.txt`) and keeps
 * the names in the table.
 */
@RunWith(AndroidJUnit4::class)
class LicencesShipTest {
    @Test fun everyBundledFamilyShipsItsLicenceVerbatim() {
        val app = InstrumentationRegistry.getInstrumentation().context.createPackageContext(APP, 0).resources
        for (name in listOf("ofl_ibm_plex_mono", "ofl_instrument_serif", "ofl_karla")) {
            @Suppress("DiscouragedApi") // the name is the subject: R.raw is the app's, not this APK's
            val id = app.getIdentifier(name, "raw", APP)
            assertNotEquals("raw/$name is not in the shipped APK", 0, id)
            val text = app.openRawResource(id).bufferedReader().use { it.readText() }
            assertTrue("raw/$name does not start with its copyright line", text.startsWith("Copyright "))
            assertTrue("raw/$name is not the OFL 1.1", "SIL OPEN FONT LICENSE Version 1.1" in text)
        }
    }

    private companion object {
        const val APP = "my.pinged.tracker"
    }
}
