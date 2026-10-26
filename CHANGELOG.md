# Changelog

Written for someone installing the APK, not for someone reading the diff.
An entry should say what the release does for you and what it still cannot do;
`release-please` drafts each one from the commits, and the release PR is where
it gets edited into that before anything ships.

## [0.14.0](https://github.com/busyxiang/pinged/compare/v0.13.0...v0.14.0) (2026-10-26)


### Features

* ask about MAE transfers to a person before counting them ([#316](https://github.com/busyxiang/pinged/issues/316)) ([9a21c21](https://github.com/busyxiang/pinged/commit/9a21c212b07ed5aa572acbfb2c6c9cd2d82d4f4b))
* let a money-back review card say which category it counts against ([#319](https://github.com/busyxiang/pinged/issues/319)) ([5ead800](https://github.com/busyxiang/pinged/commit/5ead80059f64c99984fa5721957d1672cfbea366))
* read incoming money from MAE and Touch 'n Go as a refund held for review ([#310](https://github.com/busyxiang/pinged/issues/310)) ([06d2ef7](https://github.com/busyxiang/pinged/commit/06d2ef79ef3577464088f0ca22a1efb3a495a004))


### Bug Fixes

* count a kept notification as capture evidence, from one capture record ([#327](https://github.com/busyxiang/pinged/issues/327)) ([6e2aa2d](https://github.com/busyxiang/pinged/commit/6e2aa2d0cbdb0446f0ef05dd9cb289145e640652))
* refuse a ledger change from the moment a restore or delete-everything is claimed ([#331](https://github.com/busyxiang/pinged/issues/331)) ([#337](https://github.com/busyxiang/pinged/issues/337)) ([8089757](https://github.com/busyxiang/pinged/commit/8089757ebe772bba92b750e9483e35f2fd8958a1))
* refuse Teach's save from the moment a restore or delete-everything is claimed ([#340](https://github.com/busyxiang/pinged/issues/340)) ([b2e1e35](https://github.com/busyxiang/pinged/commit/b2e1e358ec9ecd782e0249874578612132ee7e2e))
* say a screen is being replaced, not unreadable, during a restore or delete ([#336](https://github.com/busyxiang/pinged/issues/336)) ([fe7d12c](https://github.com/busyxiang/pinged/commit/fe7d12cb2c945c9609b716b7ecbc3dacf3e7b9e1)), closes [#328](https://github.com/busyxiang/pinged/issues/328)
* take a stale "cannot open your data" banner down when the backup count fails ([#325](https://github.com/busyxiang/pinged/issues/325)) ([329bfdd](https://github.com/busyxiang/pinged/commit/329bfdd85f2fd70113300752703627079d290b24))
* word Teach's second direction as money back, not a refund ([#317](https://github.com/busyxiang/pinged/issues/317)) ([74e73e5](https://github.com/busyxiang/pinged/commit/74e73e5fdea6ff55206073257b1ee6d81c722c45))

## [0.13.0](https://github.com/busyxiang/pinged/compare/v0.12.0...v0.13.0) (2026-10-23)


### Features

* draw every screen's read in flight as the receipt's not-read-yet layout ([#295](https://github.com/busyxiang/pinged/issues/295)) ([90da8a9](https://github.com/busyxiang/pinged/commit/90da8a99bb72fcf0aa06a18b5236942e6c647ff9))


### Bug Fixes

* read every screen's ledger figures through one ScreenRead module, and re-read on a restore ([#309](https://github.com/busyxiang/pinged/issues/309)) ([a6d5593](https://github.com/busyxiang/pinged/commit/a6d5593db8e382e9a4d7904e9b43914c1a52aff1))
* scroll the merchant search to the top once the keyboard has risen ([#292](https://github.com/busyxiang/pinged/issues/292)) ([2a64d11](https://github.com/busyxiang/pinged/commit/2a64d1134778ef959493e58c2dc66661d825dcf0))

## [0.12.0](https://github.com/busyxiang/pinged/compare/v0.11.0...v0.12.0) (2026-10-20)


### Features

* float the Cash button over Spending's list and slide it away while scrolling down ([#290](https://github.com/busyxiang/pinged/issues/290)) ([d979fce](https://github.com/busyxiang/pinged/commit/d979fceeebac7b9acf65c51bffed9e42731d9154))


### Bug Fixes

* leave cards over the RM500 check limit out of Accept all ([#289](https://github.com/busyxiang/pinged/issues/289)) ([75a5fdf](https://github.com/busyxiang/pinged/commit/75a5fdfcb5c50b6fbd28c7d12b7afb5935089c97)), closes [#223](https://github.com/busyxiang/pinged/issues/223)

## [0.11.0](https://github.com/busyxiang/pinged/compare/v0.10.0...v0.11.0) (2026-10-19)


### Features

* reflow Licences, fit Search's chips, check-mark sheets, show reach on open, drop Review's table ([#277](https://github.com/busyxiang/pinged/issues/277)) ([8eedfea](https://github.com/busyxiang/pinged/commit/8eedfea9ad1acb4e550cbae68b9f7825ac24b626)), closes [#274](https://github.com/busyxiang/pinged/issues/274)
* scroll Spending's summary away and carry the total in the TopBar ([#253](https://github.com/busyxiang/pinged/issues/253)) ([#269](https://github.com/busyxiang/pinged/issues/269)) ([ed7f257](https://github.com/busyxiang/pinged/commit/ed7f257d7eefd15cfe42898f3ee2a7a967d33526))
* show the backup reminder as one line on Spending only ([#252](https://github.com/busyxiang/pinged/issues/252)) ([#270](https://github.com/busyxiang/pinged/issues/270)) ([23e0602](https://github.com/busyxiang/pinged/commit/23e0602b46a5aa492977156b65dcf7ed352331d7))
* take Uncategorized out of Spending's top three and sort it from N TO SORT ([#273](https://github.com/busyxiang/pinged/issues/273)) ([#278](https://github.com/busyxiang/pinged/issues/278)) ([db42ed0](https://github.com/busyxiang/pinged/commit/db42ed0b73517713d3c3c0c5e733fb1f953964cf))


### Bug Fixes

* build Settings to its redrawn artboard, with Licences in the footer ([#230](https://github.com/busyxiang/pinged/issues/230)) ([#261](https://github.com/busyxiang/pinged/issues/261)) ([333c642](https://github.com/busyxiang/pinged/commit/333c6426aae7079154cdb2847eb417e1bd9792d1))
* build the merchant sheet to its artboard, choosing before merging ([#231](https://github.com/busyxiang/pinged/issues/231)) ([#262](https://github.com/busyxiang/pinged/issues/262)) ([c58224a](https://github.com/busyxiang/pinged/commit/c58224a46a024c3eac0d88995b10b58b69564a96))
* centre each Charts grid square's accessibility bounds on the square ([#240](https://github.com/busyxiang/pinged/issues/240)) ([dc6b009](https://github.com/busyxiang/pinged/commit/dc6b0093185f01013b1bdc000295e37d7ce458fb))
* count a refund in Search's header so it matches the export button ([#244](https://github.com/busyxiang/pinged/issues/244)) ([0201664](https://github.com/busyxiang/pinged/commit/0201664288cb76ea6cba71fbc2b88f873a0f7ac9)), closes [#216](https://github.com/busyxiang/pinged/issues/216)
* create the alerts channel before opening its settings page ([#247](https://github.com/busyxiang/pinged/issues/247)) ([1456722](https://github.com/busyxiang/pinged/commit/145672208333119825190df2a26d7729862028b9))
* draw a running month's Charts as the October-so-far artboard ([#232](https://github.com/busyxiang/pinged/issues/232)) ([#263](https://github.com/busyxiang/pinged/issues/263)) ([dfa438f](https://github.com/busyxiang/pinged/commit/dfa438f9ccdd36dc9bc1e76503ac482215215df5))
* draw a value not read yet as a dash, and a failed count as unknown ([#237](https://github.com/busyxiang/pinged/issues/237)) ([#264](https://github.com/busyxiang/pinged/issues/264)) ([2719884](https://github.com/busyxiang/pinged/commit/2719884f9b1c616b6ea62ba55b22c210ceda7677))
* draw Detail for a left-out row as its artboard ([#229](https://github.com/busyxiang/pinged/issues/229)) ([#257](https://github.com/busyxiang/pinged/issues/257)) ([a735df6](https://github.com/busyxiang/pinged/commit/a735df69f6f199ab8466397599c7fd9687b6f384))
* draw the drifted shared controls as the rest of the app does ([#220](https://github.com/busyxiang/pinged/issues/220)) ([#266](https://github.com/busyxiang/pinged/issues/266)) ([1042d31](https://github.com/busyxiang/pinged/commit/1042d315108928d37f80033b41dfa1ff5a890164))
* draw the heard chip, Charts hero, Detail rows and CASH band as their artboards ([#221](https://github.com/busyxiang/pinged/issues/221)) ([#251](https://github.com/busyxiang/pinged/issues/251)) ([39d2589](https://github.com/busyxiang/pinged/commit/39d25897bad9e87c28e5485c3642178fee279189))
* fit Onboarding's footer, wrap Cash entry's chips behind More, and close the gap above Teach's save bar ([#276](https://github.com/busyxiang/pinged/issues/276)) ([f137815](https://github.com/busyxiang/pinged/commit/f137815be4ca1c30a23dce8ffc201e87d7144b99))
* give the Day screen its steps, split total and capture line ([#228](https://github.com/busyxiang/pinged/issues/228)) ([#258](https://github.com/busyxiang/pinged/issues/258)) ([07b7b38](https://github.com/busyxiang/pinged/commit/07b7b38dd767acaa11ccc5f41f12963dc523a066))
* list categories most used first, the empty ones folded, as their artboard ([#256](https://github.com/busyxiang/pinged/issues/256)) ([8e3fe4a](https://github.com/busyxiang/pinged/commit/8e3fe4aeb2691a0ac8f1af27d7ebd111ed5eaf86))
* open Jump to fully expanded when system animations are off ([#241](https://github.com/busyxiang/pinged/issues/241)) ([4555753](https://github.com/busyxiang/pinged/commit/4555753cf237b5ce00cfa83f263960001d444fe0)), closes [#213](https://github.com/busyxiang/pinged/issues/213)
* read words typed in search's first 300ms instead of dropping them ([#246](https://github.com/busyxiang/pinged/issues/246)) ([7ae605a](https://github.com/busyxiang/pinged/commit/7ae605a037d009630ce1e8b536745b0d68f3fc44))
* refuse a queued delete or restore once its screen has begun going away ([#260](https://github.com/busyxiang/pinged/issues/260)) ([e47f39a](https://github.com/busyxiang/pinged/commit/e47f39adbc4a30d5946b3d82a49557917bdc246e)), closes [#259](https://github.com/busyxiang/pinged/issues/259)
* say a left-out row was left out by the user, not USER ([#242](https://github.com/busyxiang/pinged/issues/242)) ([1f42d6d](https://github.com/busyxiang/pinged/commit/1f42d6d50533c8c8316f02611a089c7c73dd06e2)), closes [#214](https://github.com/busyxiang/pinged/issues/214)
* say how many held payments are in the month Spending shows ([#243](https://github.com/busyxiang/pinged/issues/243)) ([4b54a61](https://github.com/busyxiang/pinged/commit/4b54a6114db35b68697529146b69850a395567dd)), closes [#215](https://github.com/busyxiang/pinged/issues/215)
* scroll the merchant search to the top so its results show above the keyboard ([#268](https://github.com/busyxiang/pinged/issues/268)) ([2a46ab6](https://github.com/busyxiang/pinged/commit/2a46ab6946c630a1faf0c732ccfe87bacfbc52ea))
* settle a restore that fails at once off the main thread ([#271](https://github.com/busyxiang/pinged/issues/271)) ([08ccbcc](https://github.com/busyxiang/pinged/commit/08ccbcc1400e2118158f59276a20623b247d3be2))
* stop holding recurring identical payments as duplicates ([#235](https://github.com/busyxiang/pinged/issues/235)) ([78bc0e0](https://github.com/busyxiang/pinged/commit/78bc0e018c64872d23867877d81b558bea020706))
* stop offering System UI as a capture source ([#248](https://github.com/busyxiang/pinged/issues/248)) ([af61698](https://github.com/busyxiang/pinged/commit/af616982d9b74ff40bb8ea9d6625a087d5fcb77b)), closes [#218](https://github.com/busyxiang/pinged/issues/218)

## [0.10.0](https://github.com/busyxiang/pinged/compare/v0.9.0...v0.10.0) (2026-10-16)


### Features

* add, rename, re-icon and delete categories ([#155](https://github.com/busyxiang/pinged/issues/155)) ([#203](https://github.com/busyxiang/pinged/issues/203)) ([f7a9827](https://github.com/busyxiang/pinged/commit/f7a9827889ba657b546598cae895677413a06b5f))
* export a CSV from the Export sheet and from a search ([#201](https://github.com/busyxiang/pinged/issues/201)) ([#205](https://github.com/busyxiang/pinged/issues/205)) ([d6b4653](https://github.com/busyxiang/pinged/commit/d6b46534a9b02d994a38a597b7a98770b67a4743))
* list the notifications Pinged could not read, with Never a transaction ([#150](https://github.com/busyxiang/pinged/issues/150)) ([#204](https://github.com/busyxiang/pinged/issues/204)) ([ca4713a](https://github.com/busyxiang/pinged/commit/ca4713a5baae2c2b071a51bb08e7e5b495c81c23))
* open a fresh install on onboarding's three claims and the access grant ([#156](https://github.com/busyxiang/pinged/issues/156)) ([#200](https://github.com/busyxiang/pinged/issues/200)) ([c2af30d](https://github.com/busyxiang/pinged/commit/c2af30d7ec7bb91b53ee8a780b1edfff6dcba77f))
* pick a month from Spending's header ([#197](https://github.com/busyxiang/pinged/issues/197)) ([fba6002](https://github.com/busyxiang/pinged/commit/fba6002f512ad80c84eb462674eeb7672922ac36))
* search the ledger by word, month, category, app and amount ([#154](https://github.com/busyxiang/pinged/issues/154)) ([#202](https://github.com/busyxiang/pinged/issues/202)) ([9cc549d](https://github.com/busyxiang/pinged/commit/9cc549d97f307f807960c204e78d3d66315a2d32))
* show what Pinged read for a captured payment, and the rule that read it ([#153](https://github.com/busyxiang/pinged/issues/153)) ([#199](https://github.com/busyxiang/pinged/issues/199)) ([9669844](https://github.com/busyxiang/pinged/commit/96698442c5dc69d5f781c6fd62498902c11ad7d7))
* teach Pinged to read a message by tapping its amount and merchant ([#151](https://github.com/busyxiang/pinged/issues/151)) ([#206](https://github.com/busyxiang/pinged/issues/206)) ([420d2a1](https://github.com/busyxiang/pinged/commit/420d2a12e8462d77541803057acf056bb6b2127f))


### Bug Fixes

* draw Cash entry at its artboard's type, colours and Save band ([#169](https://github.com/busyxiang/pinged/issues/169)) ([#196](https://github.com/busyxiang/pinged/issues/196)) ([f711493](https://github.com/busyxiang/pinged/commit/f711493235510f42368e5dccf6052e3a5a180f81))
* draw ledger rows at the artboard's height, with touch targets in the free space ([#189](https://github.com/busyxiang/pinged/issues/189)) ([12456d6](https://github.com/busyxiang/pinged/commit/12456d6e2d28b6b4b24287432e925c1dd43795e1)), closes [#186](https://github.com/busyxiang/pinged/issues/186)
* draw Settings, Export, Months and learned merchants as their artboards ([#171](https://github.com/busyxiang/pinged/issues/171)) ([#194](https://github.com/busyxiang/pinged/issues/194)) ([6cf8191](https://github.com/busyxiang/pinged/commit/6cf8191b122c345918e40c1cd8c8863da8cebf29))
* draw Spending and day one at their artboards' sizes, rhythm and offer ([#193](https://github.com/busyxiang/pinged/issues/193)) ([e147716](https://github.com/busyxiang/pinged/commit/e14771627c97dbd8a00b8f61394308287cf9f294)), closes [#170](https://github.com/busyxiang/pinged/issues/170)
* draw the category chooser as its artboard, a grid of outlined tiles ([#190](https://github.com/busyxiang/pinged/issues/190)) ([b24117d](https://github.com/busyxiang/pinged/commit/b24117d82983fedc065cfe47744ff48cf2b1d93b)), closes [#167](https://github.com/busyxiang/pinged/issues/167)
* draw the review inbox as its artboard does, Stamp only where it needs you ([#168](https://github.com/busyxiang/pinged/issues/168)) ([#191](https://github.com/busyxiang/pinged/issues/191)) ([8cd0844](https://github.com/busyxiang/pinged/commit/8cd0844d7631c84aad7368c0a9cf1c6e28ebe9e0))
* keep the storage flag's memo in step with its store when a raise and a clear cross ([#185](https://github.com/busyxiang/pinged/issues/185)) ([cffcbbc](https://github.com/busyxiang/pinged/commit/cffcbbcc6ab35e50f8706a2f3540eb3b71e74961)), closes [#179](https://github.com/busyxiang/pinged/issues/179)
* let a clear started on seeing a raise reach the storage flag ([#195](https://github.com/busyxiang/pinged/issues/195)) ([52e8444](https://github.com/busyxiang/pinged/commit/52e84442f2c1a853c56b3e26cec15cd49fc6148e)), closes [#179](https://github.com/busyxiang/pinged/issues/179)

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
