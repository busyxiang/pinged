package my.pinged.ledger.home

import androidx.annotation.DrawableRes
import my.pinged.ledger.R

/**
 * The drawable for an `icon_key`, or null when this build does not bundle one.
 *
 * **Null is a real answer, not a failure.** Spec 9.6 asks for icons "where
 * they can be resolved", and §4 says only bundled icons can be offered:
 * `icon_key` is a Lucide name and the app ships [PICKER_GROUPS]' 54. The
 * category sheet offers nothing else, so only a restored backup from a build
 * that bundled more could hold another; that row draws its name alone rather
 * than a stand-in glyph, because a wrong picture beside a category name is
 * worse than no picture: the name is already unambiguous and the icon only
 * has to agree with it.
 *
 * **A `when` rather than `Resources.getIdentifier`.** Resolving by name at
 * runtime would be four lines shorter and would break silently: the resource
 * shrinker cannot see a name assembled at runtime, so release builds would
 * drop every one of these files and every category would lose its icon with
 * nothing failing at compile time.
 *
 * The keys are stored strings in a frozen schema, spelled as upstream names
 * the icons, so they cannot be respelled without a migration.
 */
@DrawableRes
internal fun categoryIcon(iconKey: String): Int? = when (iconKey) {
    "utensils" -> R.drawable.ic_category_utensils
    "coffee" -> R.drawable.ic_category_coffee
    "pizza" -> R.drawable.ic_category_pizza
    "soup" -> R.drawable.ic_category_soup
    "ice-cream-cone" -> R.drawable.ic_category_ice_cream_cone
    "beer" -> R.drawable.ic_category_beer
    "shopping-basket" -> R.drawable.ic_category_shopping_basket
    "shopping-bag" -> R.drawable.ic_category_shopping_bag
    "shopping-cart" -> R.drawable.ic_category_shopping_cart
    "store" -> R.drawable.ic_category_store
    "gift" -> R.drawable.ic_category_gift
    "shirt" -> R.drawable.ic_category_shirt
    "car" -> R.drawable.ic_category_car
    "bus" -> R.drawable.ic_category_bus
    "train-front" -> R.drawable.ic_category_train_front
    "plane" -> R.drawable.ic_category_plane
    "bike" -> R.drawable.ic_category_bike
    "fuel" -> R.drawable.ic_category_fuel
    "ship" -> R.drawable.ic_category_ship
    "zap" -> R.drawable.ic_category_zap
    "wifi" -> R.drawable.ic_category_wifi
    "droplet" -> R.drawable.ic_category_droplet
    "flame" -> R.drawable.ic_category_flame
    "house" -> R.drawable.ic_category_house
    "wrench" -> R.drawable.ic_category_wrench
    "trash-2" -> R.drawable.ic_category_trash_2
    "banknote" -> R.drawable.ic_category_banknote
    "wallet" -> R.drawable.ic_category_wallet
    "credit-card" -> R.drawable.ic_category_credit_card
    "piggy-bank" -> R.drawable.ic_category_piggy_bank
    "receipt" -> R.drawable.ic_category_receipt
    "landmark" -> R.drawable.ic_category_landmark
    "hand-coins" -> R.drawable.ic_category_hand_coins
    "shield" -> R.drawable.ic_category_shield
    "users" -> R.drawable.ic_category_users
    "baby" -> R.drawable.ic_category_baby
    "heart-pulse" -> R.drawable.ic_category_heart_pulse
    "stethoscope" -> R.drawable.ic_category_stethoscope
    "pill" -> R.drawable.ic_category_pill
    "dog" -> R.drawable.ic_category_dog
    "graduation-cap" -> R.drawable.ic_category_graduation_cap
    "briefcase" -> R.drawable.ic_category_briefcase
    "book-open" -> R.drawable.ic_category_book_open
    "smartphone" -> R.drawable.ic_category_smartphone
    "ticket" -> R.drawable.ic_category_ticket
    "gamepad-2" -> R.drawable.ic_category_gamepad_2
    "music" -> R.drawable.ic_category_music
    "film" -> R.drawable.ic_category_film
    "dumbbell" -> R.drawable.ic_category_dumbbell
    "palette" -> R.drawable.ic_category_palette
    "hand-heart" -> R.drawable.ic_category_hand_heart
    "scissors" -> R.drawable.ic_category_scissors
    "tag" -> R.drawable.ic_category_tag
    "circle-dashed" -> R.drawable.ic_category_circle_dashed
    else -> null
}

/** One heading of the category sheet's icon picker, and its icons in order. */
internal data class IconGroup(val title: String, val keys: List<String>)

/**
 * Spec 4's picker set, grouped as its table groups it so the sheet is
 * scannable (#155). Every key resolves through [categoryIcon]
 * (`CategoryIconTest`), and every seeded category's is here, so a seeded
 * category's sheet opens with its own icon selected.
 *
 * No places of worship, deliberately (spec 4): `hand-heart` and `landmark`
 * cover the need, and one faith's building without the others' would ship a
 * default about who the app is for.
 */
internal val PICKER_GROUPS: List<IconGroup> = listOf(
    IconGroup("Food and drink", listOf("utensils", "coffee", "pizza", "soup", "ice-cream-cone", "beer")),
    IconGroup("Shops", listOf("shopping-basket", "shopping-bag", "shopping-cart", "store", "gift", "shirt")),
    IconGroup("Getting around", listOf("car", "bus", "train-front", "plane", "bike", "fuel", "ship")),
    IconGroup("Home and bills", listOf("zap", "wifi", "droplet", "flame", "house", "wrench", "trash-2")),
    IconGroup("Money and admin", listOf("banknote", "wallet", "credit-card", "piggy-bank", "receipt", "landmark", "hand-coins", "shield")),
    IconGroup("People and health", listOf("users", "baby", "heart-pulse", "stethoscope", "pill", "dog")),
    IconGroup("Learning and work", listOf("graduation-cap", "briefcase", "book-open", "smartphone")),
    IconGroup("Enjoying yourself", listOf("ticket", "gamepad-2", "music", "film", "dumbbell", "palette")),
    IconGroup("Anything else", listOf("hand-heart", "scissors", "tag", "circle-dashed")),
)
