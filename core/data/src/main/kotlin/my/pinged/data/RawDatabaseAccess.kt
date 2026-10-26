package my.pinged.data

/**
 * Marks the members of [Databases] that hand out the shared instance, or take
 * the lease, retirement or reset that only [DatabaseAccess] and [WholeFile]
 * are to take (#302, decisions 11 and 12): [Databases.shared], its DAO
 * accessors, [Databases.leasing], [Databases.retire] and [Databases.reset].
 *
 * **An error, not a warning, and opted into by no production compilation.**
 * Gradle opts in every module's `test` and `androidTest` compilations, where a
 * fixture has to reach the instance, a lease or a reset around the policy, and
 * none of the `main` ones; this module opts in at each use. A caller in
 * production code outside this module therefore does not compile, and what
 * it needed is an entry on [DatabaseAccess] or [WholeFile]. `@OptIn` at such a
 * call site opts one in without Gradle's say-so, so a review has to refuse it.
 *
 * **What it cannot see.** The opt-in covers [Databases] and not
 * [PingedDatabase]: a block that is handed the instance can still call
 * `runInTransaction` or `openHelper` on it, which `RawDatabaseAccessTest`
 * reads the sources for (ruling R42). [Databases.generation],
 * [Databases.rewrites], [Databases.gated] and [Databases.holder] carry none:
 * they are signals, not access.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Raw access to Pinged's database skips the lease, the retry and the damage protocol. " +
        "Use DatabaseAccess, or WholeFile for the whole file; only tests may reach Databases directly.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class RawDatabaseAccess
