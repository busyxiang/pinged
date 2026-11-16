package my.pinged.ledger.bill

import java.time.LocalDate
import java.time.format.TextStyle
import my.pinged.data.BillSearchSql
import my.pinged.data.LocalDates
import my.pinged.data.dao.BillChoice
import my.pinged.data.dao.TxnDao
import my.pinged.parse.Amount
import my.pinged.ui.CALENDAR
import my.pinged.ui.money
import my.pinged.ui.theme.Separator

/**
 * The payback spec 7.4's chooser picks a bill for, as its strip quotes it
 * and as its offer is read: [name] and [amountSen] in [currency], arriving on
 * [day]. Only a bill in [currency] dated on or before [day] is offered.
 */
data class BillFor(val name: String, val amountSen: Long, val currency: String, val day: LocalDate)

/** What one read of the chooser found: unsearched, or a search's answer. */
sealed interface BillOffer {
    /**
     * Unsearched (`design/BillChooser.dc.html`): the bills of the last
     * [WINDOW_DAYS] days, those of the payback's amount or a whole multiple
     * of it first ([multiples]), then the [rest], each newest first. Ranking
     * only orders the list: nothing is picked for the user.
     */
    data class Recent(val multiples: List<BillChoice>, val rest: List<BillChoice>) : BillOffer

    /**
     * A search for [query] (`BillChooserSearch`), the whole history newest
     * first, one list. [capped] when it found more than it holds.
     */
    data class Found(val query: String, val bills: List<BillChoice>, val capped: Boolean) : BillOffer
}

/** One line of the chooser's list, in the order [chooserLines] gives. */
internal sealed interface ChooserLine {
    /** A group's heading; [first] is the list's first, which sits closer under the search field. */
    data class Heading(val text: String, val first: Boolean) : ChooserLine

    data class Bill(val bill: BillChoice) : ChooserLine

    /** What an empty list says in its place ([nothingLine]). */
    data class Nothing(val text: String) : ChooserLine
}

/**
 * The chooser's list for [offer], top to bottom: unsearched, the bills of
 * [payback]'s amount or a multiple under their heading, then the rest under
 * `LAST 60 DAYS`, a group with no bills drawn not at all; searched, one
 * heading and every bill found. An empty list is one line saying what was
 * looked through.
 */
internal fun chooserLines(offer: BillOffer, payback: BillFor): List<ChooserLine> = when (offer) {
    is BillOffer.Recent -> when {
        offer.multiples.isEmpty() && offer.rest.isEmpty() -> listOf(ChooserLine.Nothing(nothingLine(payback.day, query = null)))
        else -> buildList {
            if (offer.multiples.isNotEmpty()) {
                add(ChooserLine.Heading(multiplesHeading(payback.amountSen, payback.currency), first = true))
                offer.multiples.forEach { add(ChooserLine.Bill(it)) }
            }
            if (offer.rest.isNotEmpty()) {
                add(ChooserLine.Heading(LAST_60_DAYS, first = isEmpty()))
                offer.rest.forEach { add(ChooserLine.Bill(it)) }
            }
        }
    }
    is BillOffer.Found ->
        if (offer.bills.isEmpty()) {
            listOf(ChooserLine.Nothing(nothingLine(payback.day, query = offer.query)))
        } else {
            listOf(ChooserLine.Heading(foundHeading(offer), first = true)) + offer.bills.map { ChooserLine.Bill(it) }
        }
}

/** How far back the chooser looks before a search, the payback's own day included: spec 7.4's 60 days. */
internal const val WINDOW_DAYS = 60L

/**
 * The most bills one read holds. Sixty days of a busy ledger is about two
 * hundred expenses; a search for a common word can find thousands, and the
 * newest of them are the ones a payback is for.
 */
internal const val MOST_BILLS = 200

/**
 * Read the chooser's offer for [payback]: unsearched while [query] is
 * blank, else a search for it. Blocking: inside a `ScreenRead` body only.
 */
internal fun offerBills(dao: TxnDao, payback: BillFor, query: String): BillOffer {
    val to = LocalDates.of(payback.day)
    val asked = query.trim()
    if (asked.isEmpty()) {
        val from = LocalDates.of(payback.day.minusDays(WINDOW_DAYS))
        val bills = dao.billChoices(payback.currency, from, to, MOST_BILLS)
        val (multiples, rest) = bills.partition { isMultiple(it.txn.amountSen, payback.amountSen) }
        return BillOffer.Recent(multiples, rest)
    }
    val sql = BillSearchSql.search(asked, Amount.toSen(asked), payback.currency, to, MOST_BILLS + 1)
        ?: return BillOffer.Found(asked, emptyList(), capped = false)
    val found = dao.billSearchRaw(sql)
    return BillOffer.Found(asked, found.take(MOST_BILLS), capped = found.size > MOST_BILLS)
}

/**
 * Whether a bill of [billSen] is the payback's [paybackSen] or a whole
 * multiple of it: a dinner for three that one friend paid a third of.
 */
internal fun isMultiple(billSen: Long, paybackSen: Long): Boolean = paybackSen > 0 && billSen % paybackSen == 0L

/** The name a bill is written by: spec 6.4's resolved one, else as it arrived. */
internal fun billName(bill: BillChoice): String = bill.displayName ?: bill.txn.merchantRaw ?: NO_MERCHANT

