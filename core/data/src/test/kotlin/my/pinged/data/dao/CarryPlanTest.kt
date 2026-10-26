package my.pinged.data.dao

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every branch of [planCarry], which `MerchantRuleCarryTest` and
 * `CorrectionsTest` reach only through an emulator. The keys are `OLD` (the key
 * a re-read moved a row from) and `NEW` (the key it moved to); `C` is a
 * canonical OLD is merged into, and `X` another shop.
 */
class CarryPlanTest {
    private val food = 1L
    private val shopping = 2L

    private fun undecidedTarget() = CarryTarget(
        canonical = null, members = 0, name = null, rule = null, stillKept = false, identityRule = null,
    )

    private fun plan(source: CarrySource, target: CarryTarget = undecidedTarget(), to: String = "NEW") =
        planCarry(from = "OLD", to = to, source = source) { target }

    private val undecidedOld = CarrySource(canonical = null, name = null, rule = null, members = 0)

    // ---- nothing to carry ------------------------------------------------------

    @Test fun aKeyThatDidNotChangeCarriesNothing() {
        assertEquals(CarryPlan.None, planCarry("A", "A", CarrySource("C", "n", food, 3)) { error("read the target") })
    }

    @Test fun anUndecidedKeyCarriesNothingAndNeverReadsTheTarget() {
        assertEquals(CarryPlan.None, planCarry("OLD", "NEW", undecidedOld) { error("read the target") })
    }

    @Test fun aKeyIsDecidedByAnyOneOfItsMergeMembersNameOrRule() {
        for (decided in listOf(
            undecidedOld.copy(canonical = "C"),
            undecidedOld.copy(name = "Old shop"),
            undecidedOld.copy(rule = food),
            undecidedOld.copy(members = 1),
        )) {
            assertEquals(true, decided.decided)
        }
        assertEquals(false, undecidedOld.decided)
    }

    // ---- the old key is still one shop -----------------------------------------

    @Test fun aNewKeyAlreadyTheCanonicalOfAMergedOldKeyCarriesNothing() {
        assertEquals(CarryPlan.None, plan(CarrySource("NEW", null, null, 0)))
    }

    @Test fun aNewKeyAlreadyMergedIntoTheOldKeysCanonicalCarriesNothing() {
        val target = undecidedTarget().copy(canonical = "C")
        assertEquals(CarryPlan.None, plan(CarrySource("C", null, null, 0), target))
    }

    @Test fun aNewKeyAliasedToAnOldKeyRowsStillKeepCarriesNothing() {
        val source = undecidedOld.copy(members = 1)
        val target = undecidedTarget().copy(canonical = "OLD", stillKept = true)
        assertEquals(CarryPlan.None, plan(source, target))
    }

    @Test fun aNewKeyAliasedToAnOldKeyNoRowKeepsTakesItsPlace() {
        val source = undecidedOld.copy(members = 1)
        val target = undecidedTarget().copy(canonical = "OLD", stillKept = false)
        assertEquals(CarryPlan.TakeOver(name = null, rule = RuleMove.NONE), plan(source, target))
    }

    // ---- a new key the user already decided about is left alone (#52) ----------

    @Test fun aNewKeyMergedIntoAnotherShopIsLeftAlone() {
        val target = undecidedTarget().copy(canonical = "X")
        assertEquals(CarryPlan.None, plan(undecidedOld.copy(name = "Old shop"), target))
    }

    @Test fun aNewKeyWithMembersIsLeftAlone() {
        val target = undecidedTarget().copy(members = 2)
        assertEquals(CarryPlan.None, plan(undecidedOld.copy(name = "Old shop"), target))
    }

    @Test fun aNewKeyWithANameOfItsOwnIsLeftAlone() {
        val target = undecidedTarget().copy(name = "New shop")
        assertEquals(CarryPlan.None, plan(undecidedOld.copy(rule = food), target))
    }

