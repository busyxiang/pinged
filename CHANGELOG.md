# Changelog

Written for someone installing the APK, not for someone reading the diff.
An entry should say what the release does for you and what it still cannot do;
`release-please` drafts each one from the commits, and the release PR is where
it gets edited into that before anything ships.

## [0.9.0](https://github.com/busyxiang/pinged/compare/v0.8.0...v0.9.0) (2026-10-15)


### Features

* show when capture last heard anything, flag quiet sources, and alert daily ([#162](https://github.com/busyxiang/pinged/issues/162)) ([c79dac4](https://github.com/busyxiang/pinged/commit/c79dac47586de3eb2d690d4c4068506e82deab95))


### Bug Fixes

* count a payment captured while the ledger is open in its total ([#176](https://github.com/busyxiang/pinged/issues/176)) ([14db8dc](https://github.com/busyxiang/pinged/commit/14db8dc157c315fdcc4611993ba14f72c273f0ce)), closes [#175](https://github.com/busyxiang/pinged/issues/175)
* draw sheets, the chooser's Save and its switch as the artboards do, not Material's defaults ([#177](https://github.com/busyxiang/pinged/issues/177)) ([54ce9ff](https://github.com/busyxiang/pinged/commit/54ce9ffb56db9c3106be52327238c752984fc477)), closes [#164](https://github.com/busyxiang/pinged/issues/164)
* give each typeface its spec 8 job on the inbox, ledger and buttons ([#180](https://github.com/busyxiang/pinged/issues/180)) ([9e0eb90](https://github.com/busyxiang/pinged/commit/9e0eb901e61603a769ce1c995522319f18c8fe25)), closes [#166](https://github.com/busyxiang/pinged/issues/166)
* keep the ledger's place when a payment's category is changed ([#172](https://github.com/busyxiang/pinged/issues/172)) ([f5b10f7](https://github.com/busyxiang/pinged/commit/f5b10f706be02b59b9faac63c1e6380186d09682)), closes [#158](https://github.com/busyxiang/pinged/issues/158)
* make every touch target in the ledger at least 44dp tall ([#178](https://github.com/busyxiang/pinged/issues/178)) ([8f67c17](https://github.com/busyxiang/pinged/commit/8f67c17fd00b4d533d4ba8774bb4509d431adbc5)), closes [#165](https://github.com/busyxiang/pinged/issues/165)
* ship the fonts' OFL texts in release builds, behind a Licences row in settings ([#160](https://github.com/busyxiang/pinged/issues/160)) ([84d4836](https://github.com/busyxiang/pinged/commit/84d4836da1fe25f3747d516fdafb6b8d86be7db1)), closes [#123](https://github.com/busyxiang/pinged/issues/123)

## [0.8.0](https://github.com/busyxiang/pinged/compare/v0.7.0...v0.8.0) (2026-10-14)


### Features

* prompt once when a suggested bank app posts while it is switched off ([#145](https://github.com/busyxiang/pinged/issues/145)) ([4e1ee56](https://github.com/busyxiang/pinged/commit/4e1ee569959caa64e2174fd15117f2d5cbef379b))
* remove a captured payment from the ledger as not a payment ([#137](https://github.com/busyxiang/pinged/issues/137)) ([2043fee](https://github.com/busyxiang/pinged/commit/2043feeff57a49309d8ab42fd8e0589117c0c534))
* seed Loans & financing and Insurance & takaful categories ([#134](https://github.com/busyxiang/pinged/issues/134)) ([#149](https://github.com/busyxiang/pinged/issues/149)) ([a5a62d1](https://github.com/busyxiang/pinged/commit/a5a62d128eb8b50f17abe2eaf05f5d8dffeb9328))
* show the export's row count, a Cancel while it runs, and where the file went ([#146](https://github.com/busyxiang/pinged/issues/146)) ([0dcce30](https://github.com/busyxiang/pinged/commit/0dcce30cd289ec3ca14a9a9da5f2545546a15a6a)), closes [#127](https://github.com/busyxiang/pinged/issues/127)


### Bug Fixes

* delete under the gate in test fixtures, and move Today at midnight between clock reads ([#147](https://github.com/busyxiang/pinged/issues/147)) ([a8863ca](https://github.com/busyxiang/pinged/commit/a8863cab1f7a868fe72f8b90eb6545d9b672ea5c))
* drop untouched sources from Seen recently 30 days after first seen ([#144](https://github.com/busyxiang/pinged/issues/144)) ([5752add](https://github.com/busyxiang/pinged/commit/5752add7d33b04f61ac963aa9fee837b3e164f6f))
* grey the top three's amounts in a month whose history has gaps ([#135](https://github.com/busyxiang/pinged/issues/135)) ([816674b](https://github.com/busyxiang/pinged/commit/816674b73ae4b6c3b40b5aa46ecbb0a37eeb278c))
* show a subtotal on day headers from earlier months ([#142](https://github.com/busyxiang/pinged/issues/142)) ([5c7638d](https://github.com/busyxiang/pinged/commit/5c7638d58d2b2771d16c7968a89a941a5487038c))
* show the build and parser pack version in Settings' footer ([#136](https://github.com/busyxiang/pinged/issues/136)) ([4d04263](https://github.com/busyxiang/pinged/commit/4d0426359b99f7e1ab5cddfe07195741d4777368))
* update the ledger's total and awaiting-review count when a capture lands while it is open ([#139](https://github.com/busyxiang/pinged/issues/139)) ([45feea9](https://github.com/busyxiang/pinged/commit/45feea95bc8a329a5492d36a0d51d8e4d59963a4))

## [0.7.0](https://github.com/busyxiang/pinged/compare/v0.6.0...v0.7.0) (2026-10-13)


### Features

* add payments by hand (manual entry) ([#120](https://github.com/busyxiang/pinged/issues/120)) ([7423920](https://github.com/busyxiang/pinged/commit/7423920286a8eee913b5edd7d011a50c5e9e9ee0))
* answer every payment awaiting review in a review inbox ([#118](https://github.com/busyxiang/pinged/issues/118)) ([689741e](https://github.com/busyxiang/pinged/commit/689741eb0c312d4b11cebaba3be37f4cbc601dba))


### Bug Fixes

* raise no storage banner when a delete or restore refuses a screen's call ([#122](https://github.com/busyxiang/pinged/issues/122)) ([6c7a801](https://github.com/busyxiang/pinged/commit/6c7a801021d1d228b952d76c7ba5ccfad0473b08)), closes [#119](https://github.com/busyxiang/pinged/issues/119)

## [0.6.0](https://github.com/busyxiang/pinged/compare/v0.5.0...v0.6.0) (2026-10-10)


### Features

* add a Charts tab with the daily rhythm grid, category bars and top merchants ([#95](https://github.com/busyxiang/pinged/issues/95)) ([34d6cb7](https://github.com/busyxiang/pinged/commit/34d6cb740648f354cfc0b5cda05efc1ab932b62e)), closes [#81](https://github.com/busyxiang/pinged/issues/81)
* file 99 Speedmart, Mr DIY, KK Mart, Grab, PTPTN and Rapid KL from the dictionary ([#59](https://github.com/busyxiang/pinged/issues/59)) ([563273a](https://github.com/busyxiang/pinged/commit/563273a2143a37be96e88e07c59a32338b63cc52))


### Bug Fixes

* play the same transition for the back chevron as for the back gesture ([#58](https://github.com/busyxiang/pinged/issues/58)) ([3d8a226](https://github.com/busyxiang/pinged/commit/3d8a226e4228eecade27366fbc7c8a41e1617f65)), closes [#56](https://github.com/busyxiang/pinged/issues/56)
* record today's capture_day row when a foreground finds the listener bound ([#82](https://github.com/busyxiang/pinged/issues/82)) ([69db297](https://github.com/busyxiang/pinged/commit/69db297a13e25e7ffb62b09984703ba9666343b4)), closes [#72](https://github.com/busyxiang/pinged/issues/72)

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
