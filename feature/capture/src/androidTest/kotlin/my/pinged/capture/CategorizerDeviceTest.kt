package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.parse.Categorizer
import my.pinged.parse.DictionaryEntry
import my.pinged.parse.FilingSource
import my.pinged.parse.MatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dictionary's word boundary, on the engine that ships.
 *
 * `Categorizer` decides a boundary with `Character.isLetterOrDigit` and never
 * with a regex class, because `\w` is ASCII under `java.util.regex` and
 * Unicode under ICU, and `:core:parse`'s tests run on the first. This file runs
 * **the same table** (`categorizer-boundary-table.tsv`, read from
 * `:core:parse`'s `sharedTest` resources) on Android, extended there with
 * full-width Latin, accented letters, a full-width digit beside a keyword and a
 * combining mark, and expects the same answers. If the premise below shows the
 * engines do not differ, the table still pins `Character` on those inputs on
 * Android.
 *
 * Lives in `:feature:capture` because `:core:parse` has no instrumented source
 * set -- the asymmetry `FullWidthDigitsTest` documents.
 */
@RunWith(AndroidJUnit4::class)
class CategorizerDeviceTest {

    /** The premise, asserted before anything is built on it. */
    @Test
    fun theRegexClassTheMatcherAvoidsReallyDoesReadFullWidthLettersOnAndroid() {
        val fullWidthA = "Ａ"
        assertEquals(
            "ICU no longer reads \\w as Unicode, so the reason the matcher avoids regex " +
                "classes is gone; the table below is still worth running",
            true,
            Regex("^\\w$").matches(fullWidthA),
        )
        assertTrue(
            "Character does not call a full-width letter a letter on this device, " +
                "so no boundary below can hold",
            Character.isLetterOrDigit(fullWidthA.codePointAt(0)),
        )
    }

    @Test
    fun theBoundaryTableHoldsOnAndroid() {
        val rows = rows()
        assertTrue("the table is not being read", rows.size >= 38)
        val failures = rows.mapNotNull { row ->
            val got = Categorizer(listOf(DictionaryEntry(row.entry, "Shopping", row.mode)))
                .file(row.key, learned = null).source
            val want = if (row.matches) FilingSource.DICTIONARY else FilingSource.NONE
            if (got == want) null else "${row.mode} '${row.entry}' against '${row.key}': expected $want, got $got"
        }
        assertEquals(emptyList<String>(), failures)
    }

    private data class Row(val entry: String, val mode: MatchMode, val key: String, val matches: Boolean)

    /** Same columns and `\uXXXX` rule as `:core:parse`'s `BoundaryTable`. */
    private fun rows(): List<Row> {
        val text = CategorizerDeviceTest::class.java.getResourceAsStream("/categorizer-boundary-table.tsv")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("categorizer-boundary-table.tsv is not in the test APK; see :feature:capture's build file")
        val escape = Regex("\\\\u([0-9A-Fa-f]{4})")
        fun unescape(s: String) = escape.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val cols = line.split('\t')
                require(cols.size == 4) { "malformed boundary row: $line" }
                Row(
                    unescape(cols[0]),
                    MatchMode.valueOf(cols[1].uppercase()),
                    unescape(cols[2]),
                    cols[3] == "MATCH",
                )
            }
            .toList()
    }
}
