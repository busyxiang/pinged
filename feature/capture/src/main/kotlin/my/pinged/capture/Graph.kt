package my.pinged.capture

import my.pinged.data.Databases
import my.pinged.parse.PackLoader
import my.pinged.parse.ParsePack
import my.pinged.parse.RuleMatcher

/**
 * The parse pack and the matcher built over it, once per process.
 *
 * The database half moved to [Databases] in `:core:data`: nothing about
 * memoizing a database handle was specific to notification capture, and while
 * it lived here `:feature:ledger` had to depend on this module to reach the
 * database at all.
 *
 * Manual, because a DI framework here would be scaffolding with one consumer --
 * the system constructs the listener, so it has to reach for its dependencies.
 */
object Graph {
    @Volatile private var matcher: RuleMatcher? = null

    @Volatile private var pack: ParsePack? = null

    /**
     * The bundled parse pack, on the **classpath**, not in the assets.
     *
     * `:core:parse` is a plain JVM module, so `pack.json` in its
     * `src/main/resources` is packaged as a Java resource and reached through the
     * class loader. That is the only form available here: assets from `:app` are
     * not on a library's instrumented-test classpath, and `:core:parse`'s own
     * corpus tests already read this exact file through this exact mechanism, so
     * there is one pack rather than a copy that can drift.
     *
     * `:app` used to carry a `syncPack` task copying it into the APK's assets, on
     * the reasoning that the classpath route was unavailable -- so every APK
     * shipped a second `pack.json` no code could reach. Do not add it back without
     * a reader.
     */
    private const val PACK_RESOURCE = "/pack.json"

    /**
     * The matcher over the bundled pack, built once per process.
     *
     * No [Context] parameter: the pack is a classpath resource and not an
     * asset, so there is nothing for a context to resolve. Taking one anyway
     * would suggest a per-context pack and hide the fact that this is a single
     * process-wide object -- and building a `RuleMatcher` compiles every
     * pattern in the pack, which is not work to repeat per capture.
     */
    fun ruleMatcher(): RuleMatcher =
        matcher ?: synchronized(this) {
            matcher ?: RuleMatcher(parsePack()).also { matcher = it }
        }

    /**
     * The parsed pack itself, for the one consumer that needs its packages rather
     * than its rules: the allow-list screen's suggested list (spec 9.6).
     *
     * Reading it from here rather than keeping a second list of bank identifiers in
     * the UI is what stops the two drifting. A package the screen suggests but the
     * pack has no rules for would capture text and record every notification
     * UNMATCHED, which looks like a broken parser rather than a bad suggestion.
     *
     * The manifest's `<queries>` list is a third copy and cannot be read from here
     * -- build-time metadata with no runtime accessor -- so the screen asks
     * `PackageManager` about each pack package. A package in the pack but missing
     * from `<queries>` is invisible rather than wrong, which is the safe direction.
     */
    fun parsePack(): ParsePack =
        pack ?: synchronized(this) {
            pack ?: PackLoader.load(bundledPack()).also { pack = it }
        }

    private fun bundledPack(): String =
        Graph::class.java.getResourceAsStream(PACK_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException(
                "$PACK_RESOURCE is not on the classpath. It is packaged from " +
                    ":core:parse's main resources; without it no notification " +
                    "can ever be matched and every capture would be recorded " +
                    "UNMATCHED, which looks like a rule gap rather than a " +
                    "packaging failure.",
            )

    /**
     * Tests only: drops everything memoized here and in [Databases].
     *
     * Both halves, from one call, because they used to be one object and
     * every caller wants both. [Databases.reset] closes the handle rather
     * than dropping it: leaving SQLCipher's pool open on a file the caller is
     * about to delete is what `FreshInstallTest` documents the consequence
     * of.
     */
    fun reset() = synchronized(this) {
        Databases.reset()
        matcher = null
        pack = null
    }
}
