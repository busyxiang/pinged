package my.pinged.parse

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantTest {

    /** A pack that declares nothing, to prove the lists are not code. */
    private val none = MerchantNormalization.EMPTY

    /** A pack that shares no entry with the bundled one, for the same reason. */
    private val other = MerchantNormalization(
        acquirerPrefixes = listOf("WALLET#"),
        trailingNoiseSuffixes = listOf(" LIMITED"),
        titleCaseExceptions = listOf("PJ"),
    )

    /** The lists as the bundled `pack.json` actually declares them. */
    private val bundled = ParseFixtures.bundledPack.merchantNormalization

    // Spec 5.4: "The prefix and suffix lists live in the pack, not in code,
    // so they are editable without a release." If any of this still came from
    // a constant, an empty pack would keep stripping.
    @Test fun `a pack that declares no lists normalizes nothing`() {
        assertEquals("TNG*99SPEEDMART", Merchant.clean("TNG*99SPEEDMART", none).value)
        assertEquals("MACHINES SDN BHD", Merchant.clean("MACHINES SDN BHD", none).value)
        assertEquals("Kk Supermart", Merchant.displayFor("KK SUPERMART", none))
    }

    @Test fun `the lists are whatever the pack handed in says`() {
        assertEquals("ACME", Merchant.clean("WALLET#ACME LIMITED", other).value)
        // The bundled entries are not ambient: this pack does not declare them.
        assertEquals("TNG*99SPEEDMART", Merchant.clean("TNG*99SPEEDMART", other).value)
        assertEquals("Kedai PJ", Merchant.displayFor("KEDAI PJ", other))
    }

    // Case in a pack entry is a convenience for whoever edits the file: the
    // comparison happens against the uppercased merchant either way, so
    // " via DuitNow" reads as a rails suffix rather than as shouting.
    @Test fun `pack entries are matched case-insensitively`() {
        val lowerCased = MerchantNormalization(
            acquirerPrefixes = listOf("tng*"),
            trailingNoiseSuffixes = listOf(" sdn bhd"),
        )
        assertEquals("MACHINES", Merchant.clean("TNG*Machines Sdn Bhd", lowerCased).value)
    }

    // The three strings a device actually recorded wrongly, against the
    // bundled pack rather than a test fixture pack.
    @Test fun `the bundled pack fixes the three real device failures`() {
        // Was: "Tenaga Nasional Via Duitnow" -- the MAE rule's `(?<merchant>.+)$`
        // swallows the payment rails, which are trailing noise, not a name.
        assertEquals(
            "Tenaga Nasional",
            Merchant.displayFor("TENAGA NASIONAL via DuitNow", bundled),
        )
        // Was: "Kk Supermart" -- the failure spec 5.4 names, and the reason the
        // exception list exists at all.
        assertEquals(
            "KK Supermart",
            Merchant.displayFor("KK SUPERMART", bundled),
        )
        // A merchant's own capitalisation survives the round trip.
        assertEquals(
            "McDonald's Mid Valley",
            Merchant.displayFor("McDonald's Mid Valley", bundled),
        )
    }

    // Spec 5.4's other two named casualties of unconditional title-casing.
    @Test fun `the exception list keeps an acronym and a name shape`() {
        assertEquals("TNG 99speedmart", Merchant.displayFor("TNG 99SPEEDMART", bundled))
        assertEquals("McDonald's", Merchant.displayFor("MCDONALD'S", bundled))
    }

    // Spec 5.4 applies title-casing "only when the raw string is entirely
    // uppercase". A merchant that capitalised itself is left exactly alone,
    // exception list or not.
    @Test fun `a mixed case merchant is left alone`() {
        assertEquals("foodpanda KLCC", Merchant.displayFor("foodpanda KLCC", bundled))
        assertEquals("Zus Coffee", Merchant.displayFor("Zus Coffee", bundled))
        assertEquals("iPhone Store", Merchant.displayFor("iPhone Store", bundled))
    }

    /**
     * The guard above passes for two reasons and only one of them is the rule:
     * `McDonald's Mid Valley` survives because the pack happens to carry
     * `McDonald's` in its three-entry exception list. Through
     * `display(clean(raw))`, where `clean` uppercases, every mixed-case merchant
     * *not* in that list came out title-cased.
     *
     * Each string below has no exception-list entry and a shape that path
     * destroyed. The expected values are what the notification actually said.
     */
    @Test fun `a mixed case merchant with no exception entry survives stripping`() {
        // The one the old path turned into "Foodpanda Klcc".
        assertEquals("foodpanda KLCC", Merchant.displayFor("foodpanda KLCC", bundled))
        // ...and it still survives with a prefix and a suffix to remove, which
        // is the case that forced the stripping to be case-preserving rather
        // than "skip normalization when the string is mixed case".
        assertEquals("foodpanda KLCC", Merchant.displayFor("GRAB*foodpanda KLCC MY", bundled))
        assertEquals("iPhone Store", Merchant.displayFor("iPhone Store Sdn Bhd", bundled))
        assertEquals("eBay", Merchant.displayFor("TNG*eBay SDN BHD", bundled))
        assertEquals("myBurgerLab", Merchant.displayFor("DUITNOWQR-myBurgerLab-K2", bundled))
    }

    /**
     * The guard is applied to what survives stripping, not to the whole raw string.
     * `TENAGA NASIONAL via DuitNow` contains lower-case letters, so a guard tested
     * against the whole string would decide the merchant had capitalised itself and
     * leave "TENAGA NASIONAL" shouting -- when the only lower-case letters belong
     * to the payment rails the pack exists to remove.
     */
    @Test fun `the guard sees the merchant and not the rails around it`() {
        assertEquals("Tenaga Nasional", Merchant.displayFor("TENAGA NASIONAL via DuitNow", bundled))
        assertEquals("Machines", Merchant.displayFor("MACHINES Sdn Bhd", bundled))
    }

    /**
     * `displayFor` and `clean` must strip exactly the same characters.
     *
     * Two code paths over one rule, and nothing in the app ever compares their
     * outputs -- spec 6.1 keys learned rules by `clean`, the user only ever sees
     * `displayFor`. A divergence would show up as one shop quietly learning a
     * category under a name the ledger never displays. Case is the one thing they
     * may differ on, so folding both settles it.
     */
    @Test fun `display and clean strip identically`() {
        val inputs = listOf(
            "foodpanda KLCC",
            "GRAB*foodpanda KLCC MY",
            "TNG*99SPEEDMART",
            "TENAGA NASIONAL via DuitNow",
            "KK SUPERMART",
            "McDonald's Mid Valley",
            "GRAB* RIDE-3KL",
            "DUITNOWQR-myBurgerLab-K2",
            "SHOPEE MY SDN BHD",
            "iPhone Store Sdn Bhd",
            "THONG KEE.",
            "TENAGA NASIONAL via DuitNow.",
        )
        for (raw in inputs) {
            assertEquals(
                "clean and displayFor disagree about what to strip off '$raw'",
                Merchant.clean(raw, bundled).value,
                Merchant.displayFor(raw, bundled).uppercase(Locale.ROOT),
            )
        }
    }

    /**
     * A suffix written in one case in the pack still comes off a merchant that
     * wrote it in another, without folding the merchant's own case.
     *
     * The lists used to be uppercased at the pack, which was invisible while
     * the only consumer uppercased its input first.
     */
    @Test fun `pack entries match case-insensitively without folding the merchant`() {
        val lowerCased = MerchantNormalization(
            acquirerPrefixes = listOf("tng*"),
            trailingNoiseSuffixes = listOf(" sdn bhd"),
        )
        assertEquals("Machines", Merchant.displayFor("TNG*Machines SDN BHD", lowerCased))
        assertEquals("myBurgerLab", Merchant.displayFor("tng*myBurgerLab sdn bhd", lowerCased))
    }

    /**
     * A suffix that ends in a stop, followed by the sentence's own stop.
     *
     * Both strings are one shop as a device recorded it: MAE's Scan & Pay
     * bounds the merchant at ". REF:", so it keeps the suffix's stop only;
     * TnG's DuitNow wording runs the merchant to the end of the sentence, so
     * it carries both. Taking the whole run of stops at once takes the
     * suffix's own with it, the suffix no longer matches, and spec 8 shows
     * the shop twice -- eight payments split four and four.
     */
    @Test fun `a sentence stop after a suffix ending in a stop still strips the suffix`() {
        val scanPay = Merchant.clean("SPADES BAKERY 3 SDN. BHD.", bundled)
        val duitNow = Merchant.clean("SPADES BAKERY 3 SDN. BHD..", bundled)
        assertEquals("SPADES BAKERY 3", scanPay.value)
        assertEquals(scanPay, duitNow)
        assertEquals("Spades Bakery 3", Merchant.displayFor("SPADES BAKERY 3 SDN. BHD..", bundled))
    }

    // The pass cap used to be the constant 8, "enough for every suffix in the
    // list plus slack" -- true of the list of the day, and silently false the
    // moment a pack grows. Twelve stacked suffixes need twelve passes, and
    // under the old cap this stopped four short with no error.
    @Test fun `a suffix list longer than the old cap still strips all of it`() {
        val many = MerchantNormalization(trailingNoiseSuffixes = (1..12).map { " N$it" })
        val shouted = "KEDAI" + (1..12).joinToString("") { " N$it" }
        assertEquals("KEDAI", Merchant.clean(shouted, many).value)
    }

    // A runtime-built pack (spec 5.8) never passes through PackLoader, so a
    // blank entry can reach here. A blank suffix ends every string.
    @Test fun `a blank list entry is ignored rather than matching everything`() {
        val blanks = MerchantNormalization(
            acquirerPrefixes = listOf("", "TNG*"),
            trailingNoiseSuffixes = listOf("   ", " MY"),
        )
        assertEquals("KEDAI", Merchant.clean("TNG*KEDAI MY", blanks).value)
    }

    @Test fun `strips acquirer prefix`() =
        assertEquals("99SPEEDMART", Merchant.clean("TNG*99SPEEDMART", bundled).value)

    @Test fun `strips duitnow qr prefix`() =
        assertEquals("RESTORAN ALI", Merchant.clean("DUITNOWQR-RESTORAN ALI", bundled).value)

    @Test fun `strips corporate suffix`() =
        assertEquals("MACHINES", Merchant.clean("MACHINES SDN BHD", bundled).value)

    // The country code as a card acquirer sends it. ` MY` does not end
    // "... LUMPUR MYS", so before the alpha-3 was listed the country was part
    // of `merchant_key` -- the identity section 8 aggregates by -- and the
    // display read "... Kuala Lumpur Mys". The city is still there, and is a
    // separate problem with no corpus behind it; see
    // `MerchantNormalization.trailingNoiseSuffixes`.
    @Test fun `strips the alpha-3 country code a card acquirer sends`() {
        assertEquals(
            "HOCK KEE - THE GARDENS KUALA LUMPUR",
            Merchant.clean("HOCK KEE - THE GARDENS KUALA LUMPUR MYS", bundled).value,
        )
    }

    /**
     * The sentence's full stop, off the key and off the display.
     *
     * "You have paid RM6.25 for THONG KEE." is a real TnG notification and the
     * rule's group runs to the end of `text`, so `merchant_raw` is
     * "THONG KEE." and keeps it -- spec 5.4 preserves the raw capture. The key
     * must not: it is spec 8's grouping identity, nothing revisits it, and one
     * stop would leave the same stall showing as two rows in every merchant
     * total for ever.
     */
    @Test fun `a sentence stop does not make a second shop`() {
        assertEquals("THONG KEE", Merchant.clean("THONG KEE.", bundled).value)
        assertEquals("Thong Kee", Merchant.displayFor("THONG KEE.", bundled))
        assertEquals(
            Merchant.clean("THONG KEE", bundled).value,
            Merchant.clean("THONG KEE.", bundled).value,
        )
    }

    /**
     * A stop and a noise suffix stack in both orders, and only an interleaved
     * pass gets both.
     *
     * Stops first would leave " via DuitNow" on the second string below --
     * matched against "... DuitNow." it ends nothing. Suffixes first would
     * leave the stop on the first. The rails suffix is the one this matters
     * most for: it is why `merchant_display` read "Tenaga Nasional Via
     * Duitnow" on a device.
     */
    @Test fun `a stop and a suffix come off in either order`() {
        // The suffix carries stops of its own, so it must be taken whole.
        assertEquals("MACHINES", Merchant.clean("MACHINES SDN. BHD.", bundled).value)
        // The stop is outside the suffix, so it must come off before the
        // suffix can be seen at the end of the string.
        assertEquals("TENAGA NASIONAL", Merchant.clean("TENAGA NASIONAL via DuitNow.", bundled).value)
        assertEquals("Tenaga Nasional", Merchant.displayFor("TENAGA NASIONAL via DuitNow.", bundled))
    }

    @Test fun `strips trailing terminal code`() =
        assertEquals("RIDE", Merchant.clean("GRAB* RIDE-3KL", bundled).value)

    @Test fun `strips prefix even when followed by space`() =
        assertEquals("RESTORAN ALI", Merchant.clean("DUITNOWQR- RESTORAN ALI", bundled).value)

    // I8. Single-pass stripping never retested a suffix earlier in the list
    // after a later one was removed, so the same shop with its suffixes in
    // the other order cleaned to a different string — and spec 6.1 learns
    // merchant rules by exact normalized string, so one shop became two
    // learned merchants with two independent category decisions.
    @Test fun `stacked suffixes strip in either order`() {
        assertEquals("KK MART", Merchant.clean("KK MART SDN BHD MY", bundled).value)
        assertEquals("KK MART", Merchant.clean("KK MART MY SDN BHD", bundled).value)
        assertEquals("SHOPEE", Merchant.clean("SHOPEE MY SDN BHD", bundled).value)
        assertEquals("SHOPEE", Merchant.clean("SHOPEE SDN BHD MY", bundled).value)
    }

    @Test fun `title cases an all caps string`() =
        assertEquals("Restoran Ali", Merchant.displayFor("RESTORAN ALI", bundled))

    @Test fun `leaves mixed case alone`() =
        assertEquals("McDonald's", Merchant.displayFor("McDonald's", bundled))

    @Test fun `leaves a lower case string alone`() =
        assertEquals("foodpanda", Merchant.displayFor("foodpanda", bundled))

    // Spec 6.1 learns merchant rules by exact normalized string, so a
    // locale-sensitive case change would key the same shop differently on a
    // Turkish-locale device: "KEDAİ ALİ" instead of "KEDAI ALI".
    @Test fun `case folding is unaffected by a dotless i locale`() {
        ParseFixtures.withLocale("tr-TR") {
            assertEquals("KEDAI ALI", Merchant.clean("Kedai Ali", bundled).value)
            assertEquals("Restoran Ali", Merchant.displayFor("RESTORAN ALI", bundled))
        }
    }
}
