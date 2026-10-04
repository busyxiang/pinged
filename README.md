# Pinged

Android expense tracker for Malaysia. Builds its ledger from push
notifications emitted by local banking apps and e-wallets, with no manual
entry for digital spending.

Design: [docs/superpowers/specs/2026-09-02-pinged-design.md](docs/superpowers/specs/2026-09-02-pinged-design.md)

## Layout

    :core:parse      Rules, amounts, merchant normalization. Zero Android
                     dependencies, enforced by a Gradle check, so the whole
                     engine is JVM-testable.
    :core:data       Room entities, DAOs, the SQLCipher key, migrations.
    :feature:capture Stage one (the notification listener) and stage two
                     (the parse worker). Depends on :core:data.
    :feature:ledger  The allow-list screen, the theme, JSON export/import.
    :app             Wiring and the single Activity.
    :smoke           The release build's first capture, driven from outside
                     it. Installs as Touch 'n Go's package.

## Building

    ./gradlew test                        # 191 JVM tests
    ./gradlew connectedDebugAndroidTest   # 315, needs a device or emulator
    ./gradlew :smoke:connectedMinifiedAndroidTest   # the R8 build, same
    ./gradlew :app:assembleDebug

CI runs `test lint :app:assembleDebug` on every pull request, and the
instrumented suite on pull requests into `main`. A push to a branch with no
pull request runs nothing -- open the pull request as a draft to get CI on
every push without the ten-minute emulator job.

## Releases

Tagging `v*` builds a signed APK and publishes a GitHub release. Setup and
the signing requirements are in
[docs/release.md](docs/release.md).

## Status

**The capture milestone is built, green on CI, and not yet proven on a phone.**
Five modules, 506 tests: 191 on the JVM, 315 on an emulator.

What runs today: notifications from packages the user has enabled are captured
into an encrypted database whose key never leaves the device and never enters a
backup, parsed by a data-driven pack validated at load, and written as
transactions idempotently. The allow-list screen is the only screen.

What is written and has no way to reach it: JSON export and import. Both are
implemented and tested end to end, and nothing in the app calls them, because
the settings screen that would is a later milestone.

What has not happened at all: verification on real hardware. All three of spec
10.1's rebind paths are built, and none of them has been shown to work -- the
one that matters is an APK replace, which kills the process, so nothing of ours
is running to observe it and the instrumented suite can only check the parts
around it. The two package identifiers in `pack.json` and the manifest's
`<queries>` are still guesses, and no real payment has ever been captured.

That is Task 14 of the capture plan, and it is the gap between "the tests pass"
and "it works".

One test is not understood: a dedup assertion has failed twice, once on CI and
once locally, and passed every other time. It is not fixed, only made
diagnosable.
