# Pinged

Android expense tracker for Malaysia. Builds its ledger from push
notifications emitted by local banking apps and e-wallets, with no manual
entry for digital spending. Offline only: no network permission, no server.

Design: [docs/superpowers/specs/2026-09-02-pinged-design.md](docs/superpowers/specs/2026-09-02-pinged-design.md).
Domain terms: [CONTEXT.md](CONTEXT.md).

## Layout

    :core:parse       Rules, amounts, merchant normalization. Zero Android
                      dependencies, enforced by a Gradle check, so the whole
                      engine is JVM-testable.
    :core:data        Room entities, DAOs, the SQLCipher key, migrations.
    :core:ui          The theme and the formatting the screens share.
    :feature:capture  Stage one (the notification listener) and stage two
                      (the parse worker). Depends on :core:data.
    :feature:ledger   Every screen but Spending: the ledger, detail, search,
                      review, settings, onboarding, export and import.
    :feature:charts   Spending: the month's category bars and rhythm grid.
    :app              Wiring and the single Activity.
    :smoke            The release build's first capture, driven from outside
                      it. Installs as Touch 'n Go's package.

## Building

    ./gradlew test                        # JVM tests
    ./gradlew test verifyRoborazziDebug lint :app:assembleDebug   # CI's checks
    .claude/bin/emu -- ./gradlew connectedDebugAndroidTest        # needs a device
    .claude/bin/emu -- ./gradlew :smoke:connectedMinifiedAndroidTest   # the R8 build

`.claude/bin/emu` leases the one shared emulator; see [CLAUDE.md](CLAUDE.md).

CI runs the checks on every pull request, and on a pull request into `main`
that is not a draft, the instrumented suites its diff can reach. A push to a
branch with no pull request runs nothing -- open the pull request as a draft
to get the checks on every push without the emulator job.

## Releases

`release-please` keeps a release PR open from the Conventional Commit
subjects; merging it tags `v*`, which builds a signed APK and publishes a
GitHub release. What each release does is in [CHANGELOG.md](CHANGELOG.md).
Setup and the signing requirements are in [docs/release.md](docs/release.md).
