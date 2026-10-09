package my.pinged.parse

import java.util.Locale

/**
 * What installing a pack would do to the dictionary, for spec 5.9's import
 * preview. Pure data: the preview screen has no home yet, and when it does it
 * renders [preview] rather than rewording it.
 *
 * Entries are the same entry when their text is equal ignoring case, which is
 * the identity [PackLoader] enforces; an entry that kept its text and moved
 * category or mode is [changed], not removed and added.
 */
class DictionaryDiff private constructor(
    val added: List<DictionaryEntry>,
    val changed: List<Change>,
    val removed: List<DictionaryEntry>,
) {
    data class Change(val from: DictionaryEntry, val to: DictionaryEntry)

    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removed.isEmpty()

    /**
     * One line per entry, then [NEW_PAYMENTS_ONLY]. Nothing here is re-filed:
     * a pack changes how new captures are filed and never an old row, so the
     * sentence is part of every non-empty preview and not a tooltip.
     */
    fun preview(): List<String> {
        if (isEmpty) return listOf("No dictionary changes.")
        return added.map { "Added: ${it.text} (${label(it.mode)}) -> ${it.category}" } +
            changed.map {
                "Changed: ${it.to.text} (${label(it.to.mode)}) ${it.from.category} -> ${it.to.category}"
            } +
            removed.map { "Removed: ${it.text} (${label(it.mode)}) -> ${it.category}" } +
            NEW_PAYMENTS_ONLY
    }

    private fun label(mode: MatchMode) = mode.name.lowercase(Locale.ROOT)

    companion object {
        const val NEW_PAYMENTS_ONLY =
            "These changes affect new payments only. Payments already filed keep their category."

        fun between(installed: List<DictionaryEntry>, incoming: List<DictionaryEntry>): DictionaryDiff {
            fun id(e: DictionaryEntry) = e.text.uppercase(Locale.ROOT)
            val before = installed.associateBy(::id)
            val after = incoming.associateBy(::id)
            return DictionaryDiff(
                added = incoming.filter { id(it) !in before },
                changed = incoming.mapNotNull { new ->
                    before[id(new)]?.takeIf { it.category != new.category || it.mode != new.mode }
                        ?.let { Change(it, new) }
                },
                removed = installed.filter { id(it) !in after },
            )
        }
    }
}
