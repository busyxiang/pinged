package my.pinged.data

import my.pinged.data.entity.CaptureSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sources line's rules (CONTEXT.md, Read source, Unreadable source,
 * Sources line; #365): which apps a month's line names and how it says so.
 * Charts and Spending both draw [SourcesLine], so a case wrong here is wrong
 * on both. What the inputs are, read from the database, is
 * `SourcesLineReadTest`'s.
 *
 * Every expected line is written out in full, not built from the names.
 */
class SourcesLineTest {

    private fun on(pkg: String, label: String) = CaptureSource(pkg, label, enabled = true, firstSeenAt = 0L)
    private fun off(pkg: String, label: String) = CaptureSource(pkg, label, enabled = false, firstSeenAt = 0L)

    private val tng = on(TNG, "Touch 'n Go")
    private val mae = on(MAE, "MAE")
    private val grab = on(GRAB, "GrabPay")
    private val shopee = on(SHOPEE, "ShopeePay")

    private fun line(
        sources: List<CaptureSource>,
        rulePackages: Set<String> = PACK,
        paidFrom: Set<String> = emptySet(),
        byHand: Boolean = false,
    ) = SourcesLine.of(sources, rulePackages, paidFrom, byHand).text

    // ---- FROM -----------------------------------------------------------

    @Test fun twoReadSourcesAreNamedAfterFrom() {
        assertEquals(
            "With only the two apps the pack reads switched on, the line did not name exactly those two",
            "FROM MAE AND TOUCH 'N GO",
            line(listOf(tng, mae)),
        )
    }

    @Test fun threeOrMoreAreListedWithCommasAndAFinalAnd() {
        assertEquals(
            "FROM GRABPAY, MAE AND TOUCH 'N GO",
            line(listOf(tng, mae, grab), rulePackages = PACK + GRAB),
        )
    }

    @Test fun aReadSourceWithNoTransactionInTheMonthIsStillNamed() {
        assertEquals(
            "An enabled app a rule reads was left out because it paid nothing this " +
                "month, so the line stops saying which apps the total could have come from",
            "FROM MAE AND TOUCH 'N GO",
            line(listOf(tng, mae), paidFrom = setOf(TNG)),
        )
    }

    @Test fun aDisabledAppIsNamedOnAMonthItPaidIn() {
        assertEquals(
            "An app switched off since was dropped from a month whose total it is in",
            "FROM GRABPAY, MAE AND TOUCH 'N GO",
            line(listOf(tng, mae, off(GRAB, "GrabPay")), paidFrom = setOf(GRAB)),
        )
    }

    @Test fun aDisabledAppIsNotNamedOnAMonthItDidNotPayIn() {
        assertEquals(
            "An app switched off was named on a month with none of its transactions",
            "FROM MAE AND TOUCH 'N GO",
            line(listOf(tng, mae, off(GRAB, "GrabPay"))),
        )
    }

    @Test fun aDisabledReadSourceIsNotAReadSource() {
        assertEquals(
            "An app the pack reads was named although the user switched it off",
            "FROM TOUCH 'N GO",
            line(listOf(tng, off(MAE, "MAE"))),
        )
    }

    @Test fun anAppWithNoLabelIsNamedByItsPackage() {
        assertEquals(
            "An app with no capture_source row, or a blank label, was named by nothing",
            "FROM COM.GRABTAXI.PASSENGER AND TOUCH 'N GO",
            line(listOf(tng), paidFrom = setOf(GRAB)),
        )
        assertEquals(
            "FROM COM.GRABTAXI.PASSENGER AND TOUCH 'N GO",
            line(listOf(tng, off(GRAB, " ")), paidFrom = setOf(GRAB)),
        )
    }

    @Test fun aPackAppIsNamedByThePacksLabel() {
        assertEquals(
            "A pack app was named by this phone's label, so Charts and Capture sources' " +
                "sentence (#366) name one app two ways",
            "FROM MAYBANK MAE AND TOUCH 'N GO EWALLET",
            SourcesLine.of(
                listOf(on(TNG, "TNG eWallet"), on(MAE, "MAE")),
                PACK,
                emptySet(),
                byHand = false,
                packLabels = mapOf(TNG to "Touch 'n Go eWallet", MAE to "Maybank MAE"),
            ).text,
        )
    }

    // ---- BY HAND --------------------------------------------------------

    @Test fun aMonthWithAManualEntryEndsAndByHand() {
        assertEquals(
            "FROM MAE AND TOUCH 'N GO, AND BY HAND",
            line(listOf(tng, mae), byHand = true),
        )
    }

