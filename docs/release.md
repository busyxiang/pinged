# Cutting a release

`.github/workflows/release.yml` builds a signed APK and publishes a GitHub
release when a `v*` tag is pushed. It runs the unit tests first, so a broken
parse corpus fails the release rather than shipping.

Until the Android project exists the workflow fails at its first step, by
design — a green release with no APK attached would be worse.

## Toolchain

JDK **25** locally and in CI (`java-version` in the workflow). AGP 9.4 states
a JDK *minimum* of 17, not a maximum, and requires Gradle 9.6, which runs on
JVM 17 through 26 — so 25, the current LTS, is supported.

One coupling to keep in mind if the AGP version ever moves backwards: Java 25
needs Gradle 9.1.0 or later, so an AGP 8.x project (which cannot use Gradle 9)
is capped at Java 24. Local and CI JDKs should always match; the workflow's
`java-version` is the only place CI cares.

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

### 2. Add four repository secrets

Settings, then Secrets and variables, then Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 pinged-release.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `pinged` |
| `KEY_PASSWORD` | the key password |

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

Version numbers come from the tag. Keep `versionName` in Gradle in step with
it, or read it from the tag in the build script — the workflow does not
enforce agreement.

## What the workflow deliberately does not do

- **No Play Store upload.** Public release needs a privacy policy, a
  notification-access justification, and the package-visibility constraint
  in section 9.6 of the design spec resolved first.
- **No AAB.** Sideloading is the distribution route for now, and an AAB
  cannot be installed directly.
- **No third-party actions** beyond `actions/*` and `gradle/actions/*`. The
  release is created with the `gh` CLI that is already on the runner, which
  keeps the supply chain for a signing job as short as it can be.
