package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameShopTest {

    // Spec 6.4's measured case: Scan & Pay strips the spaces, DuitNow does not.
    @Test fun `a space-stripped key is suggested for the spelled-out one`() {
        assertTrue(SameShop.likely("YUENKEEHOMETOWNCAFE", "RESTORAN YUEN KEE HOME TOWN CAFE"))
        assertTrue(SameShop.likely("RESTORAN YUEN KEE HOME TOWN CAFE", "YUENKEEHOMETOWNCAFE"))
    }

    @Test fun `the rest of the export's stripped keys find their spelled-out forms`() {
        assertTrue(SameShop.likely("KOKYIMKEI", "KOK YIM KEI"))
        assertTrue(SameShop.likely("POPUPKITCHENENTERPRISE", "POP UP KITCHEN"))
    }

    // Eight is the shorter key's floor, so a seven-character one is never offered:
    // inside a long name it matches by chance as often as by identity.
    @Test fun `a key shorter than eight characters suggests nothing`() {
        assertFalse(SameShop.likely("KOPITIA", "KOPITIAM SRI PETALING"))
        assertTrue(SameShop.likely("KOPITIAM", "KOPITIAM SRI PETALING"))
    }

    @Test fun `unrelated shops are not suggested`() {
        assertFalse(SameShop.likely("YUENKEEHOMETOWNCAFE", "SPADES BAKERY 3"))
    }

    @Test fun `a key is not its own suggestion`() {
        assertFalse(SameShop.likely("MR KOPI SETAPAK", "MR KOPI SETAPAK"))
    }

    // Unicode letters count as letters on both runtimes: Char.isLetterOrDigit is
    // Character's table, not a regex class, so it reads the same on ICU.
    @Test fun `non-latin letters are kept rather than stripped to nothing`() {
        assertTrue(SameShop.likely("鸿记茶餐室烧腊饭店", "鸿记 茶餐室 烧腊饭店 KL"))
        assertFalse(SameShop.likely("鸿记茶餐室烧腊饭店", "大众书局"))
    }

    @Test fun `suggestions come first and keep their order, as does the rest`() {
        val candidates = listOf(
            "SPADES BAKERY 3",
            "RESTORAN YUEN KEE HOME TOWN CAFE",
            "MR KOPI",
            "YUEN KEE HOME TOWN CAFE SS2",
        )
        assertEquals(
            listOf(
                "RESTORAN YUEN KEE HOME TOWN CAFE",
                "YUEN KEE HOME TOWN CAFE SS2",
                "SPADES BAKERY 3",
                "MR KOPI",
            ),
            SameShop.suggestionsFirst("YUENKEEHOMETOWNCAFE", candidates) { it },
        )
    }
}
