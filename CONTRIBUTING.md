# Contributing

Bugs, new bank wordings and new apps go on
[GitHub Issues](https://github.com/busyxiang/pinged/issues). The design spec,
[docs/superpowers/specs/2026-09-02-pinged-design.md](docs/superpowers/specs/2026-09-02-pinged-design.md),
is what section numbers in code, commits and issues refer to; the terms it and
the issues use are in [CONTEXT.md](CONTEXT.md).

## A notification is personal data

The repository is public, and every fixture under
`core/parse/src/test/resources/fixtures/` is a real notification. When you
add one, copy it verbatim except for two masks:

- a card's digits become `****`;
- every transaction reference (`REF:`, `QR…`, `FPX ID`,
  `Merchant Reference No.`) becomes zeros of the same shape.

The fixture's header says what was masked, and says either `A real …` or
`Constructed, not observed`. Leave everything else as the bank sent it: the
rules are tested against what the phone actually receives.

On an issue, paste a notification masked the same way. A device export, a
screenshot of the ledger or a list of your merchants stays off the issue;
counts are fine.

## Commits

Subjects are [Conventional Commits](https://www.conventionalcommits.org/), and
`release-please` reads the type to choose the next version:

    fix:   a patch release
    feat:  a minor release
    chore, docs, test, refactor, ci, build, perf: no release

So a change someone using the app would notice is `feat` or `fix`, including a
rule that reads a new bank wording: as `chore` it would ship to nobody. Add
`!` when the release costs the user something, such as data to re-enter or a
permission to grant again.

The body says why, what was measured, and what is still not done.

## Tests

A change lands with a test that fails without it. Before opening the pull
request, check that it does: revert the change and watch the test fail for
the reason it names.

    ./gradlew test verifyRoborazziDebug lint :app:assembleDebug   # CI's checks
    ./gradlew connectedDebugAndroidTest                           # needs a device

Open the pull request as a draft to get CI's checks on every push; mark it
ready for the emulator suites.
