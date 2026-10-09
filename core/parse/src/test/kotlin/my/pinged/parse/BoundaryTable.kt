package my.pinged.parse

/**
 * Reader for `categorizer-boundary-table.tsv`, the table `CategorizerTest`
 * runs on the JVM. `CategorizerDeviceTest` in `:feature:capture` carries its
 * own reader of the same file, because a test source set cannot be seen from
 * another module; the file is what they share, and the unescape and column
 * rules it states are all a reader needs.
 */
internal object BoundaryTable {
    data class Row(val entry: String, val mode: MatchMode, val key: String, val matches: Boolean) {
        fun describe() = "${mode.name.lowercase()} '$entry' against '$key'"
    }

    fun rows(): List<Row> {
        val text = BoundaryTable::class.java.getResourceAsStream("/categorizer-boundary-table.tsv")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("categorizer-boundary-table.tsv is not on the test classpath")
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val cols = line.split('\t')
                require(cols.size == 4) { "malformed boundary row (want 4 tab-separated columns): $line" }
                Row(
                    entry = unescape(cols[0]),
                    mode = MatchMode.valueOf(cols[1].uppercase()),
                    key = unescape(cols[2]),
                    matches = when (cols[3]) {
                        "MATCH" -> true
                        "NONE" -> false
                        else -> error("expected MATCH or NONE, got '${cols[3]}' in: $line")
                    },
                )
            }
            .toList()
    }

    private val ESCAPE = Regex("\\\\u([0-9A-Fa-f]{4})")

    private fun unescape(s: String): String =
        ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
}
