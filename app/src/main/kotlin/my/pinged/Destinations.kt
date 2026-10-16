package my.pinged

import androidx.compose.runtime.MutableState
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
 * Spec 9.6's capture-source allow-list, pushed from its row in settings
 * (`SettingsScreen`'s `onOpenSources`) and left by either route through
 * [pop].
 *
 * See [Ledger] for why these are `@Serializable` objects registered nowhere.
 */
@Serializable data object Sources : NavKey

/**
 * Spec 5.5's reviewable list of corrections a newer pack offers, pushed from
 * its row in settings (`SettingsScreen`'s `onOpenCorrections`).
 *
 * See [Ledger] for why these are `@Serializable` objects registered nowhere.
 */
@Serializable data object Corrections : NavKey

/**
 * The learned-merchants list (#50), pushed from its row in settings
 * (`SettingsScreen`'s `onOpenLearned`).
 *
 * See [Ledger] for why these are `@Serializable` objects registered nowhere.
 */
@Serializable data object Learned : NavKey

/**
 * The bundled fonts' licences (spec 8), pushed from their row in settings
 * (`SettingsScreen`'s `onOpenLicences`).
 *
 * See [Ledger] for why these are `@Serializable` objects registered nowhere.
 */
@Serializable data object Licences : NavKey

/**
 * Spec 9.5's settings, the second tab of `design/Main.dc.html`'s bottom bar.
 * [Sources] is a row inside it, as `design/Settings.dc.html` draws.
 *
 * See [Ledger] for why these are `@Serializable` objects registered nowhere.
 */
@Serializable data object Settings : NavKey

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
 * Spec 9.3's charts, the middle tab of `design/Main.dc.html`'s bottom bar.
 *
 * Carries no month: the selected month is in the screen's own saved state, so
 * a restored back stack never shows a month stale (#81).
 *
 * See [Ledger] for why these are `@Serializable` objects registered nowhere.
 */
@Serializable data object Charts : NavKey

/**
 * One day's transactions (#69), pushed on the Charts tab's own stack when a
 * grid square is tapped, and drawn by `:feature:ledger`'s `DayScreen`.
 *
 * [date] is the ISO `local_date` and nothing else: the screen reads everything
 * when it opens, so a back stack restored after a process death shows nothing
 * stale. The selected month is not on it either; it stays in Charts' own saved
 * state, so a visit to a day never changes it (#81).
 *
 * Here with the other keys rather than in `:feature:ledger`, which has no
 * navigation dependency; see [Ledger] for why it is registered nowhere.
 */
@Serializable data class Day(val date: String) : NavKey

/**
 * Spec 9.2's review inbox (#109), pushed on the **current** tab's stack, so
 * back returns to wherever the user came from, and drawn by
 * `:feature:ledger`'s `ReviewScreen`.
 *
 * [focus] is the `txn.id` whose card to scroll to, or null to open at the
 * top. A held row's badge passes its own id and the Day heading the day's
 * first held row's; the Spending list's pinned `N AWAITING REVIEW` row and
 * Charts' *N awaiting review* line pass null.
 *
 * See [Ledger] for why it is registered nowhere.
 */
@Serializable data class Review(val focus: Long? = null) : NavKey

/**
 * Spec 9.4's entry screen (#99), pushed on the Spending stack by the list's
 * **Add payment** button, and drawn by `:feature:ledger`'s
 * `ManualEntryScreen`. Off the stack's root, so the tab bar is hidden.
 *
 * [txnId] is the manual entry to open filled in, or null to add a new one;
 * [createdAt] is its `created_at`, which tells it from a later row a
 * delete-everything or a restore gives the same id (`EditTarget`).
 *
 * See [Ledger] for why it is registered nowhere.
 */
@Serializable data class ManualEntry(val txnId: Long? = null, val createdAt: Long? = null) : NavKey

/**
 * A captured transaction's detail screen (#153), pushed on the **current**
 * tab's stack from the row's body -- the Spending list's, or a Charts day's --
 * so back returns to the list it was opened from. Drawn by
 * `:feature:ledger`'s `DetailScreen`, off the stack's root, so the tab bar is
 * hidden.
 *
 * [createdAt] beside [txnId] for [ManualEntry]'s reason: a row a
 * delete-everything or a restore gives the same id is not this one.
 *
 * See [Ledger] for why it is registered nowhere.
 */
@Serializable data class Detail(val txnId: Long, val createdAt: Long) : NavKey

/**
 * Spec 9.1's search (#154), pushed on the Spending stack by the magnifier in
 * the list's header, and drawn by `:feature:ledger`'s `SearchScreen`. Off the
 * stack's root, so the tab bar is hidden.
 *
 * Carries nothing: the words and filters are in the screen's own saved
 * state, so a restored back stack comes back to the same search.
 *
 * See [Ledger] for why it is registered nowhere.
 */
@Serializable data object Search : NavKey

/**
 * The Spending stack a first creation starts on: its root, with spec 9.4's
 * entry screen above it when [addPayment] (the launcher shortcut, #107).
 *
 * Built as the stack's initial contents rather than pushed afterwards, so the
 * Ledger is beneath the entry from the first frame and back from it lands
 * there, never outside the app. The Spending tab is the one selected on a
 * first creation, so nothing else needs to change. A launcher fires a static
 * shortcut with `FLAG_ACTIVITY_CLEAR_TASK`, so a running app gets a new
 * Activity here too rather than an `onNewIntent`.
 */
internal fun spendingStackFor(addPayment: Boolean): Array<NavKey> =
    if (addPayment) arrayOf(Tab.Spending.root, ManualEntry()) else arrayOf(Tab.Spending.root)

/**
 * The Settings stack a first creation starts on: its root, with [Sources] above
 * it when [openSources] (a tap on spec 9.6's just-in-time prompt). Settings is
 * beneath because the allow-list is a row inside it (spec 9.5), so back lands
 * where the user would have found it. The prompt fires with
 * `FLAG_ACTIVITY_CLEAR_TASK`, for the shortcut's reason above.
 */
internal fun settingsStackFor(openSources: Boolean): Array<NavKey> =
    if (openSources) arrayOf(Tab.Settings.root, Sources) else arrayOf(Tab.Settings.root)

/**
 * The bottom bar's tabs, in the bar's order, each with the destination its
 * stack starts on.
 */
internal enum class Tab(val root: NavKey) {
    Spending(Ledger),
    Charts(my.pinged.Charts),
    Settings(my.pinged.Settings),
}

/**
 * One back stack per [Tab], and which tab is showing.
 *
 * [entries] is every tab's stack in one list, the shown tab's last, because
 * `NavDisplay` draws the last entry and its decorators keep an entry's
 * `ViewModel` and saved state only while the entry is *in* the list. Handing it
 * just the shown tab's stack would clear the ledger's holder and scroll
 * position at every visit to settings. A tab not shown is not composed, so a
 * settings never opened never builds its holder.
 *
 * The stacks and the selection are passed in rather than built here so the
 * Activity can keep them in saved state (`rememberNavBackStack`,
 * `rememberSaveable`) while the rules stay assertable on plain lists.
 */
internal class TabStacks(
    private val stacks: Map<Tab, MutableList<NavKey>>,
    private val selection: MutableState<Tab>,
) {
    /** The tab showing. */
    val selected: Tab get() = selection.value

    /** The shown tab's own stack, which is where a screen pushes and pops. */
    val current: MutableList<NavKey> get() = stacks.getValue(selected)

    /** What `NavDisplay` is handed: the other tabs' stacks, then [current]. */
    val entries: List<NavKey>
        get() = Tab.entries.filter { it != selected }.flatMap { stacks.getValue(it) } + current

    private val atRoot: Boolean get() = current.size == 1

    /**
     * Whether the bar is drawn: on a tab's root only. A pushed screen has the
     * chevron back of its own, and the bar there would offer a way out of a
     * screen the user is partway through.
     */
    val showsBar: Boolean get() = atRoot

    /**
     * Whether settings' own root is what the user is looking at.
     *
     * The banner's buttons lead to settings and have nothing to offer there
     * (ruling R35), so the Activity draws none.
     */
    val showingSettingsRoot: Boolean get() = selected == Tab.Settings && atRoot

    /** Show [tab] as it was left. */
    fun select(tab: Tab) {
        selection.value = tab
    }

    /**
     * Show settings from its root, for a control drawn over every destination.
     *
     * Unwound, not left as it was: the banner says "Settings says more", and
     * the allow-list over settings is not where that is said.
     */
    fun showSettings() {
        val settings = stacks.getValue(Tab.Settings)
        while (settings.size > 1) settings.removeAt(settings.lastIndex)
        select(Tab.Settings)
    }

    /** Push [key] on the shown tab; see [pushOnce]. */
    fun push(key: NavKey) = current.pushOnce(key)

    /** Pop the shown tab's top screen; see [pop]. */
    fun pop() = current.pop()

    /**
     * The system Back gesture: pop a pushed screen, else leave a tab for
     * Spending, else say it is not handled.
     *
     * `false` is the Activity's cue to leave the app as an unhandled Back would;
     * returning `true` there would make the app impossible to leave.
     */
    fun back(): Boolean = when {
        !atRoot -> { pop(); true }
        selected != Tab.Spending -> { select(Tab.Spending); true }
        else -> false
    }
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
