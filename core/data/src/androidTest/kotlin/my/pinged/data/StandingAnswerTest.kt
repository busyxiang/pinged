package my.pinged.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.StandingAnswer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 7.3's standing answers, against the real encrypted database. */
@RunWith(AndroidJUnit4::class)
class StandingAnswerTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() { db = freshDatabase() }
    @After fun tearDown() { db.close() }

    private val answers get() = db.standingAnswerDao()

    private fun answer(rule: String = RELOAD, pkg: String = TNG, key: String? = null, at: Long = 1_000L) =
        StandingAnswer(matchedRuleId = rule, sourcePackage = pkg, merchantKey = key, createdAt = at)

    @Test fun aSecondAnswerOfOneKeyIsNotStoredWithACounterpartyOrWithout() {
        assertNotNull(answers.answer(answer(key = null)))
        assertNotNull(answers.answer(answer(rule = SENT, pkg = MAE, key = "RAY FOO")))

        assertNull("A second null-key answer was stored", answers.answer(answer(key = null, at = 2_000L)))
        assertNull("A second answer to one counterparty was stored", answers.answer(answer(rule = SENT, pkg = MAE, key = "RAY FOO")))
        assertEquals(2, answers.countAll())
    }

    /** Rule, app and counterparty together: any one different is another answer, and none matches none only. */
    @Test fun aLookupMatchesTheExactKeyAndNoneOnlyNone() {
        val id = answers.answer(answer(rule = SENT, pkg = MAE, key = "RAY FOO"))!!
        val reload = answers.answer(answer(key = null))!!

        assertEquals(id, answers.idFor(SENT, MAE, "RAY FOO"))
        assertEquals(reload, answers.idFor(RELOAD, TNG, null))
        assertNull("another counterparty", answers.idFor(SENT, MAE, "ALI"))
        assertNull("no counterparty, against one with", answers.idFor(SENT, MAE, null))
        assertNull("a counterparty, against none", answers.idFor(RELOAD, TNG, "RAY FOO"))
        assertNull("the rule's next version", answers.idFor("mae-transferred-v2", MAE, "RAY FOO"))
        assertNull("another app", answers.idFor(SENT, TNG, "RAY FOO"))
    }

    /** What the import's check reads: the unique index refuses one duplicate and lets the other through. */
    @Test fun theUniqueIndexRefusesADuplicateCounterpartyAndDuplicateCountFindsANullOne() {
        try {
            answers.insertAll(listOf(answer(rule = SENT, pkg = MAE, key = "RAY FOO"), answer(rule = SENT, pkg = MAE, key = "RAY FOO")))
            fail("Two answers to one counterparty were stored")
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(0, answers.duplicateCount())

        answers.insertAll(listOf(answer(key = null), answer(key = null)))

        assertEquals("SQLite's unique index let two null keys through, as it does", 2, answers.countAll())
        assertEquals(1, answers.duplicateCount())
    }

    /**
     * The settings list's line: the app's label, and the counterparty by the
     * exact key's own name -- the user's rename of it, else the latest
     * transaction's -- never the identity it is merged into.
     */
    @Test fun theListNamesTheAppAndTheExactKeysCounterparty() {
        db.captureSourceDao().insertIfNew(CaptureSource(pkg = MAE, label = "Maybank MAE", firstSeenAt = 1L))
        val uncategorized = db.categoryDao().requireUncategorizedId()
        listOf("Ray Old" to 1_000L, "Ray Foo" to 2_000L).forEach { (shown, at) ->
            db.txnDao().insert(sampleTxn(occurredAt = at, categoryId = uncategorized).copy(merchantDisplay = shown, merchantKey = "RAY FOO"))
        }
        answers.answer(answer(rule = SENT, pkg = MAE, key = "RAY FOO", at = 1L))
        answers.answer(answer(rule = BILL, pkg = MAE, key = "MAYBANK MASTER CARD", at = 2L))
        answers.answer(answer(rule = SENT, pkg = MAE, key = "NOBODY SEEN", at = 3L))
        answers.answer(answer(key = null, at = 4L))
        db.merchantIdentityDao().setName("MAYBANK MASTER CARD", "Maybank Master Card")
        db.merchantIdentityDao().merge(source = "RAY FOO", target = "MAYBANK MASTER CARD", targetName = "Maybank Master Card")

        assertEquals(
            listOf(
                Triple(TNG, null, null),
                Triple(MAE, "Maybank MAE", "NOBODY SEEN"),
                Triple(MAE, "Maybank MAE", "Maybank Master Card"),
                Triple(MAE, "Maybank MAE", "Ray Foo"),
            ),
            answers.rows().map { Triple(it.sourcePackage, it.label, it.counterparty) },
        )
    }

    @Test fun deleteForgetsOneAnswer() {
        val reload = answers.answer(answer(key = null))!!
        answers.answer(answer(rule = SENT, pkg = MAE, key = "RAY FOO"))

        assertEquals(1, answers.delete(reload))

        assertNull(answers.idFor(RELOAD, TNG, null))
        assertEquals(1, answers.countAll())
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val RELOAD = "tng-reload-v1"
        const val SENT = "mae-transferred-v1"
        const val BILL = "mae-card-bill-v1"
    }
}
