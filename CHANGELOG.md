# Changelog

Written for someone installing the APK, not for someone reading the diff.
An entry should say what the release does for you and what it still cannot do;
`release-please` drafts each one from the commits, and the release PR is where
it gets edited into that before anything ships.

## [0.3.0](https://github.com/busyxiang/pinged/compare/v0.2.1...v0.3.0) (2026-09-18)


### Features

* re-read the captures an older pack could not understand ([#6](https://github.com/busyxiang/pinged/issues/6)) ([09ebb76](https://github.com/busyxiang/pinged/commit/09ebb764c72f1a28f8ae3caeae5c9881b609c6de))

## 0.2.1 (2026-09-15)

Pinged reads the notifications your banks actually send.

v0.2.0 showed an empty ledger. It had been capturing payments the whole time
and could not understand any of them: the rules were written from imagined
wordings, and the tests were written to match the rules, so everything passed
and nothing worked. Four real notifications from a Malaysian phone now drive
the parser -- DuitNow, Maybank card purchases, Maybank Scan & Pay, and Touch
'n Go's own wallet wording, which differs from DuitNow's by one preposition.

Payments captured before this version stay unread. The decline guards are
reasoned from the wordings banks use for successful payments; no declined
payment has been observed.

## 0.2.0 (2026-09-14)

The ledger milestone: Pinged shows the money it captured. A feed grouped by
day with subtotals, a pinned month summary with its top three categories, and
a one-tap category chip on anything the app could not file.

## 0.1.1 (2026-09-07)

The app starts.

## 0.1.0 (2026-09-06)

The capture milestone. Notifications are captured into an encrypted ledger.
Nothing is shown yet but the list of apps the app is allowed to read.
