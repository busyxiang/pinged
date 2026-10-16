package my.pinged.ledger.licences

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.ledger.R
import org.junit.Rule
import org.junit.Test

/** Spec 8: each bundled family's OFL text, as it is in the file. */
class LicencesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachFamilyIsShownWithItsLicenceVerbatim() {
        compose.setContent { LicencesScreen(onBack = {}) }
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for ((family, id) in listOf(
            "INSTRUMENT SERIF" to R.raw.ofl_instrument_serif,
            "KARLA" to R.raw.ofl_karla,
            "IBM PLEX MONO" to R.raw.ofl_ibm_plex_mono,
        )) {
            val text = resources.openRawResource(id).bufferedReader().use { it.readText() }
            compose.onNodeWithText(family).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text).performScrollTo()
        }
    }
}
