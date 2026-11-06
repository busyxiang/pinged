package my.pinged.data

import java.util.Locale

/**
 * How a sentence lists apps, wherever one does: the sources line
 * ([SourcesLine], #365) and Capture sources' sentence (`readsLine`, #366).
 * One order and one joiner for both, so a pack's apps are never listed two
 * ways on two screens.
 */
object AppNames {
    /**
     * [sources] once per package, sorted by name ignoring case, then by
     * package: stable from one read to the next, and the same whether a
     * screen prints the name in capitals or as the pack writes it.
     */
    fun ordered(sources: Iterable<NamedSource>): List<NamedSource> =
        sources.distinctBy { it.pkg }.sortedWith(ORDER)

    /**
     * [sources], already [ordered], as `A`, `A and B`, `A, B and C`: no
     * comma before the last, which [and] joins (` and `, ` AND `). Each
     * name is [name]'s, each joiner [words]', so a screen can make a name a
     * run of its own.
     */
    fun <R> listed(sources: List<NamedSource>, and: String, name: (NamedSource) -> R, words: (String) -> R): List<R> =
        buildList {
            sources.forEachIndexed { i, source ->
                when {
                    i == 0 -> Unit
                    i == sources.lastIndex -> add(words(and))
                    else -> add(words(", "))
                }
                add(name(source))
            }
        }

    private val ORDER = compareBy<NamedSource>({ it.name.uppercase(Locale.ROOT) }, { it.pkg })
}