    @Test fun aNewKeyWithADifferentRuleIsLeftAlone() {
        val target = undecidedTarget().copy(rule = shopping)
        assertEquals(CarryPlan.None, plan(undecidedOld.copy(rule = food), target))
    }

    @Test fun aNewKeyWithARuleAndAnOldKeyWithNoneIsLeftAlone() {
        val target = undecidedTarget().copy(rule = shopping, identityRule = null)
        assertEquals(CarryPlan.None, plan(undecidedOld.copy(name = "Old shop"), target))
    }

    @Test fun aNewKeyWithADifferentRuleThanAMergedOldKeysIdentityIsLeftAlone() {
        val target = undecidedTarget().copy(rule = shopping, identityRule = food)
        assertEquals(CarryPlan.None, plan(CarrySource("C", null, null, 0), target))
    }

    // ---- the new key joins the old key's shop ---------------------------------

    @Test fun aMergedOldKeyAliasesTheNewKeyToItsCanonical() {
        assertEquals(CarryPlan.Alias(key = "NEW", canonical = "C"), plan(CarrySource("C", null, null, 0)))
    }

    @Test fun aNewKeyWithTheSameRuleAsAMergedOldKeysIdentityJoinsIt() {
        val target = undecidedTarget().copy(rule = food, identityRule = food)
        assertEquals(CarryPlan.Alias(key = "NEW", canonical = "C"), plan(CarrySource("C", null, null, 0), target))
    }

    @Test fun anOldKeyRowsStillKeepIsTheCanonicalTheNewKeyIsAliasedTo() {
        val target = undecidedTarget().copy(stillKept = true)
        assertEquals(CarryPlan.Alias(key = "NEW", canonical = "OLD"), plan(undecidedOld.copy(name = "Old shop"), target))
    }

    @Test fun aNewKeyWithTheSameRuleAsAnOldKeyRowsStillKeepJoinsIt() {
        val target = undecidedTarget().copy(rule = food, stillKept = true)
        assertEquals(CarryPlan.Alias(key = "NEW", canonical = "OLD"), plan(undecidedOld.copy(rule = food), target))
    }

    // ---- the new key takes the old key's place -------------------------------

    @Test fun anOldKeyWithOnlyMembersTakesThemToTheNewKey() {
        assertEquals(CarryPlan.TakeOver(name = null, rule = RuleMove.NONE), plan(undecidedOld.copy(members = 2)))
    }

    @Test fun anOldKeysNameMovesToTheNewKey() {
        assertEquals(CarryPlan.TakeOver(name = "Old shop", rule = RuleMove.NONE), plan(undecidedOld.copy(name = "Old shop")))
    }

    @Test fun anOldKeysRuleMovesToTheNewKey() {
        assertEquals(CarryPlan.TakeOver(name = null, rule = RuleMove.MOVE_SOURCE), plan(undecidedOld.copy(rule = food)))
    }

    @Test fun anOldKeysRuleTheNewKeyAlreadyHasIsDropped() {
        val target = undecidedTarget().copy(rule = food)
        assertEquals(CarryPlan.TakeOver(name = null, rule = RuleMove.DROP_SOURCE), plan(undecidedOld.copy(rule = food), target))
    }

    @Test fun aNewKeyAliasedToTheOldKeyTakesItsPlaceWhateverRuleItHas() {
        val source = undecidedOld.copy(rule = food)
        val target = undecidedTarget().copy(canonical = "OLD", rule = shopping)
        assertEquals(CarryPlan.TakeOver(name = null, rule = RuleMove.REPLACE_TARGET), plan(source, target))
    }

    @Test fun anOldKeyWithNameRuleAndMembersMovesAllThree() {
        val source = CarrySource(canonical = null, name = "Old shop", rule = food, members = 2)
        assertEquals(CarryPlan.TakeOver(name = "Old shop", rule = RuleMove.MOVE_SOURCE), plan(source))
    }
}
