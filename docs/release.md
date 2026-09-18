# Cutting a release

`.github/workflows/release.yml` builds a signed APK and publishes a GitHub
release when a `v*` tag is pushed. It runs the unit tests first, so a broken
parse corpus fails the release rather than shipping.

That tag is normally pushed by `release-please`, not by hand:
`.github/workflows/release-please.yml` maintains a pull request carrying the
version bump and the changelog, and tags the merge. **Cutting one** below is
the whole procedure.

The first step checks that `gradlew` exists, so a repository with no Android
project fails there rather than publishing a green release with no APK
attached. That guard has been satisfied since the capture milestone; it is kept
because the failure it prevents is silent.

## Toolchain

JDK **25** locally and in CI (`java-version` in
`.github/actions/setup-android/action.yml`). AGP 9.4 states
a JDK *minimum* of 17, not a maximum, and requires Gradle 9.6, which runs on
JVM 17 through 26 — so 25, the current LTS, is supported.

One coupling to keep in mind if the AGP version ever moves backwards: Java 25
needs Gradle 9.1.0 or later, so an AGP 8.x project (which cannot use Gradle 9)
is capped at Java 24. Local and CI JDKs should always match; the composite
action's `java-version` is the only place CI cares, and both workflows read
their JDK from it.

AGP's *default* JDK is 17, which is what Google exercises most. If something
inexplicable happens in D8, R8 or KSP, dropping the Gradle JDK to 17 is the
first thing to try — Java toolchains mean that does not change the output.

## One-time setup

### 1. Create a signing keystore

Do this yourself; it is a credential and it must not live in the repository.

