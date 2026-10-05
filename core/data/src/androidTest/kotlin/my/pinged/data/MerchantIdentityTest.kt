package my.pinged.data

import android.database.sqlite.SQLiteConstraintException
import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import my.pinged.data.dao.FeedRow
import my.pinged.data.dao.MerchantTotal
import my.pinged.data.entity.MerchantAlias
import my.pinged.data.entity.TxnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 6.4: one shop paid under two `merchant_key`s, made one by the user.
 *
 * The pair is the one measured on a device: MAE Scan & Pay sends the name with
 * its spaces stripped, TnG DuitNow sends the registered name.
 */
@RunWith(AndroidJUnit4::class)
class MerchantIdentityTest {
    private lateinit var db: PingedDatabase

    /** 2026-09-07T00:00:00Z. */
    private val day = 1_788_739_200_000L
    private val sept = LocalDate(20260901) to LocalDate(20260930)

    private val scanAndPay = "YUENKEEHOMETOWNCAFE"
    private val duitNow = "RESTORAN YUEN KEE HOME TOWN CAFE"

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val merchants get() = db.merchantIdentityDao()

    private fun pay(key: String, display: String, amountSen: Long, at: Long = day, state: TxnState = TxnState.COMMITTED) =
        db.txnDao().insert(
            sampleTxn(
                occurredAt = at,
                amountSen = amountSen,
                categoryId = db.categoryDao().requireUncategorizedId(),
                state = state,
            ).copy(merchantRaw = key, merchantDisplay = display, merchantKey = key),
        )

    private fun yuenKee() {
        pay(scanAndPay, "Yuenkeehometowncafe", 1_250L, at = day)
        pay(scanAndPay, "Yuenkeehometowncafe", 980L, at = day + 1_000L)
        pay(duitNow, "Restoran Yuen Kee Home Town Cafe", 1_500L, at = day + 2_000L)
    }

    private fun ranking(): List<MerchantTotal> = db.txnDao().merchantTotals(sept.first, sept.second, limit = 10)

    private fun feed(): List<FeedRow> = runBlocking {
        val page = db.txnDao().feed().load(PagingSource.LoadParams.Refresh(null, 100, false))
        (page as PagingSource.LoadResult.Page).data
    }

