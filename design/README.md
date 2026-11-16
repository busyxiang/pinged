# Design

Static mockups for Pinged, Receipt direction. Approved design:
[`../docs/superpowers/specs/2026-09-02-pinged-design.md`](../docs/superpowers/specs/2026-09-02-pinged-design.md)

## What is here

`*.dc.html` — one file per artboard, each a self-contained 390x844 mockup.
These are the source of truth; the pages below are generated from them.

| Group | Files |
|---|---|
| First run | `Onboarding` `Stored` `Battery` `Sources` `Empty` |
| Every day | `Main` `MainScrolled` `MainScrolled320` `MainEnd` `Inbox` `InboxMoneyBack` `Detail` `DetailExcluded` `Cash` |
| Looking back | `Charts` `ChartsMonth` `Day` `Breakdown` `BreakdownUntrusted` `Search` |
| When it breaks | `Stopped` `Backup` `Settings` |
| While it loads | `LoadingSpending` `LoadingWaiting` `LoadingCharts` `LoadingInbox` `LoadingDetail` `LoadingDay` `LoadingList` `LoadingSettings` `LoadingSheet` `LoadingSearch` `LoadingBreakdown` |
| Sheets and lists | `Chooser` `Categories` `Category` `Merge` `Months` `EverythingElse` `MerchantSheet` `Export` `Wipe` `Teach` `PackImport` `Rules` `Unread` |
| Paybacks, proposed in #392 | `PaybackList` `PaybackList320` `PaybackListLeftOut` `DetailBill` `DetailBillExcluded` `NotPaymentBill` `DetailPayback` `DetailRefund` `DetailRefundFiled` `InboxPayback` `BillChooser` `BillChooserSearch` `BillChooserEmpty` `BreakdownPayback` `CashPayback` `CashPaybackEdit` `CashBill` `DeleteBill` |
| Rejected directions | `Quiet` `Warung` `Dark` |

`Cash` predates the shipped Paid / Received control (#355) and the screen's
header. `CashPayback`, `CashPaybackEdit` and `CashBill` are drawn to the
screen as built in `ManualEntryScreen.kt`.

`canvas.json` — artboard layout, two pages. Only consumed by the Claude
Design canvas editor, which needs Node or Bun to assemble and was not
available on the machine these were drawn on. Kept current so the canvas can
be seeded later without redrawing anything.

`screen-set.html`, `direction-study.html` — generated review pages,
published as Artifacts. Do not hand-edit; they are overwritten.

## Rebuilding the pages

```
python3 build-screen-set.py
python3 build-direction-study.py
```

Both read the `.dc.html` files in this directory, inline each artboard's
markup, and overwrite their output page. Captions, the design-system tables
and the launcher icon geometry all live in the build scripts.

## Previewing

```
python3 -m http.server 4321 --directory .
```

Then open `http://localhost:4321/screen-set.html`.

## Scenario

Every populated mockup is pinned to Wednesday 30 September 2026, month end:
RM2,847.30 across 112 captured transactions, RM100.00 of transfers excluded,
four items awaiting review. Category bars sum to the hero total. Keep new
mockups consistent with those figures.

Two boards are pinned elsewhere on purpose. `ChartsMonth` shows the same person two
weeks later, on Wednesday 14 October, because a month still in progress is its
subject. Its figures are its own: RM1,101.40 so far, which its grid days and
its category bars both sum to.

`Breakdown` opens the Food & Drinks bar on `Charts`: its lines, `No merchant`
included, sum to that bar's RM842.50, and every merchant but foodpanda stays
under Lotus's RM174.30 so that `WHO GOT IT` on `Charts` is still its top five.
Every Food & Drinks merchant another board draws has its line, and the
expanded one is `MainEnd`'s Restoran Nasi Kandar. Its one row awaiting review
is taken to be `Inbox`'s unnamed RM45.00 debit, filed under Food & Drinks.
`BreakdownUntrusted` is the same month with one day not captured, which the
scenario does not have; it differs from `Breakdown` only in grey, its one
explaining line and the board's note.

The payback boards are September's own and move no figure on another board.

- **Yuen Kee, 18 September.** A RM96.00 dinner for three. Mei Ling Tan and Arjun Nair each paid RM32.00 back on the 20th. A payback counts on its bill's day and adds nothing to a merchant's count, so Breakdown's Yuen Kee line of RM152.00 across 14 payments already holds the RM32.00 share. `BreakdownPayback` opens that line.
- **`BillChooser` and its two states.** Opened on the 20th, from Arjun's card before he was accepted, so Yuen Kee shows one payback.
- **Other bills it names.** foodpanda's 14 September RM64.00 bill, paid back RM64.50, sits inside its own line, and so does Restoran Sri Melayu's 8 September RM64.00 bill. Farid paid his half of the Sri Melayu bill by MAE on the 22nd (`PaybackList320`).
- **`DetailRefund`.** Ahmad Bin Abdullah's RM27.95 is one of `Main`'s 6 TO SORT.
- **`DetailRefundFiled`.** Breakdown's Domino's line, its one refund.
- **`PaybackListLeftOut`, `DetailBillExcluded` and `NotPaymentBill`.** The same month with Yuen Kee left out. They differ from the scenario only by what the bill and its paybacks move.
- **The typed-by-hand boards: `CashPayback`, `CashPaybackEdit`, `CashBill` and `DeleteBill`.** Pinned to August, since September has nothing typed by hand. Warung Pak Din on 15 August is a RM60.00 cash dinner for three. Daniel Lim paid RM20.00 back by MAE on the 16th, and Siti Hajar paid RM20.00 in cash on the 17th.

`EverythingElse` is pinned to August 2026. `Charts` draws September as six
bars, and folds only above six, so no September bar opens it. Its
four categories are August's own and sum to RM328.90.
