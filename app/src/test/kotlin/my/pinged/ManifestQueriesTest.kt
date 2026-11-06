package my.pinged

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import my.pinged.capture.Graph
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Spec 9.6: the manifest's `<queries>` names exactly the pack's packages.
 *
 * Fewer, and a pack app is invisible to `PackageManager`, so Suggested never
 * lists it. More, and the app can see a package no rule reads, which Suggested
 * must not offer (#366): every one of its notifications would go to Could not
 * read and look like a broken parser. Each package added costs a release.
 */
class ManifestQueriesTest {
    @Test fun queriesNameExactlyThePacksPackages() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = manifest.getElementsByTagName("queries")
        val declared = (0 until queries.length).flatMap { q ->
            val packages = (queries.item(q) as Element).getElementsByTagName("package")
            (0 until packages.length).map { (packages.item(it) as Element).getAttributeNS(ANDROID, "name") }
        }
        assertEquals(
            "<queries> and the pack disagree about which apps Pinged may see",
            Graph.parsePack().packages.map { it.pkg }.sorted(),
            declared.sorted(),
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
