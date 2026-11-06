package my.pinged.ledger.sources

import my.pinged.data.SourcesLine
import my.pinged.data.entity.CaptureSource
import my.pinged.parse.PackagePack
import my.pinged.parse.ParsePack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #366: the sentence under ON YOUR PHONE names the pack's apps, by the pack's
 * own labels, and nothing else, sorted and once each as the sources line
 * names them.
 */
class ReadsLineTest {
    @Test fun oneApp() {
        assertEquals(
            "Pinged reads Touch 'n Go. Add payments from other apps by hand.",
            readsLine(packOf("Touch 'n Go")),
        )
    }

    @Test fun twoApps() {
        assertEquals(
            "Pinged reads MAE and Touch 'n Go. Add payments from other apps by hand.",
            readsLine(packOf("Touch 'n Go", "MAE")),
        )
    }

    @Test fun threeApps() {
        assertEquals(
            "Pinged reads Boost, MAE and Touch 'n Go. Add payments from other apps by hand.",
            readsLine(packOf("Touch 'n Go", "MAE", "Boost")),
        )
    }

    @Test fun anAppThePackListsTwiceIsNamedOnce() {
        val pack = ParsePack(
            packVersion = 1,
            packages = listOf(PackagePack(pkg = "app.0", label = "MAE"), PackagePack(pkg = "app.0", label = "MAE")),
        )

        assertEquals("Pinged reads MAE. Add payments from other apps by hand.", readsLine(pack))
    }

    /**
     * Capture sources' sentence and the sources line name one pack's apps in
     * one order, whatever order the pack lists them in, so the two screens
     * never list the same apps two ways.
     */
    @Test fun theSentenceAndTheSourcesLineNameThePacksAppsInOneOrder() {
        val pack = packOf("Touch 'n Go", "MAE", "Boost")
        val line = SourcesLine.of(
            sources = pack.packages.map { CaptureSource(it.pkg, "Installed ${it.label}", enabled = true, firstSeenAt = 0L) },
            rulePackages = pack.packages.mapTo(HashSet()) { it.pkg },
            paidFrom = emptySet(),
            byHand = false,
            packLabels = pack.packages.associate { it.pkg to it.label },
        )

        assertEquals("FROM BOOST, MAE AND TOUCH 'N GO", line.text)
        assertEquals("Pinged reads Boost, MAE and Touch 'n Go. Add payments from other apps by hand.", readsLine(pack))
    }

    private fun packOf(vararg labels: String) = ParsePack(
        packVersion = 1,
        packages = labels.mapIndexed { i, label -> PackagePack(pkg = "app.$i", label = label) },
    )
}
