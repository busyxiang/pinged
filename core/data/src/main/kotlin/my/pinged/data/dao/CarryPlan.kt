package my.pinged.data.dao

/**
 * What [MerchantDecisions.carry] reads about the key a re-read moved a row from.
 *
 * @property canonical the key it is merged into, or null when it is canonical itself.
 * @property name the user's name for it, read only when [canonical] is null.
 * @property rule its own learned rule's category, read only when [canonical] is
 *   null: a merged key's own rule is dormant, and the identity's is
 *   [CarryTarget.identityRule].
 * @property members how many keys are merged into it, read only when [canonical] is null.
 */
internal data class CarrySource(val canonical: String?, val name: String?, val rule: Long?, val members: Int) {
    /** Whether the user decided anything about the key. An undecided one carries nothing. */
    val decided: Boolean get() = canonical != null || name != null || rule != null || members > 0
}

/**
 * What [MerchantDecisions.carry] reads about the key the row moved to, and
 * whether the old key is still in use. Read only for a [CarrySource] that is
 * [CarrySource.decided], so a sweep of undecided rows stays at its four reads.
 *
 * @property canonical the key it is merged into, or null.
 * @property members how many keys are merged into it.
 * @property name the user's name for it.
 * @property rule its own learned rule's category.
 * @property stillKept whether some row is still keyed by the old key.
 * @property identityRule the category of the learned rule on the old key's
 *   identity: its canonical's when it was merged, else its own.
 */
internal data class CarryTarget(
    val canonical: String?,
    val members: Int,
    val name: String?,
    val rule: Long?,
    val stillKept: Boolean,
    val identityRule: Long?,
)

/** What to do with the old key's learned rule when the new key takes its place. */
internal enum class RuleMove {
    /** There is no rule to move. */
    NONE,

    /** The new key already has the same rule: delete the old key's. */
    DROP_SOURCE,

    /** Re-point the old key's rule to the new key. */
    MOVE_SOURCE,

    /** Delete the new key's different rule, then re-point the old key's to it. */
    REPLACE_TARGET,
}

/** The writes [MerchantDecisions.carry] makes, decided and not yet made. */
internal sealed interface CarryPlan {
    /** Write nothing. */
    data object None : CarryPlan

    /** Alias [key] to [canonical]: the old key is still one shop, and the new key joins it. */
    data class Alias(val key: String, val canonical: String) : CarryPlan

    /**
     * The new key takes the old key's place: its members are re-pointed to it,
     * [name] moves to it when not null, and the rule moves as [rule] says.
     */
    data class TakeOver(val name: String?, val rule: RuleMove) : CarryPlan
}

/**
 * Spec 5.5's carry as a decision: given what the user decided about [from] and
 * what they decided about [to], which writes carry them. Pure, so every branch
 * is a JVM test; [MerchantDecisions.carry] reads the facts and applies the plan.
 *
 * [target] is called only once [source] is decided, and at most once. **A [to]
 * the user already decided about is left alone** (issue #52): one in another
 * merge, one with members, one with a name of its own, or one with a learned
 * rule in a category other than the one it would join. [CarryPlan.None] there,
 * so folding it in cannot merge two shops the user kept apart or shadow the
 * rule that files [to]'s new captures.
 */
internal fun planCarry(from: String, to: String, source: CarrySource, target: () -> CarryTarget): CarryPlan {
    if (from == to || !source.decided) return CarryPlan.None
    val t = target()
    val identity = source.canonical ?: from
    // Already one shop: [to] is the canonical, or merged into it, or aliased to a [from] rows still keep.
    if ((t.canonical ?: to) == identity && (source.canonical != null || t.stillKept)) return CarryPlan.None
    // [to] was aliased to [from] by an earlier row of this sweep, or the user merged it in.
    val ours = t.canonical == from
    if (!ours && (t.canonical != null || t.members > 0 || t.name != null)) return CarryPlan.None
    if (!ours && t.rule != null && t.rule != (source.rule ?: t.identityRule)) return CarryPlan.None

    if (source.canonical != null || t.stillKept) return CarryPlan.Alias(key = to, canonical = identity)
    // The unique index allows one rule per pattern. A rule already on [to] in
    // [from]'s category makes [from]'s redundant; a dormant one from a merge
    // into [from] is not the rule that was in force.
    val rule = when {
        source.rule == null -> RuleMove.NONE
        t.rule == source.rule -> RuleMove.DROP_SOURCE
        t.rule != null -> RuleMove.REPLACE_TARGET
        else -> RuleMove.MOVE_SOURCE
    }
    return CarryPlan.TakeOver(name = source.name, rule = rule)
}
