package my.pinged

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The home destination, and the one `rememberNavBackStack` starts on.
 *
 * `@Serializable` because Nav3 keeps the back stack in saved state: this
 * process dies constantly -- the listener is spawned for one notification and
 * goes away again -- and has to come back on the screen the user left.
 *
 * Registered nowhere. `rememberNavBackStack(vararg elements: NavKey)` resolves
 * each key's serializer reflectively at save time; only the
 * `SavedStateConfiguration` overload requires `polymorphic(NavKey::class)`. So
 * a new destination needs `@Serializable` and nothing else, at the price of a
 * dependency on the class names surviving: `release` leaves `isMinifyEnabled`
 * at its default of false, and turning R8 on would need a keep rule for these
 * types before the back stack could be restored.
 *
 * An object rather than a class while it carries no argument. A deep link into
 * one transaction (spec 9.1) would make this a class with an id, which is the
 * shape it exists to leave room for.
 */
@Serializable data object Ledger : NavKey

/**
 * Spec 9.6's capture-source allow-list, pushed from the ledger's top-bar
 * control (`LedgerScreen`'s `onOpenSources`) and left by either route through
 * [pop].
 *
 * See [Ledger] for why both are `@Serializable` objects registered nowhere.
 */
@Serializable data object Sources : NavKey

/**
 * Push [key] unless it is already on top.
 *
 * A tap is not one event: two pointer-ups inside a frame both run before the
 * recomposition that would take the control off screen, so a plain `add`
 * pushes two entries and back then has to be pressed twice to leave a screen
 * the user reached once.
 *
 * Compared on the key, not on `contains`: a destination legitimately appears
 * twice in a deeper stack -- one transaction reached from another -- and what
 * is wrong is only the immediate repeat.
 */
internal fun MutableList<NavKey>.pushOnce(key: NavKey) {
    if (lastOrNull() != key) add(key)
}

/**
 * Pop the top entry, unless it is the last one.
 *
 * Both ways off a destination go through here -- `NavDisplay`'s `onBack` for
 * the system gesture, and `SourcesScreen`'s chevron -- so the two cannot come
 * to mean different things.
 *
 * `NavDisplay` already refuses `onBack` on a one-entry stack, so the guard is
 * for the other caller: a control that emptied the back stack would leave
 * `NavDisplay` with no entry to render, which is not a state the app has a
 * screen for.
 */
internal fun MutableList<NavKey>.pop() {
    if (size > 1) removeAt(lastIndex)
}
