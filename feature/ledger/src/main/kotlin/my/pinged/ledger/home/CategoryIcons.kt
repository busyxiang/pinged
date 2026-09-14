package my.pinged.ledger.home

import androidx.annotation.DrawableRes
import my.pinged.ledger.R

/**
 * The drawable for an `icon_key`, or null when this build does not bundle one.
 *
 * **Null is a real answer, not a failure.** Spec 9.6 asks for icons "where
 * they can be resolved", and §4 says only bundled icons can be offered:
 * `icon_key` is a Lucide name, the app ships fourteen of them, and §4's
 * category editor -- which is what would let a row hold any other name -- is
 * out of this milestone. A row that somehow holds an unbundled key draws its
 * name alone rather than a stand-in glyph, because a wrong picture beside a
 * category name is worse than no picture: the name is already unambiguous and
 * the icon only has to agree with it.
 *
 * **A `when` rather than `Resources.getIdentifier`.** Resolving by name at
 * runtime would be four lines shorter and would break silently: the resource
 * shrinker cannot see a name assembled at runtime, so release builds would
 * drop every one of these files and every category would lose its icon with
 * nothing failing at compile time. This mapping is also what makes the set
 * bundled in the sense §4 means -- it is enumerable, so the picker for §4's
 * editor can offer exactly what can be drawn.
 *
 * The keys are the seed's, spelled as `Seed.categories` spells them. They are
 * stored strings in a frozen schema, so they cannot drift without a migration.
 */
@DrawableRes
internal fun categoryIcon(iconKey: String): Int? = when (iconKey) {
    "utensils" -> R.drawable.ic_category_utensils
    "shopping-basket" -> R.drawable.ic_category_shopping_basket
    "car" -> R.drawable.ic_category_car
    "fuel" -> R.drawable.ic_category_fuel
    "zap" -> R.drawable.ic_category_zap
    "wifi" -> R.drawable.ic_category_wifi
    "shopping-bag" -> R.drawable.ic_category_shopping_bag
    "heart-pulse" -> R.drawable.ic_category_heart_pulse
    "graduation-cap" -> R.drawable.ic_category_graduation_cap
    "users" -> R.drawable.ic_category_users
    "hand-heart" -> R.drawable.ic_category_hand_heart
    "landmark" -> R.drawable.ic_category_landmark
    "ticket" -> R.drawable.ic_category_ticket
    "circle-dashed" -> R.drawable.ic_category_circle_dashed
    else -> null
}
