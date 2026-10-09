# Changelog

Written for someone installing the APK, not for someone reading the diff.
An entry should say what the release does for you and what it still cannot do;
`release-please` drafts each one from the commits, and the release PR is where
it gets edited into that before anything ships.

## [0.5.0](https://github.com/busyxiang/pinged/compare/v0.4.0...v0.5.0) (2026-10-09)


### Features

* learn merchant categories, ship a dictionary, fix past payments from the chooser ([#53](https://github.com/busyxiang/pinged/issues/53)) ([db04f0e](https://github.com/busyxiang/pinged/commit/db04f0e7e9f8824fa6ccff50b6339d8bbb040f3a))
* let the backup nudge be dismissed for a week ([8ab81ce](https://github.com/busyxiang/pinged/commit/8ab81ce2958b306bc4c91d9f28d79f188e3977eb))
* let the backup nudge be dismissed for a week ([9c0acb6](https://github.com/busyxiang/pinged/commit/9c0acb665317e66b709772d4cc11df79e4f87c94))
* make settings a bottom tab, as the design draws it ([#55](https://github.com/busyxiang/pinged/issues/55)) ([595b1a2](https://github.com/busyxiang/pinged/commit/595b1a21c4de1b4dd91e33d3fa698de805aa50de))
* merge one shop's two merchant keys, and rename a merchant ([4432ba1](https://github.com/busyxiang/pinged/commit/4432ba1651c39b177dfe088215e6c3b3317e5748))
* merge one shop's two merchant keys, and rename a merchant ([#4](https://github.com/busyxiang/pinged/issues/4)) ([0a3aa12](https://github.com/busyxiang/pinged/commit/0a3aa122a04a22bce9674130902779797686bb00))
* offer corrections when a newer pack reads a committed payment differently ([4742f2b](https://github.com/busyxiang/pinged/commit/4742f2b094b53920e3ffd08793e2317e3be1207d))
* offer corrections when a newer pack reads a committed payment differently ([6cd6ea3](https://github.com/busyxiang/pinged/commit/6cd6ea3169e6d6812b4becbc854687e25efd8a61))


### Bug Fixes

* capitalise a merchant's first letter, not its first character ([#54](https://github.com/busyxiang/pinged/issues/54)) ([a620ccb](https://github.com/busyxiang/pinged/commit/a620ccb58b94d395bbafce704886e9a33d0e6e6d))
* keep merged and renamed merchants together when a pack re-keys their rows ([#36](https://github.com/busyxiang/pinged/issues/36)) ([e7bbc2a](https://github.com/busyxiang/pinged/commit/e7bbc2a9ac6ccad4cbdbddd1750729ef1155a91e))
* merchant keys and five unread payment wordings, from a device export ([#19](https://github.com/busyxiang/pinged/issues/19)) ([64a1b87](https://github.com/busyxiang/pinged/commit/64a1b8701e771a8b03f69fb0c92a3c438e5ecce0))
* show a payment captured while the ledger's page is loading ([#17](https://github.com/busyxiang/pinged/issues/17)) ([5dfe099](https://github.com/busyxiang/pinged/commit/5dfe0992d3f447747e8547620c2e01dfcfd4e4a7))

## [0.4.0](https://github.com/busyxiang/pinged/compare/v0.3.0...v0.4.0) (2026-10-04)


### Features

* export, restore, delete, check and rescue the ledger from settings ([#10](https://github.com/busyxiang/pinged/issues/10)) ([f79322b](https://github.com/busyxiang/pinged/commit/f79322b80f34ea9bd8d8d35574e4172797f30dc1))


### Bug Fixes

* shrink the release APK from 18.61 MB to 5.30 MB ([#12](https://github.com/busyxiang/pinged/issues/12)) ([7e45a81](https://github.com/busyxiang/pinged/commit/7e45a8132905f107f052f8f0caee3aa0207541b2))

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