/** `18 Sep`: a bill's day as spec 7.4 writes it after its name. */
internal fun shortDay(day: LocalDate): String =
    day.dayOfMonth.toString() + " " + day.month.getDisplayName(TextStyle.SHORT, CALENDAR)

/** A bill's own day, as a calendar date. */
internal fun billDay(bill: BillChoice): LocalDate = LocalDates.calendarDay(bill.txn.localDate)

/** `Yuen Kee · 18 Sep`: spec 7.4's way of writing a bill, its name and then its date. */
internal fun billLabel(bill: BillChoice): String = billName(bill) + Separator + shortDay(billDay(bill))

/** `FRI 18 SEP`: a chooser row's date, in the mono. */
internal fun chooserDay(day: LocalDate): String =
    (day.dayOfWeek.getDisplayName(TextStyle.SHORT, CALENDAR) + " " + shortDay(day)).uppercase(CALENDAR)

/** A figure as the chooser draws it, unsymbolled in ringgit: `96.00`, `−0.50`. */
internal fun figure(sen: Long, currency: String): String = money(sen, currency, symbol = false)

/**
 * `32.00 PAID BACK · YOUR SHARE 64.00`, once anything is paid back against
 * [bill]; null while nothing is. The share is below zero when friends
 * rounded up.
 */
internal fun paidBackLine(bill: BillChoice): String? =
    bill.paidBackSen.takeIf { it > 0 }?.let { paidBackLine(it, bill.txn.amountSen, bill.txn.currency) }

/** `32.00 PAID BACK · YOUR SHARE 64.00` for a bill of [amountSen] with [paidBackSen] paid back. */
internal fun paidBackLine(paidBackSen: Long, amountSen: Long, currency: String): String =
    figure(paidBackSen, currency) + " PAID BACK" + Separator + "YOUR SHARE " + figure(amountSen - paidBackSen, currency)

/**
 * Where the money will count once saved or accepted, and what the share
 * becomes (spec 9.2, 9.4): `Counts against Food & Drinks on 18 Sep. Your
 * share of Yuen Kee becomes RM32.00.` In Karla, since it carries names.
 *
 * [paybackSen] is the payback's amount as it will be written, and
 * [countedSen] what of it [bill]'s paid back already holds: 0 for money not
 * yet counted against it -- a card awaiting review, a new entry, a typed
 * payback moving from another bill -- else the amount stored.
 */
internal fun countsAgainstLine(bill: BillChoice, paybackSen: Long, countedSen: Long = 0L): String {
    val share = bill.shareSen + countedSen - paybackSen
    return "Counts against " + (bill.categoryName ?: NO_CATEGORY) + " on " + shortDay(billDay(bill)) +
        ". Your share of " + billName(bill) + " becomes " + money(share, bill.txn.currency, symbol = true) + "."
}

/** The chooser's title (spec 7.4). */
internal const val WHICH_BILL = "Which bill?"

/** The row that takes a pick off, always first, and the line under it. */
internal const val NO_BILL = "No bill"
internal const val NO_BILL_NOTE = "Count it as money received"

/** The search field's placeholder, and what TalkBack calls it. */
internal const val SEARCH_BILLS = "Search all bills, by name or amount"

/** The search field's clear X, as TalkBack names it. */
internal const val CLEAR_BILL_SEARCH = "Clear the search"

/** `32.00, OR A MULTIPLE OF IT`: the ranked group's heading. */
internal fun multiplesHeading(paybackSen: Long, currency: String): String = figure(paybackSen, currency) + ", OR A MULTIPLE OF IT"

/** The rest of the unsearched list's heading. */
internal const val LAST_60_DAYS = "LAST 60 DAYS"

/** A search's heading: `4 BILLS, ALL HISTORY`, or the newest it holds when there were more. */
internal fun foundHeading(found: BillOffer.Found): String = when {
    found.capped -> "THE NEWEST $MOST_BILLS, ALL HISTORY"
    found.bills.size == 1 -> "1 BILL, ALL HISTORY"
    else -> "${found.bills.size} BILLS, ALL HISTORY"
}

/** The small print under the list: what is offered, and that a tap is the whole of a pick. */
internal fun chooserFootnote(day: LocalDate, searched: Boolean): String =
    "ONLY BILLS THAT COUNT, ON OR BEFORE " + shortDay(day).uppercase(CALENDAR) + if (searched) "" else ". A TAP PICKS AND CLOSES"

/** The small print under an empty search: why a bill the user remembers may not be there. */
internal const val EMPTY_FOOTNOTE = "ONLY BILLS THAT COUNT CAN BE PICKED. ONE LEFT OUT OF SPENDING IS NOT OFFERED"

/**
 * The line in place of the list when it is empty (`BillChooserEmpty`): what
 * was searched and how far, so an empty list is never read as a broken one.
 */
internal fun nothingLine(day: LocalDate, query: String?): String =
    if (query != null) {
        "No bill matches “$query” on or before ${shortDay(day)}, in all of your history."
    } else {
        "No bill on or before ${shortDay(day)}, in the last 60 days."
    }

/** The `PAYS BACK` label: spec 7.4's name for the link. */
internal const val PAYS_BACK = "PAYS BACK"

/** `PAYS BACK` before a bill is picked, in Ink: an offer, not a question. */
internal const val NO_BILL_PICKED = "None"

private const val NO_MERCHANT = "No merchant"
private const val NO_CATEGORY = "Uncategorized"