```
keytool -genkeypair -v \
  -keystore pinged-release.jks \
  -alias pinged \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Keep this file and its passwords safe and backed up.** Android identifies
an app by its signing key: lose the keystore and you cannot ship an update
that installs over an existing Pinged. The user has to uninstall first,
which destroys their capture history — and section 11.1 of the design spec
explains why that history cannot be recovered.

Store `pinged-release.jks` outside the repository. It is covered by
`.gitignore` (`*.jks`, `*.keystore`) as a second line of defence.

### 2. Add four secrets to a `release` environment

**Environment secrets, not repository secrets**, and the distinction is not
cosmetic: the two live on different settings pages, look identical once
stored, and a job reads an environment secret as an empty string unless it
names that environment. `release.yml` names it.

Settings, then Environments, then an environment called `release`:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 pinged-release.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `pinged` |
| `KEY_PASSWORD` | the key password |

Then set the environment's deployment branch rule to **selected branches and
tags** with the single pattern `v*`. That is the reason for the environment:
a repository secret is readable by any workflow in the repository, including
one added by an edit to a workflow file, and a signing key that can only be
released to a run on a release tag is a much smaller thing to get wrong.

Leave the reviewer and wait-timer rules off unless you want a manual approval
before every release; they gate the job, not the secrets.

The workflow checks all four are present before it builds anything, so a
missing secret fails in seconds with a message naming it rather than after a
full Gradle run.

### 3. Wire the signing config in Gradle

The workflow passes the keystore through environment variables, so
`app/build.gradle.kts` needs to read them. Falling back to no signing config
when they are absent keeps local `assembleRelease` working without secrets.

```kotlin
android {
    signingConfigs {
        create("release") {
            val keystore = System.getenv("PINGED_KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("PINGED_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PINGED_KEY_ALIAS")
                keyPassword = System.getenv("PINGED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (System.getenv("PINGED_KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }
}
```

### 4. Create a token for release-please

`release-please` opens the version-bump pull request and, once you merge it,
tags the merge and opens the GitHub release. That tag is the trigger
`release.yml` waits on -- and **GitHub does not start a workflow run for an
event raised by `GITHUB_TOKEN`**. With the default token the release would be
tagged and never built: a release that silently never ships, which is worse
than one that fails.

So it needs a token of its own. Create a fine-grained personal access token:

- **Repository access:** only this repository.
- **Permissions:** Contents *read and write*, Pull requests *read and write*.
  Nothing else.

Store it as a **repository** secret named `RELEASE_PLEASE_TOKEN` -- not an
environment secret, because `release-please.yml` names no environment.

This is deliberately not the way to give the build its keystore. The keystore
stays in the `release` environment, which admits `v*` tags and no branches, so
it is reachable only from a run triggered by the tag. The token exists to make
that tag trigger a run at all; it never grants access to the environment. The
alternative -- building from the `push: main` run that `release-please` itself
fires -- would mean admitting `main` to that environment, which would put the
signing keystore in reach of any workflow file edited on `main`.

A fine-grained token expires. When it does, merging a release PR will stop
producing a tag and nothing will say so loudly, so it is worth setting a
calendar reminder for the expiry date rather than discovering it at a release.

## Cutting one

Nothing is tagged by hand. Committing with Conventional Commit subjects (see
`CLAUDE.md`) is what starts a release:

1. Land `feat:` or `fix:` commits on `main`. `release-please` opens a pull
   request titled *chore(main): release X.Y.Z*, which bumps `version.txt`,
   updates `.release-please-manifest.json`, and drafts the `CHANGELOG.md`
   entry from those subjects.
2. **Edit that entry in the pull request.** The draft says what changed; the
   release has to say what it does for the person installing it, and what it
   still cannot do. This is the step that makes the notes worth reading, and
   it is the only chance to do it before anything ships.
3. Merge. `release-please` tags the merge commit and opens the GitHub release.
4. The tag fires `release.yml`, which runs the tests, builds
   `assembleRelease`, attaches `pinged-X.Y.Z.apk` (and the R8 mapping file if
   minification is on), and rewrites the release body with the changelog
   entry, the SHA-256 and the sideloading instructions.

Between steps 3 and 4 the release exists with no APK attached, for as long as
the build takes. That is the cost of the tag being what triggers the build.

A tag pushed by hand still works, and is the fallback if `release-please` is
unavailable:

```
git tag -a v0.1.0
git push origin v0.1.0
```

The publish step is idempotent for exactly that reason: it uploads into an
existing release and creates one only when there is none. `git tag -a` rather
than `git tag` on that path, because with no changelog entry for the version
the tag message is all the notes have.

`release.yml` also refuses a tag whose version does not match `version.txt`.
The two can only disagree when a tag was pushed without merging the release PR
for it, and the symptom otherwise would be a release carrying the previous
version's notes.

The notes are built by `.github/scripts/release_notes.sh` and are four things:

- the `CHANGELOG.md` entry for this version, rendered as markdown -- or, when
  there is none, the tag's own message quoted verbatim;
- the diffstat between this tag and the previous one, measured with `git
  diff --shortstat`;
- the subject lines of the commits in that range, merge commits excluded;
- the static sideloading instructions and the APK's SHA-256.

The changelog entry is the only part of the notes rendered as markdown rather
than fenced. The distinction is trust, not formatting: a commit message
arrives from whoever wrote it, while `CHANGELOG.md` is reviewed in the release
pull request by whoever could edit the generating script anyway.

The curated entry carries the weight on purpose. A merged branch lands here as
one squashed commit — the whole capture milestone is `7918da9` — so a commit
list is short in a way that has nothing to do with how large the release is,
and the notes say so where a reader will see it. The diffstat is stated first
because it is measured across the range and a squash cannot flatten it. The
commit list is subject lines only: this repository's commit messages are long
and are about why, and a release page is not where they read well, so the
notes link the compare view instead of reproducing them.

Everything taken out of git is emitted inside a fenced code block whose fence
is one backtick longer than the longest backtick run in the text, so a commit
message cannot close its own container and reach the rendered page as
markdown, HTML or an `@mention`. That also means the tag message renders
preformatted, with the wrapping it was written with.

The script runs outside Actions, which is how it gets tested — the workflow
around it signs an APK and publishes to GitHub, so it cannot be rehearsed:

```
.github/scripts/release_notes.sh v0.1.0 $(sha256sum some.apk | cut -d' ' -f1) /tmp/notes.md
```

It needs the tag and the previous tag's history, so a shallow clone gives the
wrong answer rather than an error — it reports every release as the first.
That is why `release.yml` checks out with `fetch-depth: 0`.
`.github/scripts/release_notes_test.sh`, which CI runs, covers both paths and
reverts each guard to prove it is load-bearing.

Version numbers come from the tag and nowhere else. `app/build.gradle.kts`
reads `PINGED_VERSION`, which the workflow sets to the tag without its leading
`v`, and derives `versionCode` from it as `major * 10000 + minor * 100 +
patch`. A build without that variable calls itself `0.0.0-dev`, code 1, which
is deliberately not a plausible version.

The workflow then re-derives both from the tag and compares them against the
merged manifest before uploading anything. `versionCode` is the field that
matters: it is the only one Android compares, so an APK named after the right
tag carrying a stale code is an update the package manager will not treat as
one.

## What the workflow deliberately does not do

- **No Play Store upload.** Public release needs a privacy policy, a
  notification-access justification, and the package-visibility constraint
  in section 9.6 of the design spec resolved first.
- **No AAB.** Sideloading is the distribution route for now, and an AAB
  cannot be installed directly.
- **No third-party action in the signing job.** `release.yml` uses `actions/*`
  and `gradle/actions/*` only, and creates the release with the `gh` CLI
  already on the runner. The note generator is `git`, `awk` and `printf` in a
  shell script here rather than a changelog action, because a job that
  decrypts a signing keystore is the last place to add a dependency for the
  sake of a nicer changelog. `release-please` is third-party and does run
  against this repository, but in `release-please.yml`, which names no
  environment and so never sees the keystore.
- **No changelog invented from subject lines.** `release-please` groups
  commits by their Conventional Commit type, which is a classification the
  author wrote down rather than one inferred from prose. What reaches the
  release is the `CHANGELOG.md` entry after it has been edited in the release
  pull request, so the notes assert only what a person put there.
- **No rewriting of what the author wrote.** Nothing summarises, reorders or
  rewords the changelog entry, the tag message or the commit subjects.
  Everything taken out of git is still rendered preformatted; the changelog
  entry is the one exception, and it is an exception because it is reviewed
  before it ships.
- **No `--generate-notes`.** GitHub's own generator lists merged pull requests
  and new contributors; this repository merged one pull request in its life
  and has one contributor, so on a squash-per-branch history it produces less
  than the tag message does.