    @Test fun theRankingSplitsTheShopUntilTheUserMergesIt() {
        yuenKee()
        assertEquals(
            "Unmerged, the two rails are two merchants: the fixture is not the case it claims",
            2,
            ranking().size,
        )

        assertTrue(merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe"))

        val merged = ranking()
        assertEquals("Merged, the shop must rank once: $merged", 1, merged.size)
        assertEquals(duitNow, merged.single().identityKey)
        assertEquals(3_730L, merged.single().netSen)
        assertEquals(3, merged.single().txnCount)
        assertEquals("Restoran Yuen Kee Home Town Cafe", merged.single().displayName)
    }

    @Test fun separatingRestoresTheSplitExactly() {
        yuenKee()
        val before = ranking().sortedBy { it.identityKey }
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")

        assertEquals(1, merchants.separate(scanAndPay))

        assertEquals(
            before.map { it.identityKey to it.netSen },
            ranking().sortedBy { it.identityKey }.map { it.identityKey to it.netSen },
        )
    }

    @Test fun mergingNeverWritesATransaction() {
        yuenKee()
        val before = db.txnDao().pageFrom(0L, 100)
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")
        assertEquals(before, db.txnDao().pageFrom(0L, 100))
    }

    @Test fun aMergedRowShowsTheTargetsName() {
        yuenKee()
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")

        val rows = feed()
        assertEquals(setOf(duitNow), rows.map { it.identityKey }.toSet())
        assertEquals(setOf("Restoran Yuen Kee Home Town Cafe"), rows.map { it.displayName }.toSet())
    }

    @Test fun aMergeOfAMerchantCarriesWhatWasMergedIntoIt() {
        pay("A ONE", "A One", 100L)
        pay("B ONE", "B One", 100L)
        pay("C ONE", "C One", 100L)

        merchants.merge(source = "A ONE", target = "B ONE", targetName = "B One")
        merchants.merge(source = "B ONE", target = "C ONE", targetName = "C One")

        assertEquals("C ONE", merchants.canonicalOf("A ONE"))
        assertEquals("C ONE", merchants.canonicalOf("B ONE"))
        assertEquals(0, merchants.chainedAliasCount())
        assertEquals(listOf("C ONE"), ranking().map { it.identityKey })
    }

    @Test fun mergingIntoAMergedMerchantResolvesToItsCanonical() {
        pay("A ONE", "A One", 100L)
        pay("B ONE", "B One", 100L)
        pay("C ONE", "C One", 100L)

        merchants.merge(source = "B ONE", target = "C ONE", targetName = "C One")
        merchants.merge(source = "A ONE", target = "B ONE", targetName = "C One")

        assertEquals("C ONE", merchants.canonicalOf("A ONE"))
        assertEquals(0, merchants.chainedAliasCount())
    }

    @Test fun mergingAMerchantWithItselfWritesNothing() {
        pay("A ONE", "A One", 100L)
        pay("B ONE", "B One", 100L)
        merchants.merge(source = "A ONE", target = "B ONE", targetName = "B One")

        assertFalse(merchants.merge(source = "B ONE", target = "A ONE", targetName = "B One"))
        assertEquals(1, merchants.countAliases())
    }

    @Test fun theAliasTableRefusesASecondCanonicalForOneKey() {
        merchants.insertAlias(MerchantAlias("A ONE", "B ONE"))
        try {
            merchants.insertAlias(MerchantAlias("A ONE", "C ONE"))
            throw AssertionError("A key was given two canonical merchants")
        } catch (expected: SQLiteConstraintException) {
        }
    }

    // Spec 6.4's resolution at read time: a rename touches no row and stage two
    // looks nothing up, so a payment captured after it is named all the same.
    @Test fun aCaptureAfterARenameShowsTheNewName() {
        pay(scanAndPay, "Yuenkeehometowncafe", 1_250L)
        merchants.rename(scanAndPay, "Yuen Kee", derivedName = "Yuenkeehometowncafe")

        pay(scanAndPay, "Yuenkeehometowncafe", 980L, at = day + 1_000L)

        assertEquals(listOf("Yuen Kee", "Yuen Kee"), feed().map { it.displayName })
        assertEquals("Yuen Kee", ranking().single().displayName)
    }

    @Test fun renamingToTheDerivedNameOrToNothingStoresNothing() {
        pay(scanAndPay, "Yuenkeehometowncafe", 1_250L)
        merchants.rename(scanAndPay, "Yuen Kee", derivedName = "Yuenkeehometowncafe")

        merchants.rename(scanAndPay, "  Yuenkeehometowncafe ", derivedName = "Yuenkeehometowncafe")
        assertNull(merchants.nameOf(scanAndPay))

        merchants.rename(scanAndPay, "Yuen Kee", derivedName = "Yuenkeehometowncafe")
        merchants.rename(scanAndPay, "   ", derivedName = "Yuenkeehometowncafe")
        assertNull(merchants.nameOf(scanAndPay))
        assertEquals(listOf("Yuenkeehometowncafe"), feed().map { it.displayName })
    }

    @Test fun renamingAMergedKeyNamesItsCanonical() {
        yuenKee()
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")

        merchants.rename(scanAndPay, "Yuen Kee", derivedName = "Restoran Yuen Kee Home Town Cafe")

        assertEquals("Yuen Kee", merchants.nameOf(duitNow))
        assertNull(merchants.nameOf(scanAndPay))
    }

    // Spec 6.4's "the split comes back exactly as it was" includes the name the
    // user had given the merchant that was merged away.
    @Test fun separatingRestoresTheNameTheMergedMerchantHadBefore() {
        yuenKee()
        merchants.rename(scanAndPay, "Yuen Kee (QR)", derivedName = "Yuenkeehometowncafe")
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")
        assertEquals(setOf("Restoran Yuen Kee Home Town Cafe"), feed().map { it.displayName }.toSet())

        merchants.separate(scanAndPay)

        assertEquals(
            listOf("Restoran Yuen Kee Home Town Cafe", "Yuen Kee (QR)", "Yuen Kee (QR)"),
            feed().map { it.displayName },
        )
    }

    // The feed falls back to each row's own name, not the merchant's, so a
    // merged merchant left without a stored name shows its halves apart again.
    @Test fun renamingAMergedMerchantToItsDerivedNameKeepsEveryRowUnderIt() {
        yuenKee()
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")
        merchants.rename(duitNow, "Yuen Kee", derivedName = "Restoran Yuen Kee Home Town Cafe")

        merchants.rename(duitNow, "Restoran Yuen Kee Home Town Cafe", derivedName = "Restoran Yuen Kee Home Town Cafe")
        assertEquals(setOf("Restoran Yuen Kee Home Town Cafe"), feed().map { it.displayName }.toSet())

        merchants.rename(duitNow, "  ", derivedName = "Restoran Yuen Kee Home Town Cafe")
        assertEquals(setOf("Restoran Yuen Kee Home Town Cafe"), feed().map { it.displayName }.toSet())
    }

    @Test fun theChoicesListEachMerchantOnceWithItsCount() {
        yuenKee()
        pay("SPADES BAKERY", "Spades Bakery", 700L, at = day + 5_000L)
        pay("REJECTED SHOP", "Rejected Shop", 700L, state = TxnState.REJECTED)
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")

        val choices = merchants.choices()
        assertEquals(listOf("SPADES BAKERY", duitNow), choices.map { it.identityKey })
        assertEquals(listOf(1, 3), choices.map { it.txnCount })
        assertEquals("Restoran Yuen Kee Home Town Cafe", choices[1].displayName)
    }

    @Test fun theMembersOfAMergedMerchantAreListedByTheirOwnName() {
        yuenKee()
        merchants.merge(source = scanAndPay, target = duitNow, targetName = "Restoran Yuen Kee Home Town Cafe")

        val members = merchants.membersOf(duitNow)
        assertEquals(listOf(scanAndPay), members.map { it.merchantKey })
        assertEquals(listOf("Yuenkeehometowncafe"), members.map { it.derivedName })
    }
}
