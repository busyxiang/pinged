package my.pinged.ledger.sources

import my.pinged.parse.PackagePack
import my.pinged.parse.ParsePack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #366: the sentence under ON YOUR PHONE names the pack's apps, by the pack's
 * own labels, and nothing else.
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
            "Pinged reads Touch 'n Go and MAE. Add payments from other apps by hand.",
            readsLine(packOf("Touch 'n Go", "MAE")),
        )
    }

    @Test fun threeApps() {
        assertEquals(
            "Pinged reads Touch 'n Go, MAE and Boost. Add payments from other apps by hand.",
            readsLine(packOf("Touch 'n Go", "MAE", "Boost")),
        )
    }

    private fun packOf(vararg labels: String) = ParsePack(
        packVersion = 1,
        packages = labels.mapIndexed { i, label -> PackagePack(pkg = "app.$i", label = label) },
    )
}
