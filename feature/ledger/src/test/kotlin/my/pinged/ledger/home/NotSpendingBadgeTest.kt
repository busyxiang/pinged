package my.pinged.ledger.home

import my.pinged.parse.ExclusionReason
import my.pinged.ui.theme.Separator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An excluded row's badge for every reason. Over `entries`, so a new constant
 * fails here until it is given words, rather than reaching the screen as its
 * name the way `USER` did (#214).
 */
class NotSpendingBadgeTest {

    @Test fun everyReasonReadsAsWordsTheArtboardsUse() {
        assertEquals(
            "A reason's badge is not the copy the artboards give it, or a new " +
                "reason was added without any",
            mapOf(
                ExclusionReason.TRANSFER to "NOT SPENDING${Separator}TRANSFER",
                ExclusionReason.CARD_PAYMENT to "NOT SPENDING${Separator}CARD PAYMENT",
                ExclusionReason.ATM_WITHDRAWAL to "NOT SPENDING${Separator}ATM WITHDRAWAL",
                ExclusionReason.USER to "NOT SPENDING${Separator}YOU LEFT IT OUT",
            ),
            ExclusionReason.entries.associateWith(::notSpendingBadge),
        )
    }

    @Test fun aRowWithNoRecordedReasonSaysSo() {
        assertEquals("NOT SPENDING${Separator}REASON NOT RECORDED", notSpendingBadge(null))
    }
}