    @Test fun aMonthWithoutOneSaysNothingOfHand() {
        assertEquals("FROM MAE AND TOUCH 'N GO", line(listOf(tng, mae), byHand = false))
    }

    // ---- nothing to name ------------------------------------------------

    @Test fun noReadSourceAndNoManualEntryIsNoAppIsReadYet() {
        assertEquals("NO APP IS READ YET", line(emptyList()))
        assertEquals(
            "A source switched off is not read, so a month it did not pay in names nothing",
            "NO APP IS READ YET",
            line(listOf(off(TNG, "Touch 'n Go"))),
        )
    }

    @Test fun noReadSourceWithAManualEntryIsOnlyByHand() {
        assertEquals("ONLY BY HAND", line(emptyList(), byHand = true))
    }

    // ---- unreadable -----------------------------------------------------

    @Test fun anEnabledAppNoRuleReadsIsWarnedOf() {
        assertEquals(
            "FROM MAE AND TOUCH 'N GO. GRABPAY IS ON BUT PINGED CANNOT READ IT YET",
            line(listOf(tng, mae, grab)),
        )
    }

    @Test fun twoUnreadableSourcesAreWarnedOfTogether() {
        assertEquals(
            "FROM MAE AND TOUCH 'N GO. GRABPAY AND SHOPEEPAY ARE ON BUT PINGED CANNOT READ THEM YET",
            line(listOf(tng, mae, grab, shopee)),
        )
    }

    @Test fun disablingTheUnreadableSourceRemovesTheWarning() {
        assertEquals(
            "FROM MAE AND TOUCH 'N GO",
            line(listOf(tng, mae, off(GRAB, "GrabPay"))),
        )
    }

    @Test fun oneTaughtMessageMakesAnAppARead() {
        assertEquals(
            "An app the user taught a message from was still called unreadable",
            "FROM GRABPAY, MAE AND TOUCH 'N GO",
            line(listOf(tng, mae, grab), rulePackages = PACK + GRAB),
        )
    }

    @Test fun anUnreadableSourceThatPaidIsInBothSentences() {
        assertEquals(
            "An unreadable app with counted transactions this month (a taught rule " +
                "since deleted) must be in the total's list and in the warning",
            "FROM GRABPAY, MAE AND TOUCH 'N GO. GRABPAY IS ON BUT PINGED CANNOT READ IT YET",
            line(listOf(tng, mae, grab), paidFrom = setOf(GRAB)),
        )
    }

    @Test fun anUnreadableSourceAloneIsWarnedOfAfterNoAppIsRead() {
        assertEquals(
            "NO APP IS READ YET. GRABPAY IS ON BUT PINGED CANNOT READ IT YET",
            line(listOf(grab)),
        )
        assertEquals(
            "ONLY BY HAND. GRABPAY IS ON BUT PINGED CANNOT READ IT YET",
            line(listOf(grab), byHand = true),
        )
    }

    // ---- the structure #368 and the screens draw from ----------------------

    @Test fun theSegmentsSpellTheTextAndMarkEachUnreadableName() {
        val line = SourcesLine.of(listOf(tng, mae, grab), PACK, paidFrom = setOf(GRAB), byHand = false)
        assertEquals(line.text, line.segments.joinToString("") { it.text })
        assertEquals(
            "The warning's names are what #368 makes tap targets, so each must carry its package",
            listOf(NamedSource(GRAB, "GRABPAY")),
            line.segments.filterIsInstance<SourcesLine.Segment.App>().filter { it.unreadable }.map { it.source },
        )
        assertEquals(listOf(NamedSource(GRAB, "GRABPAY"), NamedSource(MAE, "MAE"), NamedSource(TNG, "TOUCH 'N GO")), line.from)
        assertEquals(listOf(NamedSource(GRAB, "GRABPAY")), line.unreadable)
    }

    @Test fun nothingReadIsItsOwnSegment() {
        assertEquals(
            "NO APP IS READ YET opens Capture sources, so the screen has to find it",
            listOf(SourcesLine.Segment.NoAppRead),
            SourcesLine.of(emptyList(), PACK, emptySet(), byHand = false).segments,
        )
    }

    private companion object {
        const val TNG = "my.com.tngdigital.ewallet"
        const val MAE = "com.maybank2u.life"
        const val GRAB = "com.grabtaxi.passenger"
        const val SHOPEE = "com.shopeepay.my"
        val PACK = setOf(TNG, MAE)
    }
}
