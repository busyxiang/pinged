# Cutting a release

`.github/workflows/release.yml` builds a signed APK and publishes a GitHub
release when a `v*` tag is pushed. It runs the unit tests first, so a broken
parse corpus fails the release rather than shipping.

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

## Cutting one

```
git tag v0.1.0
git push origin v0.1.0
```

The workflow then runs the tests, builds `assembleRelease`, attaches
`pinged-0.1.0.apk` (and the R8 mapping file if minification is on), and
publishes the release with its SHA-256 and the sideloading instructions.

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
- **No third-party actions** beyond `actions/*` and `gradle/actions/*`. The
  release is created with the `gh` CLI that is already on the runner, which
  keeps the supply chain for a signing job as short as it can be.
