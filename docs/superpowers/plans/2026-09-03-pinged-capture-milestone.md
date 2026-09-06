# Pinged Capture Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An installable Android app that captures notifications from packages the user has enabled, parses them with a data-driven rule pack, writes them to an encrypted database, and can export and re-import that database as JSON.

**Architecture:** Two-stage capture with a durable boundary. `NotificationListenerService` extracts fields and does one insert into `raw_capture`; a background worker reads that table, matches rules, and writes `txn` rows. Rule matching lives in `:core:parse`, a pure-Kotlin module with no Android dependencies, so the whole engine is JVM-testable against a corpus of real notification strings.

**Tech Stack:** Kotlin, Jetpack Compose, Room over SQLCipher (raw key mode), WorkManager, DataStore, kotlinx.serialization. JDK 25, AGP 9.4, Gradle 9.6, minSdk 27.

**Spec:** [`docs/superpowers/specs/2026-09-02-pinged-design.md`](../specs/2026-09-02-pinged-design.md)

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec.

- **`minSdk 27`.** Named regex group access is API 26; `isNotificationListenerAccessGranted()` is API 27. `targetSdk 36`, `compileSdk 36`.
- **`applicationId = my.pinged.tracker`.** Changing it later is a different app to Android: reinstall, database gone, history unrecoverable.
- **The listener service's fully-qualified class name is frozen.** The notification-access grant is stored against its flattened `ComponentName`; renaming the class silently voids the grant.
- **No `INTERNET` permission in the manifest.** Ever, in this milestone.
- **`android:allowBackup="false"`**, plus the database excluded under both `<cloud-backup>` and `<device-transfer>` in `android:dataExtractionRules`. The wrapped key lives in `getNoBackupFilesDir()`.
- **Amounts are `Long` sen.** `BigDecimal` only in the string-to-sen step. No floating point anywhere in the money path.
- **Regex flags are fixed:** `CASE_INSENSITIVE | UNICODE_CASE | DOTALL`, never `MULTILINE`.
- **Transaction templates are evaluated before reject patterns.** Never the reverse (spec §5.3).
- **Default-deny.** A transaction is created only when text matches a known template for that package. Never from finding a currency amount.
- **`:core:parse` and `:core:categorize` have zero Android dependencies.** Enforced by a Gradle check, not by discipline.
- **Every string comparison and case change uses `Locale.ROOT`.**
- **The table is `txn`, not `transaction`** — the latter is a SQLite reserved keyword.
- **`raw_capture` rows are never deleted** except by the user's delete-all action.
- **Bytecode target is Java 17 (class-file major 61), not the toolchain JDK.**
  `jvmToolchain(25)` selects the compiler but also emits major 69, which D8
  rejects; every module pins `jvmTarget` explicitly. See Task 1 Step 6.
- **A behaviour with no falsifying test has not landed.** A fix whose removal
  leaves the suite green is indistinguishable from no fix, and this branch
  shipped thirteen such behaviours before they were caught by mutation.

> **On the per-task test counts below.** Each `Expected: PASS, N tests` is a
> snapshot as of that task's own commit. The final whole-branch review's fix
> wave added tests and one fixture after Task 6, so the branch total is now
> **90 tests and 7 fixtures**, not the 59 and 6 the Task 6 numbers imply.
> Treat the per-task numbers as historical, and the current
> `./gradlew :core:parse:test` output as authoritative.

---

## File Structure

```
settings.gradle.kts                        module list, repositories
gradle/libs.versions.toml                  version catalog, single source of dependency truth
build.gradle.kts                           root, plugin declarations only

core/parse/                                PURE KOTLIN — no Android
  src/main/kotlin/my/pinged/parse/
    Amount.kt                              string -> Long sen, with rejects
    TextNormalizer.kt                      Unicode spaces, invisibles, case folding
    Skeleton.kt                            digit-stripped signature (spec 5.7, 5.8)
    Merchant.kt                            acquirer prefix and suffix stripping
    Pack.kt                                pack data model + kotlinx.serialization
    Conditions.kt                          condition evaluation (the four
                                           predicates plus the field selector;
                                           the `Conditions` data class itself
                                           lives in Pack.kt with the rest of
                                           the serialized model)
    Deadline.kt                            interruptible CharSequence, regex budget
    RuleMatcher.kt                          templates-then-rejects, the heart
    Outcome.kt                             sealed result type crossing the module boundary
  src/test/kotlin/...                      JVM tests
  src/test/resources/fixtures/             the corpus, one file per case

core/data/                                 Room, SQLCipher, DAOs
  src/main/kotlin/my/pinged/data/
    entity/RawCapture.kt, Txn.kt, Category.kt, CaptureSource.kt, MerchantRule.kt
    dao/RawCaptureDao.kt, TxnDao.kt, CategoryDao.kt, CaptureSourceDao.kt
    PingedDatabase.kt                      @Database, indices, exportSchema
    DatabaseKey.kt                         Keystore-wrapped raw key
    DatabaseFactory.kt                     SQLCipher factory in raw key mode
  schemas/                                 exported Room schema JSON, committed from v1

feature/capture/
  src/main/kotlin/my/pinged/capture/
    PingedNotificationListener.kt          STAGE 1 ONLY. Frozen class name.
    NotificationFields.kt                  extras -> data class, getCharSequence
    ParseWorker.kt                         STAGE 2
    RebindReceiver.kt                      MY_PACKAGE_REPLACED, BOOT_COMPLETED
    CaptureHealth.kt                       DataStore heartbeat, throttled
    SourceCounters.kt                      DataStore per-package seen counts

feature/ledger/
  src/main/kotlin/my/pinged/ledger/
    theme/Theme.kt, Color.kt, Type.kt      the Receipt design system
    sources/SourcesScreen.kt               the allow-list
    transfer/ExportJson.kt, ImportJson.kt  streaming, SAF

app/
  src/main/AndroidManifest.xml             permissions, service, receivers, backup rules
  src/main/res/xml/data_extraction_rules.xml
  src/main/kotlin/my/pinged/PingedApp.kt   DI wiring
```

---

### Task 1: Project skeleton that builds and tests green

The release workflow currently fails at its first step because there is no `gradlew`. This task ends that.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`, `gradle/wrapper/*`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `core/parse/build.gradle.kts`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: nothing
- Produces: a Gradle project where `./gradlew test` and `./gradlew assembleRelease` both run. Module coordinates `:app`, `:core:parse`.

- [ ] **Step 1: Generate the skeleton with the Studio wizard**

Do not hand-write the wrapper or invent version numbers. In Android Studio, New Project, Empty Activity, then set: name Pinged, package name `my.pinged.tracker`, language Kotlin, build config Kotlin DSL, minimum SDK **API 27**. This pins AGP, Gradle, Kotlin and Compose BOM to versions that are current and mutually compatible, which is not something to guess at.

Move the generated project into the repository root so `gradlew` sits beside `README.md`.

- [ ] **Step 2: Set the identity constants**

In `app/build.gradle.kts`, confirm and correct:

```kotlin
android {
    namespace = "my.pinged.tracker"
    compileSdk = 36
    defaultConfig {
        applicationId = "my.pinged.tracker"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
```

- [ ] **Step 3: Add the release signing config from the release doc**

Copy the `signingConfigs` block from [`docs/release.md`](../../release.md) into `app/build.gradle.kts` verbatim. It reads `PINGED_KEYSTORE_FILE` and friends from the environment and falls back to no signing when they are absent, so a local `assembleRelease` still works.

- [ ] **Step 4: Lock the manifest down**

`app/src/main/AndroidManifest.xml`. Note what is absent: no `INTERNET`.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <queries>
        <package android:name="my.com.tngdigital.ewallet" />
        <package android:name="com.maybank2u.life" />
    </queries>

    <application
        android:name=".PingedApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:label="Pinged"
        android:theme="@style/Theme.Pinged">
    </application>
</manifest>
```

The two package identifiers are placeholders in the sense that they must be **verified on a real device** before they mean anything — Task 9 Step 6 is where that happens. They are not placeholders in the plan: write these two, verify them, add the rest as they are confirmed. Spec §9.6 explains why this list needs a release to grow while parse rules do not.

- [ ] **Step 5: Exclude the database from every backup path**

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </device-transfer>
</data-extraction-rules>
```

Both sections are required. `allowBackup="false"` alone does not cover device-to-device transfer on API 31+, and a database transferred without its Keystore key crash-loops on first launch (spec §11.1).

- [ ] **Step 6: Add the pure-Kotlin parse module**

`settings.gradle.kts` gains `include(":core:parse")`. `core/parse/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The compiler runs on JDK 25, but the emitted bytecode must stay at 17
// (class-file major 61). Toolchain 25 alone emits major 69, which D8
// rejects, so the first Android module to depend on this one fails to dex.
kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```

**The toolchain is not the target.** `jvmToolchain(25)` selects the JDK that
runs the compiler; on its own it also sets the emitted class-file version to
69. Verify with `javap -verbose` on a built class in
`core/parse/build/classes/kotlin/main/` — the major version must read 61.

Add `kotlin("jvm")`, `kotlin("plugin.serialization")`, `kotlinx-serialization-json` and `junit` to `gradle/libs.versions.toml`, taking the newest versions the catalog editor or Maven Central offers.

- [ ] **Step 7: Enforce the no-Android rule mechanically**

Add to `core/parse/build.gradle.kts`:

```kotlin
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group.startsWith("com.android") ||
            requested.group.startsWith("androidx")) {
            throw GradleException(
                "core:parse must stay Android-free (spec §3). Offending dependency: ${requested.group}:${requested.name}"
            )
        }
    }
}
```

The spec calls this module's Android-freedom the most important structural decision in the design. A build failure defends it; a comment does not.

- [ ] **Step 8: Prove the build works**

Run: `./gradlew test`
Expected: PASS, zero tests, no configuration errors.

Run: `./gradlew assembleRelease`
Expected: PASS, producing `app/build/outputs/apk/release/app-release-unsigned.apk` locally (unsigned, because no keystore env vars are set).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add the Gradle project skeleton

minSdk 27, applicationId my.pinged.tracker, no INTERNET permission, and the
database excluded from both cloud backup and device transfer. core:parse is
a pure-Kotlin module with a resolution strategy that fails the build if an
Android dependency is ever added to it.

The release workflow's first step now passes."
```

---

### Task 2: Amount and text normalization

**Files:**
- Create: `core/parse/src/main/kotlin/my/pinged/parse/TextNormalizer.kt`
- Create: `core/parse/src/main/kotlin/my/pinged/parse/Amount.kt`
- Test: `core/parse/src/test/kotlin/my/pinged/parse/TextNormalizerTest.kt`, `AmountTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `TextNormalizer.forMatch(String): String`, `TextNormalizer.forCompare(String): String`, `Amount.toSen(String): Long?`

- [ ] **Step 1: Write the failing tests**

`AmountTest.kt`:

```kotlin
package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountTest {
    @Test fun `plain amount`() = assertEquals(1200L, Amount.toSen("12.00"))
    @Test fun `with currency prefix`() = assertEquals(1200L, Amount.toSen("RM12.00"))
    @Test fun `with space after prefix`() = assertEquals(5000L, Amount.toSen("RM 50.00"))
    @Test fun `myr prefix`() = assertEquals(5000L, Amount.toSen("MYR50.00"))
    @Test fun `thousands separator`() = assertEquals(123450L, Amount.toSen("RM1,234.50"))
    @Test fun `no decimal part`() = assertEquals(1200L, Amount.toSen("RM12"))
    @Test fun `one decimal place`() = assertEquals(1250L, Amount.toSen("RM12.5"))
    @Test fun `non breaking space before amount`() =
        assertEquals(5000L, Amount.toSen("RM 50.00"))

    @Test fun `three decimals is rejected`() = assertNull(Amount.toSen("RM12.005"))
    @Test fun `zero is rejected`() = assertNull(Amount.toSen("RM0.00"))
    @Test fun `above the ceiling is rejected`() = assertNull(Amount.toSen("RM1,000,000.01"))
    @Test fun `negative is rejected`() = assertNull(Amount.toSen("-RM12.00"))
    @Test fun `letters are rejected`() = assertNull(Amount.toSen("RM12.00OFF"))
    @Test fun `empty is rejected`() = assertNull(Amount.toSen(""))
}
```

`TextNormalizerTest.kt`:

```kotlin
package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test fun `collapses runs of whitespace`() =
        assertEquals("a b", TextNormalizer.forMatch("a   \n  b"))

    @Test fun `non breaking space becomes a plain space`() =
        assertEquals("RM 50.00", TextNormalizer.forMatch("RM 50.00"))

    @Test fun `narrow no break space becomes a plain space`() =
        assertEquals("RM 50.00", TextNormalizer.forMatch("RM 50.00"))

    @Test fun `strips zero width and bidi marks`() =
        assertEquals("RM50.00", TextNormalizer.forMatch("RM​50.00‎"))

    @Test fun `forMatch preserves case`() =
        assertEquals("Payment To Ali", TextNormalizer.forMatch("Payment To Ali"))

    @Test fun `forCompare folds case with root locale`() =
        assertEquals("payment to ali", TextNormalizer.forCompare("Payment To ALI"))
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:parse:test`
Expected: FAIL — unresolved references `Amount` and `TextNormalizer`.

- [ ] **Step 3: Implement**

`TextNormalizer.kt`:

```kotlin
package my.pinged.parse

import java.util.Locale

object TextNormalizer {
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    private val SPACES = Regex("[\\p{Zs}\\t\\n\\r\\u000B\\u000C]+")

    fun forMatch(raw: String): String =
        SPACES.replace(INVISIBLE.replace(raw, ""), " ").trim()

    fun forCompare(raw: String): String = forMatch(raw).lowercase(Locale.ROOT)
}
```

`\p{Zs}` is the reason this exists. Java's `\s` does not match U+00A0 or U+202F, and bank notifications carry them around amounts routinely (spec §5.2). Skeleton equality in later tasks depends on this being right.

`Amount.kt`:

```kotlin
package my.pinged.parse

import java.math.BigDecimal
import java.util.Locale

object Amount {
    const val CEILING_SEN: Long = 100_000_000L // RM1,000,000.00

    private val SHAPE = Regex("^\\d{1,3}(,\\d{3})*(\\.\\d{1,2})?$|^\\d+(\\.\\d{1,2})?$")

    fun toSen(raw: String): Long? {
        val cleaned = TextNormalizer.forMatch(raw)
            .uppercase(Locale.ROOT)
            .removePrefix("MYR")
            .removePrefix("RM")
            .replace(" ", "")
            .trim()

        if (!SHAPE.matches(cleaned)) return null

        val sen = BigDecimal(cleaned.replace(",", ""))
            .movePointRight(2)
            .stripTrailingZeros()

        if (sen.scale() > 0) return null // finer than one sen

        val value = sen.toLong()
        return if (value <= 0L || value > CEILING_SEN) null else value
    }
}
```

`SHAPE` is what rejects `-RM12.00` and `RM12.00OFF` — it anchors both ends, so anything outside the grammar fails before `BigDecimal` sees it. Rejecting rather than throwing matters: a malformed amount must produce an unmatched capture, not a crash in the listener path.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :core:parse:test`
Expected: PASS, 21 tests.

- [ ] **Step 5: Commit**

```bash
git add core/parse
git commit -m "Add amount and text normalization

Amount.toSen rejects rather than throws: a malformed amount must become an
unmatched capture, never an exception on the listener path. Text
normalization uses \\p{Zs} because Java's \\s misses U+00A0 and U+202F, which
Malaysian bank notifications use around amounts."
```

---

### Task 3: Skeleton signatures and merchant cleanup

**Files:**
- Create: `core/parse/src/main/kotlin/my/pinged/parse/Skeleton.kt`, `Merchant.kt`
- Test: `core/parse/src/test/kotlin/my/pinged/parse/SkeletonTest.kt`, `MerchantTest.kt`

**Interfaces:**
- Consumes: `TextNormalizer` from Task 2
- Produces: `Skeleton.of(String): String`, `Merchant.clean(String): String`, `Merchant.display(String): String`

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class SkeletonTest {
    @Test fun `amount and account number both generalize`() = assertEquals(
        "your fd of rm# has matured. view details in mae.",
        Skeleton.of("Your FD of RM5,000.00 has matured. View details in MAE.")
    )

    @Test fun `two messages differing only in amount share a skeleton`() = assertEquals(
        Skeleton.of("RM88.00 telah ditolak dari akaun anda 1234."),
        Skeleton.of("RM1,250.75 telah ditolak dari akaun anda 9876.")
    )

    @Test fun `messages differing in wording do not share a skeleton`() {
        val a = Skeleton.of("RM88.00 telah ditolak dari akaun anda 1234.")
        val b = Skeleton.of("RM88.00 telah dikreditkan ke akaun anda 1234.")
        assert(a != b)
    }

    @Test fun `non breaking space does not defeat equality`() = assertEquals(
        Skeleton.of("RM 88.00 debited"),
        Skeleton.of("RM 88.00 debited")
    )
}

class MerchantTest {
    @Test fun `strips acquirer prefix`() =
        assertEquals("99SPEEDMART", Merchant.clean("TNG*99SPEEDMART"))

    @Test fun `strips duitnow qr prefix`() =
        assertEquals("RESTORAN ALI", Merchant.clean("DUITNOWQR-RESTORAN ALI"))

    @Test fun `strips corporate suffix`() =
        assertEquals("MACHINES", Merchant.clean("MACHINES SDN BHD"))

    @Test fun `strips trailing terminal code`() =
        assertEquals("RIDE", Merchant.clean("GRAB* RIDE-3KL"))

    @Test fun `title cases an all caps string`() =
        assertEquals("Restoran Ali", Merchant.display("RESTORAN ALI"))

    @Test fun `leaves mixed case alone`() =
        assertEquals("McDonald's", Merchant.display("McDonald's"))

    @Test fun `leaves a lower case string alone`() =
        assertEquals("foodpanda", Merchant.display("foodpanda"))
}
```

The last three matter for a reason worth stating: title-casing unconditionally produces "Tng 99speedmart" and "Mcdonald's" (spec §5.4). Only shouting gets corrected.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:parse:test`
Expected: FAIL — unresolved references `Skeleton` and `Merchant`.

- [ ] **Step 3: Implement**

```kotlin
package my.pinged.parse

object Skeleton {
    private val DIGIT_RUN = Regex("\\d[\\d,.]*\\d|\\d")

    fun of(raw: String): String =
        DIGIT_RUN.replace(TextNormalizer.forCompare(raw), "#")
}
```

```kotlin
package my.pinged.parse

import java.util.Locale

object Merchant {
    private val PREFIXES = listOf(
        "TNG*", "GRAB*", "PYMT-", "DUITNOWQR-", "DUITNOW-", "FPX-", "IBG-", "MBB-"
    )
    private val SUFFIXES = listOf(
        " SDN BHD", " SDN. BHD.", " SDN BHD.", " S/B", " BERHAD", " MY"
    )
    private val TERMINAL_CODE = Regex("-[A-Z0-9]{2,4}$")

    fun clean(raw: String): String {
        var s = TextNormalizer.forMatch(raw).uppercase(Locale.ROOT)
        PREFIXES.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it) }
        SUFFIXES.forEach { if (s.endsWith(it)) s = s.removeSuffix(it) }
        s = TERMINAL_CODE.replace(s, "")
        return s.trim().replace(Regex(" +"), " ")
    }

    fun display(raw: String): String {
        val trimmed = TextNormalizer.forMatch(raw)
        if (trimmed != trimmed.uppercase(Locale.ROOT)) return trimmed
        return trimmed.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word[0].uppercase(Locale.ROOT) + word.drop(1).lowercase(Locale.ROOT)
        }
    }
}
```

Both prefix and suffix lists belong in the pack rather than in code eventually (spec §5.4). They start here so Task 4 has something to test the pack loader against; moving them is a later task in a later plan, and the constant names are the seam.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :core:parse:test`
Expected: PASS, 33 tests.

- [ ] **Step 5: Commit**

```bash
git add core/parse
git commit -m "Add skeleton signatures and merchant cleanup

Skeleton generalizes every digit run, so two messages differing only in
amount and account number collapse to one signature, which is what sections
5.7 and 5.8 need. Title-casing only fires on an all-caps string, because
applying it unconditionally produces Mcdonald's."
```

---

### Task 4: The pack model and its loader

**Files:**
- Create: `core/parse/src/main/kotlin/my/pinged/parse/Pack.kt`, `Outcome.kt`
- Create: `core/parse/src/main/resources/pack.json`
- Test: `core/parse/src/test/kotlin/my/pinged/parse/PackTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `ParsePack`, `PackagePack`, `TemplateRule`, `RejectRule`, `Conditions`, `Direction`, `Confidence`, `Kind`, `MatchOutcome`, and `PackLoader.load(String): ParsePack`

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackTest {
    private val json = """
    {
      "pack_version": 7,
      "packages": [{
        "package": "my.com.tngdigital.ewallet",
        "label": "Touch 'n Go eWallet",
        "reject": [
          { "id": "promo", "any_of": ["cashback", "voucher"] }
        ],
        "rules": [
          { "id": "tng-payment-v1", "priority": 100,
            "direction": "EXPENSE", "confidence": "HIGH",
            "requires": { "text_contains_all": ["Payment of", "successful"] },
            "pattern": "Payment of RM(?<amount>[\\d,]+\\.\\d{2}) to (?<merchant>.+?) successful" },
          { "id": "tng-reload-v1", "priority": 90,
            "direction": "EXPENSE", "confidence": "REVIEW",
            "kind": "TRANSFER_SUSPECT", "exclusion_reason": "TRANSFER",
            "requires": { "text_contains_all": ["Reload"] },
            "pattern": "Reload of RM(?<amount>[\\d,]+\\.\\d{2})" }
        ]
      }]
    }
    """.trimIndent()

    @Test fun `loads version and package`() {
        val pack = PackLoader.load(json)
        assertEquals(7, pack.packVersion)
        assertEquals(1, pack.packages.size)
        assertEquals("my.com.tngdigital.ewallet", pack.packages[0].pkg)
    }

    @Test fun `rules arrive sorted by descending priority`() {
        val rules = PackLoader.load(json).packages[0].rules
        assertEquals(listOf("tng-payment-v1", "tng-reload-v1"), rules.map { it.id })
    }

    @Test fun `optional fields default rather than fail`() {
        val rule = PackLoader.load(json).packages[0].rules[0]
        assertEquals(null, rule.kind)
        assertEquals(null, rule.exclusionReason)
        assertEquals(Confidence.HIGH, rule.confidence)
    }

    @Test fun `transfer suspect is parsed`() {
        val rule = PackLoader.load(json).packages[0].rules[1]
        assertEquals(Kind.TRANSFER_SUSPECT, rule.kind)
        assertEquals(Confidence.REVIEW, rule.confidence)
    }

    @Test fun `a pattern without an amount group is rejected at load`() {
        val bad = json.replace("(?<amount>[\\d,]+\\.\\d{2})", "[\\d,]+\\.\\d{2}")
        val failure = runCatching { PackLoader.load(bad) }.exceptionOrNull()
        assertTrue(failure is PackValidationException)
        assertTrue(failure!!.message!!.contains("amount"))
    }

    @Test fun `an uncompilable pattern is rejected at load`() {
        val bad = json.replace("Payment of RM", "Payment of RM(")
        assertTrue(runCatching { PackLoader.load(bad) }.exceptionOrNull() is PackValidationException)
    }

    @Test fun `duplicate rule ids within a package are rejected`() {
        val bad = json.replace("tng-reload-v1", "tng-payment-v1")
        assertTrue(runCatching { PackLoader.load(bad) }.exceptionOrNull() is PackValidationException)
    }

    @Test fun `the bundled pack loads and validates`() {
        val text = PackTest::class.java.getResource("/pack.json")!!.readText()
        assertTrue(PackLoader.load(text).packages.isNotEmpty())
    }
}
```

Validation lives at load time, not at match time. A pack is the one artifact that can change how every future notification is read (spec §5.9), so it fails loudly on arrival rather than quietly at 2am.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:parse:test --tests '*PackTest*'`
Expected: FAIL — unresolved reference `PackLoader`.

- [ ] **Step 3: Implement the model and loader**

`Outcome.kt`:

```kotlin
package my.pinged.parse

enum class Direction { EXPENSE, REFUND }
enum class Confidence { HIGH, REVIEW }
enum class Kind { TRANSFER_SUSPECT }

sealed interface MatchOutcome {
    data class Matched(
        val ruleId: String,
        val amountSen: Long,
        val merchantRaw: String?,
        val direction: Direction,
        val confidence: Confidence,
        val kind: Kind?,
        val exclusionReason: String?,
        /** Non-null when a reject pattern also matched. Logged, never acted on. */
        val rejectCollisionId: String?,
    ) : MatchOutcome

    data class Rejected(val rejectRuleId: String) : MatchOutcome
    data object Unmatched : MatchOutcome
    data object NoExtras : MatchOutcome
}
```

`Pack.kt`:

```kotlin
package my.pinged.parse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PackValidationException(message: String) : IllegalArgumentException(message)

/**
 * Regex flags are fixed for every pattern (spec 5.2). They live here rather
 * than on RuleMatcher because PackLoader validates patterns and must not
 * depend on the matcher.
 *
 * Regex flags are fixed for every pattern (spec 5.2):
 * CASE_INSENSITIVE | UNICODE_CASE | DOTALL, never MULTILINE.
 *
 * Kotlin's RegexOption enum has no UNICODE_CASE member, so patterns are
 * compiled through java.util.regex.Pattern and converted. DOTALL matters
 * because bigText contains newlines and without it `(?<merchant>.+?)`
 * silently fails to match across one.
 */
object PackRegex {
    private const val FLAGS =
        java.util.regex.Pattern.CASE_INSENSITIVE or
        java.util.regex.Pattern.UNICODE_CASE or
        java.util.regex.Pattern.DOTALL

    fun compile(pattern: String): Regex =
        java.util.regex.Pattern.compile(pattern, FLAGS).toRegex()
}

@Serializable
data class Conditions(
    @SerialName("text_contains_all") val textContainsAll: List<String> = emptyList(),
    @SerialName("text_contains_any") val textContainsAny: List<String> = emptyList(),
    @SerialName("text_contains_none") val textContainsNone: List<String> = emptyList(),
    @SerialName("title_contains_any") val titleContainsAny: List<String> = emptyList(),
    val field: String = "concat",
)

@Serializable
data class RejectRule(
    val id: String,
    @SerialName("any_of") val anyOf: List<String>,
)

@Serializable
data class TemplateRule(
    val id: String,
    val priority: Int,
    val direction: Direction,
    val confidence: Confidence = Confidence.HIGH,
    val kind: Kind? = null,
    @SerialName("exclusion_reason") val exclusionReason: String? = null,
    val requires: Conditions? = null,
    val pattern: String,
)

@Serializable
data class PackagePack(
    @SerialName("package") val pkg: String,
    val label: String,
    val reject: List<RejectRule> = emptyList(),
    val rules: List<TemplateRule> = emptyList(),
)

@Serializable
data class ParsePack(
    @SerialName("pack_version") val packVersion: Int,
    val packages: List<PackagePack>,
)

object PackLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(text: String): ParsePack {
        val pack = try {
            json.decodeFromString<ParsePack>(text)
        } catch (e: Exception) {
            throw PackValidationException("Pack did not parse: ${e.message}")
        }
        pack.packages.forEach(::validate)
        return pack.copy(
            packages = pack.packages.map { it.copy(rules = it.rules.sortedByDescending(TemplateRule::priority)) }
        )
    }

    private fun validate(p: PackagePack) {
        val seen = mutableSetOf<String>()
        p.rules.forEach { rule ->
            if (!seen.add(rule.id)) {
                throw PackValidationException("Duplicate rule id '${rule.id}' in ${p.pkg}")
            }
            if (!rule.pattern.contains("(?<amount>")) {
                throw PackValidationException("Rule '${rule.id}' declares no amount group")
            }
            try {
                PackRegex.compile(rule.pattern)
            } catch (e: Exception) {
                throw PackValidationException("Rule '${rule.id}' pattern does not compile: ${e.message}")
            }
        }
    }
}
```

- [ ] **Step 4: Write the bundled pack**

`core/parse/src/main/resources/pack.json`. Two packages, both needing on-device verification of their identifier (Task 9):

```json
{
  "pack_version": 1,
  "packages": [
    {
      "package": "my.com.tngdigital.ewallet",
      "label": "Touch 'n Go eWallet",
      "reject": [
        { "id": "tng-otp", "any_of": ["OTP", "TAC", "do not share", "jangan kongsi"] },
        { "id": "tng-failed", "any_of": ["unsuccessful", "gagal", "failed", "declined"] },
        { "id": "tng-promo", "any_of": ["voucher", "diskaun", "% off"] }
      ],
      "rules": [
        {
          "id": "tng-payment-v1", "priority": 100,
          "direction": "EXPENSE", "confidence": "HIGH",
          "requires": { "text_contains_all": ["Payment of", "successful"] },
          "pattern": "Payment of RM\\s?(?<amount>[\\d,]+(?:\\.\\d{1,2})?) to (?<merchant>.+?) successful"
        },
        {
          "id": "tng-reload-v1", "priority": 90,
          "direction": "EXPENSE", "confidence": "REVIEW",
          "kind": "TRANSFER_SUSPECT", "exclusion_reason": "TRANSFER",
          "requires": { "text_contains_all": ["Reload"] },
          "pattern": "Reload of RM\\s?(?<amount>[\\d,]+(?:\\.\\d{1,2})?)"
        }
      ]
    },
    {
      "package": "com.maybank2u.life",
      "label": "Maybank MAE",
      "reject": [
        { "id": "mae-otp", "any_of": ["TAC", "one-time", "do not share"] },
        { "id": "mae-failed", "any_of": ["unsuccessful", "gagal", "declined"] }
      ],
      "rules": [
        {
          "id": "mae-duitnow-paid-v1", "priority": 100,
          "direction": "EXPENSE", "confidence": "HIGH",
          "requires": { "text_contains_all": ["You have paid"] },
          "pattern": "You have paid RM\\s?(?<amount>[\\d,]+(?:\\.\\d{1,2})?) to (?<merchant>.+)$"
        }
      ]
    }
  ]
}
```

Note the `cashback` keyword is deliberately **absent** from the promo reject lists. Spec §5.3: Malaysian wallets append reward copy to genuine success messages, and templates run first anyway — but leaving the keyword out removes the trap entirely. `voucher` and `% off` stay because they do not appear in success text.

Note the greedy-versus-lazy difference between the two merchant groups: `tng-payment-v1` is lazy because the literal `successful` follows it; `mae-duitnow-paid-v1` is greedy and `$`-anchored because the merchant runs to the end of the string. Getting this backwards is the most common rule-authoring mistake.

- [ ] **Step 5: Run to verify tests pass**

Run: `./gradlew :core:parse:test --tests '*PackTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add core/parse
git commit -m "Add the parser pack model, loader and bundled pack

Validation runs at load, not at match: a pack can change how every future
notification is read, so it fails on arrival rather than at 2am. Rules are
sorted by descending priority once at load rather than on every capture.

cashback is deliberately absent from the promo reject lists — section 5.3
explains why that word in a reject list eats real expenses."
```

---

### Task 5: The rule matcher, templates before rejects

This is the heart of the app. Everything else is plumbing around it.

**Files:**
- Create: `core/parse/src/main/kotlin/my/pinged/parse/Conditions.kt`, `Deadline.kt`, `RuleMatcher.kt`
- Test: `core/parse/src/test/kotlin/my/pinged/parse/RuleMatcherTest.kt`, `DeadlineTest.kt`

**Interfaces:**
- Consumes: `TextNormalizer`, `Amount`, `Merchant`, the Task 4 model
- Produces: `RuleMatcher(pack: ParsePack)` with `match(pkg: String, title: String?, text: String?, bigText: String?): MatchOutcome`

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.pinged.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuleMatcherTest {
    private lateinit var matcher: RuleMatcher
    private val tng = "my.com.tngdigital.ewallet"
    private val mae = "com.maybank2u.life"

    @Before fun setUp() {
        val text = javaClass.getResource("/pack.json")!!.readText()
        matcher = RuleMatcher(PackLoader.load(text))
    }

    @Test fun `a plain payment matches`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM12.00 to 99 SPEEDMART successful", null)
        out as MatchOutcome.Matched
        assertEquals("tng-payment-v1", out.ruleId)
        assertEquals(1200L, out.amountSen)
        assertEquals("99 SPEEDMART", out.merchantRaw)
        assertEquals(Direction.EXPENSE, out.direction)
        assertEquals(Confidence.HIGH, out.confidence)
    }

    @Test fun `the duitnow example from the design parses`() {
        val out = matcher.match(mae, "DuitNow Payment", "You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe", null)
        out as MatchOutcome.Matched
        assertEquals(1200L, out.amountSen)
        assertEquals("Restoran Yuen Kee Home Town Cafe", out.merchantRaw)
    }

    // Trailing reward copy must not perturb the amount extraction. Note this
    // does NOT by itself prove templates run before rejects: none of the
    // bundled tng reject terms (OTP, TAC, do not share, jangan kongsi,
    // unsuccessful, gagal, failed, declined, voucher, diskaun, % off) appear
    // in this message, so it passes identically under a reject-first
    // implementation. The actual ordering guard is the collision test below.
    @Test fun `trailing reward copy does not change the amount`() {
        val out = matcher.match(
            tng, "Touch 'n Go",
            "Payment of RM52.30 to 99 SPEEDMART successful. You earned RM1.05 cashback.",
            null,
        )
        out as MatchOutcome.Matched
        assertEquals(5230L, out.amountSen)
    }

    // The load-bearing test. Spec 5.3. A test-local pack deliberately puts a
    // reject keyword ("bonus") inside a message that also matches a
    // template, so a reject-first implementation fails this immediately.
    // The bundled production pack.json intentionally omits "cashback" from
    // its reject lists (see its comment), so it cannot exercise this path;
    // this pack exists only to construct a real collision without polluting
    // the production pack.
    @Test fun `a template match wins over a colliding reject and records the collision`() {
        val collidePack = """
            {
              "pack_version": 1,
              "packages": [
                {
                  "package": "com.example.collide",
                  "label": "Collision Test",
                  "reject": [
                    { "id": "collide-bonus", "any_of": ["bonus"] }
                  ],
                  "rules": [
                    {
                      "id": "collide-payment-v1", "priority": 100,
                      "direction": "EXPENSE", "confidence": "HIGH",
                      "requires": { "text_contains_all": ["Payment of", "successful"] },
                      "pattern": "Payment of RM\\s?(?<amount>[\\d,]+(?:\\.\\d{1,2})?) to (?<merchant>.+?) successful"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val collideMatcher = RuleMatcher(PackLoader.load(collidePack))

        val out = collideMatcher.match(
            "com.example.collide", "Test",
            "Payment of RM20.00 to Test Merchant successful. You earned a bonus.",
            null,
        )
        out as MatchOutcome.Matched
        assertEquals(2000L, out.amountSen)
        assertEquals("collide-bonus", out.rejectCollisionId)
    }

    @Test fun `a reload is flagged as a transfer suspect`() {
        val out = matcher.match(tng, "Touch 'n Go", "Reload of RM100.00 was successful", null)
        out as MatchOutcome.Matched
        assertEquals(Kind.TRANSFER_SUSPECT, out.kind)
        assertEquals(Confidence.REVIEW, out.confidence)
        assertEquals("TRANSFER", out.exclusionReason)
    }

    @Test fun `an otp message is rejected`() {
        val out = matcher.match(tng, "Touch 'n Go", "TAC 123456 for RM250.00 transfer. Do not share.", null)
        assertEquals("tng-otp", (out as MatchOutcome.Rejected).rejectRuleId)
    }

    @Test fun `a failed transaction is rejected`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM12.00 to 99 SPEEDMART unsuccessful", null)
        assertTrue(out is MatchOutcome.Rejected)
    }

    @Test fun `a promotional push produces nothing`() {
        val out = matcher.match(tng, "Touch 'n Go", "Get RM10 off your next order with this voucher", null)
        assertTrue(out is MatchOutcome.Rejected)
    }

    @Test fun `an amount alone is never a transaction`() {
        val out = matcher.match(tng, "Touch 'n Go", "Your balance is RM88.00", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }

    @Test fun `an unknown package produces nothing`() {
        val out = matcher.match("com.example.other", "x", "Payment of RM12.00 to X successful", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }

    @Test fun `all null fields produce NoExtras`() {
        assertEquals(MatchOutcome.NoExtras, matcher.match(tng, null, null, null))
    }

    @Test fun `bigText is preferred over text and matches across newlines`() {
        val out = matcher.match(
            tng, "Touch 'n Go", "Payment received",
            "Payment of RM30.00\nto 99 SPEEDMART successful",
        )
        out as MatchOutcome.Matched
        assertEquals(3000L, out.amountSen)
    }

    @Test fun `a non breaking space in the amount still matches`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM\u00A012.00 to 99 SPEEDMART successful", null)
        assertEquals(1200L, (out as MatchOutcome.Matched).amountSen)
    }

    @Test fun `an amount the normalizer rejects yields Unmatched not a crash`() {
        val out = matcher.match(tng, "Touch 'n Go", "Payment of RM0.00 to 99 SPEEDMART successful", null)
        assertEquals(MatchOutcome.Unmatched, out)
    }
}

class DeadlineTest {
    @Test fun `a catastrophic pattern gives up instead of hanging`() {
        val evil = PackRegex.compile("(a+)+b")
        val input = "a".repeat(40)
        val started = System.nanoTime()
        val result = runCatching {
            evil.containsMatchIn(DeadlineCharSequence(input, System.nanoTime() + 50_000_000L))
        }
        assertTrue(result.exceptionOrNull() is RegexTimeoutException)
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:parse:test --tests '*RuleMatcherTest*' --tests '*DeadlineTest*'`
Expected: FAIL — unresolved references `RuleMatcher`, `DeadlineCharSequence`.

- [ ] **Step 3: Implement the deadline guard**

```kotlin
package my.pinged.parse

class RegexTimeoutException : RuntimeException("Regex exceeded its time budget")

/**
 * Wraps a CharSequence so that regex backtracking cannot run away. The regex
 * engine reads through charAt, so throwing from it is the only way to
 * interrupt a match in progress. Section 5.9: packs arrive as files, and a
 * nested quantifier in one would otherwise hang a worker over 50,000 rows.
 */
class DeadlineCharSequence(
    private val delegate: CharSequence,
    private val deadlineNanos: Long,
) : CharSequence {
    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadlineNanos) throw RegexTimeoutException()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(delegate.subSequence(startIndex, endIndex), deadlineNanos)

    override fun toString(): String = delegate.toString()
}
```

- [ ] **Step 4: Implement condition evaluation**

```kotlin
package my.pinged.parse

internal object ConditionEvaluator {
    fun fieldFor(conditions: Conditions?, title: String?, text: String?, bigText: String?): String {
        val body = bigText ?: text
        return when (conditions?.field) {
            "title" -> title.orEmpty()
            "text" -> text.orEmpty()
            "bigText" -> bigText.orEmpty()
            else -> listOfNotNull(title, body).joinToString(" ")
        }.let(TextNormalizer::forMatch)
    }

    fun matches(conditions: Conditions?, field: String, title: String?): Boolean {
        if (conditions == null) return true
        val haystack = TextNormalizer.forCompare(field)
        val titleHay = TextNormalizer.forCompare(title.orEmpty())

        if (conditions.textContainsAll.any { !haystack.contains(TextNormalizer.forCompare(it)) }) return false
        if (conditions.textContainsAny.isNotEmpty() &&
            conditions.textContainsAny.none { haystack.contains(TextNormalizer.forCompare(it)) }
        ) return false
        if (conditions.textContainsNone.any { haystack.contains(TextNormalizer.forCompare(it)) }) return false
        if (conditions.titleContainsAny.isNotEmpty() &&
            conditions.titleContainsAny.none { titleHay.contains(TextNormalizer.forCompare(it)) }
        ) return false
        return true
    }
}
```

- [ ] **Step 5: Implement the matcher**

```kotlin
package my.pinged.parse

object RuleMatcherFlags {
    const val TIME_BUDGET_MILLIS = 50L
    const val MAX_INPUT_CHARS = 4_000
}

class RuleMatcher(pack: ParsePack) {

    /** Recorded on every capture this matcher parses, so an outcome is traceable to a pack. */
    val packVersion: Int = pack.packVersion

    private val byPackage: Map<String, PackagePack> = pack.packages.associateBy { it.pkg }
    private val compiled: Map<String, Regex> = pack.packages
        .flatMap { it.rules }
        .associate { it.id to PackRegex.compile(it.pattern) }

    fun match(pkg: String, title: String?, text: String?, bigText: String?): MatchOutcome {
        if (title == null && text == null && bigText == null) return MatchOutcome.NoExtras
        val pp = byPackage[pkg] ?: return MatchOutcome.Unmatched

        val field = ConditionEvaluator
            .fieldFor(null, title, text, bigText)
            .take(RuleMatcherFlags.MAX_INPUT_CHARS)
        if (field.isBlank()) return MatchOutcome.NoExtras

        // Templates first. A matched template is stronger evidence than a keyword.
        for (rule in pp.rules) {
            val ruleField = ConditionEvaluator
                .fieldFor(rule.requires, title, text, bigText)
                .take(RuleMatcherFlags.MAX_INPUT_CHARS)
            if (!ConditionEvaluator.matches(rule.requires, ruleField, title)) continue

            val match = runCatching {
                compiled.getValue(rule.id).find(
                    DeadlineCharSequence(
                        ruleField,
                        System.nanoTime() + RuleMatcherFlags.TIME_BUDGET_MILLIS * 1_000_000L,
                    )
                )
            }.getOrNull() ?: continue

            val amount = Amount.toSen(match.groups["amount"]?.value ?: "") ?: continue
            val merchant = runCatching { match.groups["merchant"]?.value }.getOrNull()

            return MatchOutcome.Matched(
                ruleId = rule.id,
                amountSen = amount,
                merchantRaw = merchant?.trim()?.takeIf { it.isNotEmpty() },
                direction = rule.direction,
                confidence = rule.confidence,
                kind = rule.kind,
                exclusionReason = rule.exclusionReason,
                rejectCollisionId = firstReject(pp, field),
            )
        }

        // Only now do rejects get a say.
        firstReject(pp, field)?.let { return MatchOutcome.Rejected(it) }
        return MatchOutcome.Unmatched
    }

    private fun firstReject(pp: PackagePack, field: String): String? {
        val haystack = TextNormalizer.forCompare(field)
        return pp.reject.firstOrNull { r ->
            r.anyOf.any { haystack.contains(TextNormalizer.forCompare(it)) }
        }?.id
    }
}
```

Three things in there are load-bearing and easy to undo by accident:

`rejectCollisionId` records that a reject also fired but does not act on it (spec §5.3). Someone will eventually "simplify" this into an early return; `a template match wins over a colliding reject and records the collision` is what stops them — it constructs a test-local pack with a deliberate reject/template collision, since the bundled production pack.json intentionally has no such collision to exercise. (An earlier draft of this plan pointed to the "cashback" test for this job; that test cannot detect a reject-first regression because none of the bundled tng reject terms appear in its input, so it passes identically either way. It is still useful — it proves trailing reward copy doesn't perturb the amount — just not as the ordering guard.)

`Amount.toSen(...) ?: continue` means a template that matches text but yields an unusable amount falls through to the next rule rather than producing a zero-value transaction.

`MAX_INPUT_CHARS` together with `DeadlineCharSequence` is the imported-pack safety net. Both are needed: the cap bounds the input, the deadline bounds the backtracking.

- [ ] **Step 6: Run to verify they pass**

Run: `./gradlew :core:parse:test`
Expected: PASS, 57 tests.

- [ ] **Step 7: Commit**

```bash
git add core/parse
git commit -m "Add the rule matcher: templates before rejects

The load-bearing test is 'a template match wins over a colliding reject and
records the collision', which builds a test-local pack with a deliberate
reject/template collision (the bundled pack.json has none to exercise).
Malaysian wallets append reward copy to genuine success notifications, so a
reject list consulted first eats real expenses — exactly the silent
under-report the flat model cannot detect. Templates win, and a reject that
also fired is recorded as a collision rather than acted on.

DeadlineCharSequence bounds regex backtracking by throwing from charAt,
which is the only way to interrupt a match in progress. Imported packs are
files, and one nested quantifier would otherwise hang a worker."
```

---

### Task 6: The fixture corpus

Rules are data, so coverage is a corpus rather than code. This task builds the harness that makes adding a bank cheap.

**Files:**
- Create: `core/parse/src/test/resources/fixtures/*.txt`
- Create: `core/parse/src/test/kotlin/my/pinged/parse/CorpusTest.kt`

**Interfaces:**
- Consumes: `RuleMatcher`, `PackLoader`
- Produces: a corpus format and a single parameterised test over it

- [ ] **Step 1: Define the fixture format and write the first files**

One file per case. Header lines, blank line, then the raw notification text verbatim.

`core/parse/src/test/resources/fixtures/tng-payment-speedmart.txt`:

```
package: my.com.tngdigital.ewallet
title: Touch 'n Go eWallet
expect: matched
rule: tng-payment-v1
amount_sen: 3200
merchant: 99 SPEEDMART

Payment of RM32.00 to 99 SPEEDMART successful. Balance: RM88.00
```

`core/parse/src/test/resources/fixtures/tng-payment-with-cashback.txt`:

```
package: my.com.tngdigital.ewallet
title: Touch 'n Go eWallet
expect: matched
rule: tng-payment-v1
amount_sen: 5230
merchant: 99 SPEEDMART

Payment of RM52.30 to 99 SPEEDMART successful. You earned RM1.05 cashback.
```

`core/parse/src/test/resources/fixtures/tng-tac-negative.txt`:

```
package: my.com.tngdigital.ewallet
title: Touch 'n Go eWallet
expect: rejected

TAC 884211 for RM250.00 transfer. Do not share this code with anyone.
```

`core/parse/src/test/resources/fixtures/tng-promo-negative.txt`:

```
package: my.com.tngdigital.ewallet
title: Touch 'n Go eWallet
expect: rejected

Jom! Get RM10 off your next order with this voucher, today only.
```

`core/parse/src/test/resources/fixtures/mae-duitnow-yuen-kee.txt`:

```
package: com.maybank2u.life
title: DuitNow Payment
expect: matched
rule: mae-duitnow-paid-v1
amount_sen: 1200
merchant: Restoran Yuen Kee Home Town Cafe

You have paid RM12.00 to Restoran Yuen Kee Home Town Cafe
```

`core/parse/src/test/resources/fixtures/mae-fd-matured-unmatched.txt`:

```
package: com.maybank2u.life
title: Maybank
expect: unmatched

Your FD of RM5,000.00 has matured. View details in MAE.
```

Note that last one is `unmatched`, not `rejected`: it is the case section 5.7 exists for, and it should reach the unread list so the user can mark it never-a-transaction.

Every fixture is redacted of real account numbers before it is committed.

- [ ] **Step 2: Write the corpus test**

```kotlin
package my.pinged.parse

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusTest {

    private data class Fixture(
        val name: String,
        val pkg: String,
        val title: String?,
        val bigText: String?,
        val expect: String,
        val rule: String?,
        val amountSen: Long?,
        val merchant: String?,
        val body: String,
    )

    private fun parse(file: File): Fixture {
        val (header, body) = file.readText().split("\n\n", limit = 2)
        val h = header.lineSequence()
            .filter { it.contains(':') }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return Fixture(
            name = file.name,
            pkg = requireNotNull(h["package"]) { "${file.name} has no package" },
            title = h["title"],
            bigText = h["big_text"],
            expect = requireNotNull(h["expect"]) { "${file.name} has no expect" },
            rule = h["rule"],
            amountSen = h["amount_sen"]?.toLong(),
            merchant = h["merchant"],
            body = body.trim(),
        )
    }

    @Test fun `every fixture produces its expected outcome`() {
        val matcher = RuleMatcher(PackLoader.load(javaClass.getResource("/pack.json")!!.readText()))
        val dir = File(javaClass.getResource("/fixtures")!!.toURI())
        val files = dir.listFiles { f: File -> f.extension == "txt" }.orEmpty().sortedBy { it.name }

        assertTrue("No fixtures found", files.isNotEmpty())

        val failures = mutableListOf<String>()
        files.map(::parse).forEach { fx ->
            val outcome = matcher.match(fx.pkg, fx.title, fx.body, fx.bigText)
            when (fx.expect) {
                "matched" -> {
                    if (outcome !is MatchOutcome.Matched) {
                        failures += "${fx.name}: expected matched, got $outcome"
                    } else {
                        if (fx.rule != null && outcome.ruleId != fx.rule) {
                            failures += "${fx.name}: rule ${outcome.ruleId}, expected ${fx.rule}"
                        }
                        if (fx.amountSen != null && outcome.amountSen != fx.amountSen) {
                            failures += "${fx.name}: amount ${outcome.amountSen}, expected ${fx.amountSen}"
                        }
                        if (fx.merchant != null && outcome.merchantRaw != fx.merchant) {
                            failures += "${fx.name}: merchant '${outcome.merchantRaw}', expected '${fx.merchant}'"
                        }
                    }
                }
                "rejected" -> if (outcome !is MatchOutcome.Rejected) {
                    failures += "${fx.name}: expected rejected, got $outcome"
                }
                "unmatched" -> if (outcome != MatchOutcome.Unmatched) {
                    failures += "${fx.name}: expected unmatched, got $outcome"
                }
                else -> failures += "${fx.name}: unknown expect '${fx.expect}'"
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `the corpus contains negative fixtures`() {
        val dir = File(javaClass.getResource("/fixtures")!!.toURI())
        val expects = dir.listFiles { f: File -> f.extension == "txt" }.orEmpty()
            .map { parse(it).expect }
        assertTrue("A corpus with no negative fixtures proves nothing", expects.contains("rejected"))
        assertTrue(expects.contains("unmatched"))
    }
}
```

Collecting failures and asserting once means a rule change reports every fixture it broke, not just the first. That is the difference between a corpus you use and one you avoid.

The second test is a guard on the corpus itself. Spec §13: negative fixtures are the point, and a corpus that drifts into only happy paths stops defending default-deny.

- [ ] **Step 3: Run to verify it passes**

Run: `./gradlew :core:parse:test --tests '*CorpusTest*'`
Expected: PASS, 2 tests, 6 fixtures exercised.

- [ ] **Step 4: Break something on purpose and confirm the corpus catches it**

Temporarily change `tng-payment-v1`'s `requires.text_contains_all` from `["Payment of", "successful"]` to `["Payment of", "successfully"]` (one letter) in `pack.json`. No fixture text contains "successfully", so that template stops matching.

Run: `./gradlew :core:parse:test --tests '*CorpusTest*'`
Expected: FAIL naming BOTH `tng-payment-speedmart.txt` AND `tng-payment-with-cashback.txt`.

The double naming proves the harness's collect-all-failures design works: a rule change reports every fixture it broke, not just the first. Revert the change and confirm the corpus passes again. This step is not ceremony: it proves the corpus actually fails when the parser regresses, which is the only thing that makes the other 57 tests trustworthy.

Reject patterns checked first cannot work for this experiment because templates are now evaluated before rejects (spec §5.3): once a template matches, reject patterns are never consulted, so a reject-list change cannot break a matching fixture.

- [ ] **Step 5: Commit**

```bash
git add core/parse
git commit -m "Add the fixture corpus and its harness

One file per case, header plus verbatim notification text, so adding a bank
means adding files rather than writing test code. Failures are collected and
asserted once, so a rule change reports every fixture it broke.

A second test asserts the corpus still contains negative fixtures. A corpus
that drifts into only happy paths stops defending default-deny, and that
drift is invisible without a guard."
```

---

### Task 7: The database, its key, and a migration harness

**Files:**
- Create: `core/data/build.gradle.kts`
- Create: `core/data/src/main/kotlin/my/pinged/data/entity/{RawCapture,Txn,Category,CaptureSource,MerchantRule,CaptureDay}.kt`
- Create: `core/data/src/main/kotlin/my/pinged/data/{PingedDatabase,DatabaseKey,DatabaseFactory}.kt`
- Create: `core/data/src/main/kotlin/my/pinged/data/dao/{RawCaptureDao,TxnDao,CategoryDao,CaptureSourceDao,CaptureDayDao}.kt`
  as **minimal `@Dao` interfaces only**. `PingedDatabase` below declares an
  accessor for each, so Room's processor fails at compile time if the
  interface is absent -- this task cannot compile without them. Task 8 adds
  the query methods to these same files; it does not create them.
- Create: `core/data/schemas/` (committed, generated)
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/{OpenTest,MigrationTest,QueryPlanTest}.kt`
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt` — moved
  here from Task 8, because Task 7's own OpenTest calls `sampleCapture()`.

**Interfaces:**
- Consumes: `Direction`, `Confidence` from `:core:parse`
- Produces: `PingedDatabase` with `rawCaptureDao()`, `txnDao()`, `categoryDao()`, `captureSourceDao()`, `captureDayDao()`; `DatabaseFactory.build(Context): PingedDatabase`; entity classes and `ParseStatus`

- [ ] **Step 1: Add the module**

`settings.gradle.kts` gains `include(":core:data")`. `core/data/build.gradle.kts`:

```kotlin
plugins {
    // AGP 9 has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is a HARD ERROR, not a warning: "The
    // 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
    // support since AGP 9.0." Verified during Task 1b, which is why :app
    // applies only the AGP plugin. Every Android module in this plan follows
    // that shape, and the kotlin-android catalog alias does not exist.
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "my.pinged.data"
    compileSdk = 36
    // Bytecode target is set through compileOptions, not a kotlin{} block:
    // with AGP's built-in Kotlin there is no kotlin extension to configure
    // here. :app does the same (Task 1b).
    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
}

dependencies {
    implementation(project(":core:parse"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
```

Add `androidx.room`, `net.zetetic:sqlcipher-android`, `androidx.sqlite`, `com.google.devtools.ksp` and `androidx.test:runner` to the version catalog.

**As built, with the two version choices that are not "newest wins":**

| Coordinate | Version | Why not the newest |
|---|---|---|
| `androidx.room:*` | 2.8.4 | newest |
| `net.zetetic:sqlcipher-android` | **4.17.0** | 4.18.0 declares `minCompileSdk=37` in its AAR metadata and fails the build outright against `compileSdk 36`. Verified by reading the metadata of both: 4.18.0 is 37, 4.17.0 is 1. **Do not bump this without bumping `compileSdk`**, which is a spec decision, not a dependency chore. |
| `androidx.sqlite:sqlite` | **2.6.2** | 2.7.0 exists, but `room-runtime-2.8.4.pom` declares 2.6.2, and Room's generated code plus SQLCipher's `SupportOpenHelperFactory` both sit on this API surface. Match Room's own pin. |
| `com.google.devtools.ksp` | 2.3.11 | newest. KSP2 versions no longer encode the Kotlin version; there is no 2.4.x. Works against Kotlin 2.4.10. |
| `androidx.test:runner` | 1.7.0 | newest |
| `androidx.test.ext:junit` | 1.3.0 | newest |

Also note two build-file facts the original text omitted: the root
`build.gradle.kts` needs `alias(libs.plugins.android.library) apply false`
and the same for `ksp`, or the subproject fails with "already on the
classpath with an unknown version"; and `androidTest.assets.srcDirs` must
include `$projectDir/schemas`, because `MigrationTestHelper` reads the
exported schema from the test APK's assets and `room.schemaLocation` alone
does not put it there.

`:core:data` depends on `:core:parse` with `api`, not `implementation`:
`Txn` exposes `Direction` and `Confidence` in its public signature, so
`implementation` would leave consumers unable to name the types they get
back.

`room.schemaLocation` and committing `schemas/` from v1 is not optional. Spec §13 promises migrations tested from every released version, and that is impossible retroactively without the exported schema.

- [ ] **Step 2: Write the entities**

`RawCapture.kt`:

```kotlin
package my.pinged.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ParseStatus { NEW, MATCHED, UNMATCHED, REJECTED, DUPLICATE_OF, UPDATE_OF, NO_EXTRAS }

@Entity(
    tableName = "raw_capture",
    indices = [
        Index("content_hash"),
        Index("sbn_key"),
        Index(value = ["parse_status", "posted_at"]),
    ],
)
data class RawCapture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_package") val sourcePackage: String,
    /** sbn.postTime. Never notification.when — see spec section 4. */
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    /** notification.when, kept but trusted only when non-zero. */
    @ColumnInfo(name = "when_millis") val whenMillis: Long?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "sbn_key") val sbnKey: String,
    @ColumnInfo(name = "notif_id") val notifId: Int,
    @ColumnInfo(name = "notif_tag") val notifTag: String?,
    @ColumnInfo(name = "user_handle") val userHandle: Int,
    @ColumnInfo(name = "channel_id") val channelId: String?,
    val flags: Int,
    val title: String?,
    val text: String?,
    @ColumnInfo(name = "big_text") val bigText: String?,
    @ColumnInfo(name = "sub_text") val subText: String?,
    @ColumnInfo(name = "extras_json") val extrasJson: String?,
    /** sha256(package, user, normalized text). No timestamp — see spec 7.2. */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "parse_status") val parseStatus: ParseStatus = ParseStatus.NEW,
    @ColumnInfo(name = "rejected_by_rule_id") val rejectedByRuleId: String? = null,
    @ColumnInfo(name = "matched_rule_id") val matchedRuleId: String? = null,
    @ColumnInfo(name = "reject_collision_id") val rejectCollisionId: String? = null,
    @ColumnInfo(name = "pack_version") val packVersion: Int = 0,
    @ColumnInfo(name = "duplicate_of_id") val duplicateOfId: Long? = null,
    @ColumnInfo(name = "user_reject_rule_id") val userRejectRuleId: Long? = null,
)
```

**Every column carries `@ColumnInfo`.** Room derives column names from property
names, so without these the `@Index` entries and every snake_case `@Query`
below would reference columns that do not exist — a build failure at best and
a wrong index at worst. Add `import androidx.room.ColumnInfo`.

`Txn.kt`:

```kotlin
package my.pinged.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import my.pinged.parse.Confidence
import my.pinged.parse.Direction

enum class TxnState { COMMITTED, PENDING, REJECTED }
enum class ExclusionReason { TRANSFER, CARD_PAYMENT, ATM_WITHDRAWAL, USER }

@Entity(
    tableName = "txn",
    indices = [
        Index("local_date"),
        Index(value = ["amount_sen", "occurred_at"]),
        Index(value = ["state", "occurred_at"]),
        Index("raw_capture_id", unique = true),
    ],
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_capture_id") val rawCaptureId: Long?,
    @ColumnInfo(name = "amount_sen") val amountSen: Long,
    val currency: String = "MYR",
    val direction: Direction,
    /** yyyymmdd in the device zone, computed once. Spec 15.7. */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "local_date") val localDate: Int,
    @ColumnInfo(name = "merchant_raw") val merchantRaw: String?,
    @ColumnInfo(name = "merchant_display") val merchantDisplay: String?,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "source_package") val sourcePackage: String?,
    @ColumnInfo(name = "source_label") val sourceLabel: String?,
    val confidence: Confidence,
    val state: TxnState,
    @ColumnInfo(name = "is_excluded") val isExcluded: Boolean = false,
    @ColumnInfo(name = "exclusion_reason") val exclusionReason: ExclusionReason? = null,
    val note: String? = null,
    @ColumnInfo(name = "user_edited") val userEdited: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

None of these indices are partial. Room's `@Index` has no `where` clause (spec §15.1), so the review inbox uses a composite on `(state, occurred_at)` that serves both the badge count and the ordered list.

`raw_capture_id` is uniquely indexed. That is what makes stage two idempotent: a re-run cannot produce a second transaction for the same capture.

`Category.kt`, `CaptureSource.kt`, `MerchantRule.kt`:

```kotlin
package my.pinged.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "category")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String,
    val sortOrder: Int,
    val isProtected: Boolean = false,
)

@Entity(tableName = "capture_source")
data class CaptureSource(
    @PrimaryKey val pkg: String,
    val label: String,
    val enabled: Boolean = false,
    val isAuthoritative: Boolean = false,
    val firstSeenAt: Long,
    val lastNotificationAt: Long? = null,
    val expectedMonthlyCount: Int? = null,
)

@Entity(tableName = "merchant_rule")
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchType: String, // EXACT on save, CONTAINS only after spec 6.2
    val pattern: String,
    val merchantDisplay: String,
    val categoryId: Long,
    val origin: String, // BUNDLED | LEARNED
    val priority: Int,
    val hitCount: Int = 0,
    val scopedPackage: String? = null,
)

// Spec section 4. One row per local date, so the daily rhythm grid can tell
// "you spent nothing" apart from "Pinged was not watching" -- both of which
// are an absence of txn rows. Nothing reads this until charts ship, and
// nothing can backfill it, which is why it is recorded from milestone 1.
@Entity(tableName = "capture_day")
data class CaptureDay(
    @PrimaryKey val localDate: Int,
    val listenerBound: Boolean,
    val sawAnyNotification: Boolean,
)
```

- [ ] **Step 3: Write the Keystore-wrapped raw key**

```kotlin
package my.pinged.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DatabaseKeyUnavailableException(message: String) : IllegalStateException(message)

/**
 * Produces the SQLCipher raw key. The stored blob is a 32-byte key plus a
 * 16-byte salt, AES-GCM wrapped by a non-exportable Keystore key, held in
 * getNoBackupFilesDir() so no backup path can carry it (spec 11.1).
 */
object DatabaseKey {
    private const val ALIAS = "pinged.db.wrap"
    private const val FILE = "db.key"
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun rawKeyPassphrase(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        val material = if (file.exists()) unwrap(file.readBytes()) else create(file)
        val hex = material.joinToString("") { "%02x".format(it) }
        // SQLCipher treats an x'...' passphrase as a raw key and skips PBKDF2
        // entirely. At 256,000 default iterations that KDF would otherwise be
        // paid on every cold listener start (spec 15.2).
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    fun exists(context: Context): Boolean = File(context.noBackupFilesDir, FILE).exists()

    fun destroy(context: Context) {
        File(context.noBackupFilesDir, FILE).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    private fun create(file: File): ByteArray {
        val material = ByteArray(KEY_BYTES + SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey())
        }
        file.writeBytes(cipher.iv + cipher.doFinal(material))
        return material
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(body)
            }
        } catch (e: Exception) {
            // Happens after a device-to-device transfer, and after an OTA that
            // invalidates the Keystore entry. The caller must tell the user and
            // offer a fresh start or a JSON restore. It must never crash-loop.
            throw DatabaseKeyUnavailableException(
                "The database key could not be unwrapped: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}
```

- [ ] **Step 4: Write the database and factory**

```kotlin
package my.pinged.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category
import my.pinged.data.entity.MerchantRule
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        RawCapture::class, Txn::class, Category::class, CaptureSource::class,
        MerchantRule::class, CaptureDay::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PingedDatabase : RoomDatabase() {
    abstract fun rawCaptureDao(): RawCaptureDao
    abstract fun txnDao(): TxnDao
    abstract fun categoryDao(): CategoryDao
    abstract fun captureSourceDao(): CaptureSourceDao
    abstract fun captureDayDao(): CaptureDayDao
}

object DatabaseFactory {
    const val NAME = "pinged.db"

    fun build(context: Context): PingedDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(DatabaseKey.rawKeyPassphrase(context))
        return Room.databaseBuilder(context, PingedDatabase::class.java, NAME)
            .openHelperFactory(factory)
            .build()
    }
}
```

- [ ] **Step 5: Write the instrumented tests**

```kotlin
package my.pinged.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun opensAndRoundTripsACapture() {
        val db = DatabaseFactory.build(context)
        val dao = db.rawCaptureDao()
        val id = dao.insert(sampleCapture())
        assertEquals(1, dao.countAll())
        assertTrue(id > 0)
        db.close()
    }

    @Test fun rawKeyModeMakesOpeningFast() {
        DatabaseFactory.build(context).close()
        val started = System.nanoTime()
        DatabaseFactory.build(context).use { it.rawCaptureDao().countAll() }
        val millis = (System.nanoTime() - started) / 1_000_000
        // With PBKDF2 at 256,000 iterations this is hundreds of ms. Raw key
        // mode skips the KDF, so a regression here means someone passed a
        // plain passphrase instead of the x'...' form (spec 15.2).
        assertTrue("Database open took ${millis}ms; expected under 150", millis < 150)
    }
}

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    // The constructor that accepts an open-helper factory has changed shape
    // across Room versions. Use whichever overload the pinned version offers;
    // what matters is that the factory is passed at all.
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PingedDatabase::class.java,
        emptyList(),
        // Without this the helper opens a PLAIN database and the migration
        // tests exercise a schema the app does not ship (spec 13).
        SupportOpenHelperFactory(
            DatabaseKey.rawKeyPassphrase(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
        ),
    )

    @Test fun schemaVersionOneIsCreatable() {
        helper.createDatabase("migration-test.db", 1).close()
    }
}

@RunWith(AndroidJUnit4::class)
class QueryPlanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun duplicateWindowQueryDoesNotScan() {
        val db = DatabaseFactory.build(context)
        val plan = StringBuilder()
        db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM txn WHERE amount_sen = ? AND occurred_at BETWEEN ? AND ?",
            arrayOf(1200, 0, Long.MAX_VALUE),
        ).use { c -> while (c.moveToNext()) plan.append(c.getString(c.columnCount - 1)).append('\n') }

        // A substring assertion on purpose: plan output is not stable across
        // SQLite versions and SQLCipher bundles its own build (spec 15.8).
        assertTrue("Plan scans instead of searching:\n$plan", plan.contains("SEARCH"))
        assertTrue("Plan contains a full table scan:\n$plan", !plan.contains("SCAN txn"))
        db.close()
    }
}
```

- [ ] **Step 6: Run the instrumented tests on a device or emulator**

Run: `./gradlew :core:data:connectedAndroidTest`
Expected: PASS. If `rawKeyModeMakesOpeningFast` fails, the passphrase is not in `x'...'` form and PBKDF2 is running.

- [ ] **Step 7: Confirm the schema was exported**

Run: `ls core/data/schemas/my.pinged.data.PingedDatabase/1.json`
Expected: the file exists. Commit it — it is the baseline every future migration test compares against.

- [ ] **Step 8: Commit**

```bash
git add core/data settings.gradle.kts gradle/libs.versions.toml
git commit -m "Add the encrypted database, its key, and a migration harness

SQLCipher in raw key mode. The passphrase is the x'...' hex form, which
skips PBKDF2 entirely — at 256,000 default iterations that KDF would be paid
on every cold listener start, on the critical path of capturing a
notification. A test asserts the open stays under 150ms so a regression to
plain-passphrase mode fails rather than just gets slower.

The Keystore-wrapped key lives in getNoBackupFilesDir(), and unwrap failure
raises DatabaseKeyUnavailableException rather than crashing: it is the
expected state after a device-to-device transfer.

MigrationTestHelper is given the SQLCipher factory, without which the
migration tests would exercise an unencrypted schema the app never ships.
Schema JSON is exported and committed from v1, because migrations cannot be
tested from a version whose schema was never recorded.

No index is partial: Room's @Index has no where clause, so the review inbox
uses a composite on (state, occurred_at)."
```

---

### Task 8: DAOs and the stage-two queue

**Files:**
- Modify: `core/data/src/main/kotlin/my/pinged/data/dao/{RawCaptureDao,TxnDao,CategoryDao,CaptureSourceDao}.kt`
  — Task 7 created these as minimal `@Dao` interfaces so its `@Database` could
  compile. This task adds the query methods. `CaptureDayDao` needs only an
  `upsert`, which Task 7 already wrote.
- Create: `core/data/src/main/kotlin/my/pinged/data/LocalDate.kt` — Task 7's
  `Fixtures.kt` deliberately ships only `sampleCapture()`, because
  `sampleTxn()` calls `LocalDates.of(...)` which does not exist until this
  file does.
- Create: `core/data/src/main/kotlin/my/pinged/data/Seed.kt`
- Modify: `core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt` — add
  `sampleTxn`, `enabledSource`, `disabledSource`. The file and its `useDb {}`
  helper already exist from Task 7.
- Test: `core/data/src/androidTest/kotlin/my/pinged/data/DaoTest.kt`

**Interfaces:**
- Consumes: Task 7 entities
- Produces: `RawCaptureDao.claimNext(limit: Int): List<RawCapture>`, `RawCaptureDao.findByContentHash(hash: String, sinceMillis: Long): List<RawCapture>`, `TxnDao.insert(txn: Txn): Long`, `TxnDao.findDuplicateSuspects(amountSen: Long, from: Long, to: Long, excludePackage: String): List<Txn>`, `LocalDates.of(epochMillis: Long): Int`, `Seed.categories(): List<Category>`

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.pinged.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.pinged.data.entity.ParseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: PingedDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DatabaseFactory.NAME)
        db = DatabaseFactory.build(context)
        db.categoryDao().insertAll(Seed.categories())
    }

    @Test fun seedsFourteenCategoriesWithUncategorizedProtected() {
        val all = db.categoryDao().all()
        assertEquals(14, all.size)
        val uncategorized = all.single { it.name == "Uncategorized" }
        assertTrue(uncategorized.isProtected)
    }

    @Test fun claimNextReturnsOnlyNewRowsInArrivalOrder() {
        val dao = db.rawCaptureDao()
        dao.insert(sampleCapture(hash = "a", postedAt = 200))
        dao.insert(sampleCapture(hash = "b", postedAt = 100))
        dao.insert(sampleCapture(hash = "c", postedAt = 300, status = ParseStatus.MATCHED))

        val claimed = dao.claimNext(10)
        assertEquals(listOf("b", "a"), claimed.map { it.contentHash })
    }

    @Test fun findByContentHashRespectsTheWindow() {
        val dao = db.rawCaptureDao()
        dao.insert(sampleCapture(hash = "dup", postedAt = 1_000_000))
        assertEquals(1, dao.findByContentHash("dup", 1_000_000 - 60_000).size)
        assertEquals(0, dao.findByContentHash("dup", 1_000_000 + 1).size)
    }

    @Test fun duplicateSuspectsExcludeTheSamePackage() {
        val txnDao = db.txnDao()
        txnDao.insert(sampleTxn(amountSen = 12_000, occurredAt = 5_000, pkg = "com.a"))
        assertEquals(1, txnDao.findDuplicateSuspects(12_000, 0, 10_000, "com.b").size)
        assertEquals(0, txnDao.findDuplicateSuspects(12_000, 0, 10_000, "com.a").size)
    }

    @Test fun oneCaptureCannotProduceTwoTransactions() {
        val capture = db.rawCaptureDao().insert(sampleCapture(hash = "once"))
        db.txnDao().insert(sampleTxn(rawCaptureId = capture))
        val second = runCatching { db.txnDao().insert(sampleTxn(rawCaptureId = capture)) }
        assertTrue("The unique index on raw_capture_id did not hold", second.isFailure)
    }

    @Test fun localDateIsStableForTheSameDay() {
        val morning = 1_790_000_000_000L
        val evening = morning + 8 * 60 * 60 * 1000
        assertEquals(LocalDates.of(morning), LocalDates.of(evening))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :core:data:connectedAndroidTest`
Expected: FAIL — unresolved references `Seed`, `LocalDates`, DAO methods.

- [ ] **Step 3: Implement the local-date helper and the seed**

```kotlin
package my.pinged.data

import java.time.Instant
import java.time.ZoneId

object LocalDates {
    /** yyyymmdd in the device zone. Stored, never derived at query time (spec 15.7). */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return d.year * 10_000 + d.monthValue * 100 + d.dayOfMonth
    }
}
```

```kotlin
package my.pinged.data

import my.pinged.data.entity.Category

object Seed {
    /** Spec section 4. Order and icon keys are fixed. */
    fun categories(): List<Category> = listOf(
        Category(name = "Food & Drinks", iconKey = "utensils", sortOrder = 0),
        Category(name = "Groceries", iconKey = "shopping-basket", sortOrder = 1),
        Category(name = "Transport", iconKey = "car", sortOrder = 2),
        Category(name = "Petrol & tolls", iconKey = "fuel", sortOrder = 3),
        Category(name = "Bills & utilities", iconKey = "zap", sortOrder = 4),
        Category(name = "Telco & internet", iconKey = "wifi", sortOrder = 5),
        Category(name = "Shopping", iconKey = "shopping-bag", sortOrder = 6),
        Category(name = "Health", iconKey = "heart-pulse", sortOrder = 7),
        Category(name = "Education", iconKey = "graduation-cap", sortOrder = 8),
        Category(name = "Family", iconKey = "users", sortOrder = 9),
        Category(name = "Religious & zakat", iconKey = "hand-heart", sortOrder = 10),
        Category(name = "Government & fees", iconKey = "landmark", sortOrder = 11),
        Category(name = "Entertainment", iconKey = "ticket", sortOrder = 12),
        Category(name = "Uncategorized", iconKey = "circle-dashed", sortOrder = 13, isProtected = true),
    )
}
```

- [ ] **Step 4: Implement the DAOs**

```kotlin
package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture

@Dao
interface RawCaptureDao {
    @Insert
    fun insert(capture: RawCapture): Long

    @Query("SELECT COUNT(*) FROM raw_capture")
    fun countAll(): Int

    /** Stage two's work queue. Uses the (parse_status, posted_at) index. */
    @Query(
        "SELECT * FROM raw_capture WHERE parse_status = :status " +
            "ORDER BY posted_at ASC LIMIT :limit"
    )
    fun claimNext(limit: Int, status: ParseStatus = ParseStatus.NEW): List<RawCapture>

    // No parse_status filter. An earlier version of this query carried
    // "AND parse_status != 'NEW'", which contradicted this task's own test
    // and would have disabled duplicate detection in the exact case it
    // exists for. Spec 7.2 layer 1 rule 2 is "same content_hash from the
    // same package and user within 60 seconds, with a different sbn_key" --
    // it says nothing about parse status, and the common duplicate is two
    // notifications seconds apart, where the first is still NEW because the
    // stage-two worker has not run yet. Excluding NEW rows means the
    // 60-second window can only fire against captures that were already
    // parsed, i.e. almost never.
    //
    // Same package and user need no clause: content_hash is computed from
    // (pkg, user, text), so an equal hash already implies both (Task 9's
    // ContentHash.of).
    @Query(
        "SELECT * FROM raw_capture WHERE content_hash = :hash " +
            "AND posted_at >= :sinceMillis"
    )
    fun findByContentHash(hash: String, sinceMillis: Long): List<RawCapture>

    @Query("SELECT * FROM raw_capture WHERE sbn_key = :key AND content_hash = :hash LIMIT 1")
    fun findBySlotAndContent(key: String, hash: String): RawCapture?

    @Query(
        "UPDATE raw_capture SET parse_status = :status, matched_rule_id = :ruleId, " +
            "rejected_by_rule_id = :rejectId, reject_collision_id = :collisionId, " +
            "duplicate_of_id = :duplicateOf, pack_version = :packVersion WHERE id = :id"
    )
    fun markOutcome(
        id: Long,
        status: ParseStatus,
        ruleId: String?,
        rejectId: String?,
        collisionId: String?,
        duplicateOf: Long?,
        packVersion: Int,
    )
}
```

```kotlin
package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import my.pinged.data.entity.Txn

@Dao
interface TxnDao {
    @Insert
    fun insert(txn: Txn): Long

    @Query("SELECT COUNT(*) FROM txn")
    fun countAll(): Int

    @Query(
        "SELECT * FROM txn WHERE amount_sen = :amountSen " +
            "AND occurred_at BETWEEN :from AND :to " +
            "AND (source_package IS NULL OR source_package != :excludePackage) " +
            "AND state != 'REJECTED'"
    )
    fun findDuplicateSuspects(
        amountSen: Long,
        from: Long,
        to: Long,
        excludePackage: String,
    ): List<Txn>

    @Query("SELECT * FROM txn ORDER BY occurred_at DESC LIMIT :limit")
    fun recent(limit: Int): List<Txn>
}
```

```kotlin
package my.pinged.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import my.pinged.data.entity.CaptureSource
import my.pinged.data.entity.Category

@Dao
interface CategoryDao {
    @Insert
    fun insertAll(categories: List<Category>)

    @Query("SELECT * FROM category ORDER BY sort_order ASC")
    fun all(): List<Category>

    @Query("SELECT id FROM category WHERE name = 'Uncategorized' LIMIT 1")
    fun uncategorizedId(): Long
}

@Dao
interface CaptureSourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(source: CaptureSource)

    @Query("SELECT * FROM capture_source WHERE enabled = 1")
    fun enabled(): List<CaptureSource>

    @Query("SELECT * FROM capture_source WHERE pkg = :pkg LIMIT 1")
    fun byPackage(pkg: String): CaptureSource?

    @Query("SELECT * FROM capture_source ORDER BY label ASC")
    fun all(): List<CaptureSource>
}
```

`RawCaptureDao` also needs, used by later tasks:

```kotlin
    @Query("SELECT * FROM raw_capture WHERE id = :id")
    fun byId(id: Long): RawCapture

    @Query("SELECT * FROM raw_capture ORDER BY id ASC LIMIT :limit OFFSET :offset")
    fun page(limit: Int, offset: Int): List<RawCapture>
```

and `TxnDao` the same `page(limit, offset)` ordered by `id`.

Finally the shared test helper every later task's tests use. Put it in
`core/data/src/androidTest/kotlin/my/pinged/data/Fixtures.kt`:

```kotlin
package my.pinged.data

import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.Confidence
import my.pinged.parse.Direction

fun sampleCapture(
    hash: String = "hash-0",
    postedAt: Long = 1_000L,
    pkg: String = "my.com.tngdigital.ewallet",
    text: String? = "Payment of RM1.00 to A successful",
    sbnKey: String = "key-$hash",
    status: ParseStatus = ParseStatus.NEW,
) = RawCapture(
    sourcePackage = pkg,
    postedAt = postedAt,
    whenMillis = null,
    capturedAt = postedAt,
    sbnKey = sbnKey,
    notifId = 1,
    notifTag = null,
    userHandle = 0,
    channelId = "txn",
    flags = 0,
    title = "Touch 'n Go eWallet",
    text = text,
    bigText = null,
    subText = null,
    extrasJson = null,
    contentHash = hash,
    parseStatus = status,
)

fun sampleTxn(
    amountSen: Long = 1_000L,
    occurredAt: Long = 1_000L,
    pkg: String? = "my.com.tngdigital.ewallet",
    rawCaptureId: Long? = null,
    categoryId: Long = 1L,
) = Txn(
    rawCaptureId = rawCaptureId,
    amountSen = amountSen,
    direction = Direction.EXPENSE,
    occurredAt = occurredAt,
    localDate = LocalDates.of(occurredAt),
    merchantRaw = "A",
    merchantDisplay = "A",
    categoryId = categoryId,
    sourcePackage = pkg,
    sourceLabel = null,
    confidence = Confidence.HIGH,
    state = TxnState.COMMITTED,
    createdAt = occurredAt,
    updatedAt = occurredAt,
)

fun enabledSource(pkg: String) = my.pinged.data.entity.CaptureSource(
    pkg = pkg, label = pkg, enabled = true, firstSeenAt = 0L,
)

fun disabledSource(pkg: String) = my.pinged.data.entity.CaptureSource(
    pkg = pkg, label = pkg, enabled = false, firstSeenAt = 0L,
)
```

**Nothing to do here about column names — and doing it here would have been
a bug.** An earlier version of this step told Task 8 to add
`@ColumnInfo(name = "sort_order")` to `Category` and `@ColumnInfo` to four
`CaptureSource` fields. Those are column *renames*, and Task 7 is where
`schemas/1.json` is generated and version 1 frozen. Renaming columns here
would rewrite the v1 schema under an unchanged version number, which is
exactly the "a migration harness that cannot see the previous schema"
failure Task 7 exists to prevent. Task 7 therefore applied the snake_case
names to every entity up front, matching spec section 4 verbatim. Verify
against the committed `1.json` rather than adding annotations.

- [ ] **Step 5: Run to verify they pass**

Run: `./gradlew :core:data:connectedAndroidTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/data
git commit -m "Add DAOs, the stage-two queue, category seed and local-date helper

The work queue is a table query on (parse_status, posted_at), not an
in-memory queue, so an interrupted run resumes: any row still at NEW is
unfinished work and process death loses nothing.

A unique index on txn.raw_capture_id makes stage two idempotent, and a test
asserts a second insert for the same capture fails rather than silently
double-counting."
```

---

### Task 9: Stage one, the listener

The class name written here is frozen forever. The notification-access grant is stored against its flattened `ComponentName`, so renaming it later silently voids the grant with no callback and no visible event.

**Files:**
- Create: `feature/capture/build.gradle.kts`
- Create: `feature/capture/src/main/kotlin/my/pinged/capture/{NotificationFields,PingedNotificationListener,ContentHash,CaptureHealth,SourceCounters}.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `feature/capture/src/test/kotlin/my/pinged/capture/{NotificationFieldsTest,ContentHashTest}.kt` (Robolectric), `feature/capture/src/androidTest/kotlin/my/pinged/capture/ListenerTest.kt`

**Interfaces:**
- Consumes: `RawCaptureDao`, `RawCapture`, `TextNormalizer`
- Produces: `NotificationFields.from(sbn: StatusBarNotification): NotificationFields?`, `ContentHash.of(pkg: String, user: Int, text: String): String`, `CaptureHealth.recordSeen()`, `SourceCounters.increment(pkg: String)`

- [ ] **Step 1: Write the failing extraction tests**

These need Robolectric, because building a real `Notification` is the only way to test the thing that actually breaks.

```kotlin
package my.pinged.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationFieldsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sbn(notification: Notification): StatusBarNotification =
        // The only usable public constructor, verified with javap against
        // platforms/android-36/android.jar:
        //   (String pkg, String opPkg, int id, String tag, int uid, int initialPid,
        //    int score, Notification n, UserHandle user, long postTime)
        // Three ints before the notification, one trailing long. An earlier
        // draft passed two ints and two longs and did not compile.
        @Suppress("DEPRECATION")
        StatusBarNotification("com.example", null, 1, "tag", 0, 0, 0, notification, android.os.Process.myUserHandle(), 1_000L)

    // The bug that decides whether this product works at all.
    @Test fun `a spannable text field is extracted, not dropped`() {
        val bolded = SpannableString("Payment of RM12.00 to X successful").apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 11, 18, 0)
        }
        val n = Notification.Builder(context, "c").setContentTitle("Bank").setContentText(bolded).build()
        val fields = NotificationFields.from(sbn(n))!!
        assertEquals("Payment of RM12.00 to X successful", fields.text)
    }

    @Test fun `bigText is captured alongside text`() {
        val n = Notification.Builder(context, "c")
            .setContentTitle("Bank")
            .setContentText("short")
            .setStyle(Notification.BigTextStyle().bigText("the long version"))
            .build()
        val fields = NotificationFields.from(sbn(n))!!
        assertEquals("the long version", fields.bigText)
    }

    @Test fun `a notification with no text at all yields all nulls`() {
        val n = Notification.Builder(context, "c").build()
        val fields = NotificationFields.from(sbn(n))!!
        assertNull(fields.title)
        assertNull(fields.text)
        assertNull(fields.bigText)
    }

    @Test fun `postTime is used and a zero when is not`() {
        val n = Notification.Builder(context, "c").setContentText("x").setWhen(0L).build()
        val fields = NotificationFields.from(sbn(n))!!
        assertEquals(1_000L, fields.postedAt)
        assertNull(fields.whenMillis)
    }

    @Test fun `a non zero when is kept`() {
        val n = Notification.Builder(context, "c").setContentText("x").setWhen(900L).build()
        assertEquals(900L, NotificationFields.from(sbn(n))!!.whenMillis)
    }
}

@RunWith(RobolectricTestRunner::class)
class ContentHashTest {
    @Test fun `the same content hashes the same regardless of time`() = assertEquals(
        ContentHash.of("com.a", 0, "Payment of RM12.00"),
        ContentHash.of("com.a", 0, "Payment  of   RM12.00"),
    )

    @Test fun `a different user handle hashes differently`() {
        assert(ContentHash.of("com.a", 0, "x") != ContentHash.of("com.a", 10, "x"))
    }
}
```

`postTime is used and a zero when is not` is the fix for the 1970 bug: `notification.when` is app-controlled and frequently zero, and a zero there dates a transaction to 1 January 1970 where it vanishes from every month view while still counting as captured (spec §4).

- [ ] **Step 2: Run to verify they fail**

Add `testImplementation(libs.robolectric)` and `testOptions { unitTests.isIncludeAndroidResources = true }` to `feature/capture/build.gradle.kts`.

Run: `./gradlew :feature:capture:test`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement extraction and hashing**

```kotlin
package my.pinged.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import my.pinged.parse.TextNormalizer

data class NotificationFields(
    val sourcePackage: String,
    val postedAt: Long,
    val whenMillis: Long?,
    val sbnKey: String,
    val notifId: Int,
    val notifTag: String?,
    val userHandle: Int,
    val channelId: String?,
    val flags: Int,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
) {
    val hasAnyText: Boolean get() = title != null || text != null || bigText != null

    val normalizedForHash: String
        get() = TextNormalizer.forCompare(listOfNotNull(title, bigText ?: text).joinToString(" "))

    companion object {
        fun from(sbn: StatusBarNotification): NotificationFields? {
        val extras = sbn.notification?.extras ?: return null
        // getCharSequence, never getString: getString returns null for a
        // SpannableString, and banks bold the amount (spec section 3).
        fun str(key: String) = extras.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        return NotificationFields(
            sourcePackage = sbn.packageName,
            postedAt = sbn.postTime,
            whenMillis = sbn.notification.`when`.takeIf { it > 0L },
            sbnKey = sbn.key,
            notifId = sbn.id,
            notifTag = sbn.tag,
            userHandle = sbn.user.hashCode(),
            channelId = sbn.notification.channelId,
            flags = sbn.notification.flags,
            title = str(Notification.EXTRA_TITLE),
            text = str(Notification.EXTRA_TEXT),
            bigText = str(Notification.EXTRA_BIG_TEXT),
                subText = str(Notification.EXTRA_SUB_TEXT),
            )
        }
    }
}
```

A `data class` and an `object` cannot share a name in one file. The factory is a
companion, and the body above is indented to match.

```kotlin
package my.pinged.capture

import java.security.MessageDigest

object ContentHash {
    /** No timestamp. Identity is content; recency is a separate predicate (spec 7.2). */
    fun of(pkg: String, user: Int, normalizedText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$pkg|$user|$normalizedText".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 4: Implement the throttled heartbeat and counters**

```kotlin
package my.pinged.capture

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.captureStore by preferencesDataStore("capture_health")

/**
 * DataStore, not Room, and throttled. A phone posts 100-300 notifications a
 * day; Room's invalidation tracker is table-granular, so a heartbeat row
 * would re-emit every Flow observing that table on every notification on the
 * device (spec 10.2).
 */
object CaptureHealth {
    private val LAST_SEEN = longPreferencesKey("last_any_notification_at")
    private val LAST_CONNECTED = longPreferencesKey("last_listener_connected_at")
    private const val THROTTLE_MILLIS = 5 * 60 * 1000L

    @Volatile private var lastWrittenAt = 0L

    suspend fun recordSeen(context: Context, now: Long) {
        if (now - lastWrittenAt < THROTTLE_MILLIS) return
        lastWrittenAt = now
        context.captureStore.edit { it[LAST_SEEN] = now }
    }

    suspend fun recordConnected(context: Context, now: Long) {
        context.captureStore.edit { it[LAST_CONNECTED] = now }
    }

    suspend fun lastSeenAt(context: Context): Long =
        context.captureStore.data.first()[LAST_SEEN] ?: 0L

    /**
     * The one thing that does go to Room, at most once per calendar day.
     *
     * DataStore answers "is capture alive now", which is all the banner
     * needs. It cannot answer "was capture alive on 14 August", and the
     * daily rhythm grid (spec section 8) has to tell "you spent nothing"
     * apart from "Pinged was not watching" -- both of which are an absence
     * of txn rows. Nothing can backfill this, so it is recorded from
     * milestone 1 even though nothing reads it until charts ship.
     *
     * Gated on the local date having changed, so this is one write a day,
     * not one per notification. That is why it may live in Room while
     * LAST_SEEN may not.
     */
    private val LAST_DAY_MARKED = intPreferencesKey("last_day_marked")

    suspend fun markDayCaptured(context: Context, localDate: Int, dao: CaptureDayDao) {
        val marked = context.captureStore.data.first()[LAST_DAY_MARKED] ?: 0
        if (marked == localDate) return
        // recordNotificationSeen, NOT a whole-row upsert. Task 8 deleted
        // CaptureDayDao.upsert precisely because REPLACE is a delete-and-insert
        // that erases the other writer's column: this writer knows a
        // notification arrived and knows nothing about listener_bound, so a
        // whole-row write would clear a binding observation made earlier today.
        // capture_day is the only thing that distinguishes "spent nothing" from
        // "was not watching", so that erasure is not recoverable.
        dao.recordNotificationSeen(localDate)
        context.captureStore.edit { it[LAST_DAY_MARKED] = localDate }
    }
}

object SourceCounters {
    /** A count only, never a last-seen timestamp (spec 9.6). */
    suspend fun increment(context: Context, pkg: String) {
        val key = intPreferencesKey("seen:$pkg")
        context.captureStore.edit { it[key] = (it[key] ?: 0) + 1 }
    }

    suspend fun seenCount(context: Context, pkg: String): Int =
        context.captureStore.data.first()[intPreferencesKey("seen:$pkg")] ?: 0
}
```

- [ ] **Step 5: Implement the listener**

```kotlin
package my.pinged.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import my.pinged.data.entity.RawCapture

/**
 * STAGE ONE ONLY. Extract, filter, one insert. Everything else is ParseWorker.
 *
 * DO NOT RENAME OR MOVE THIS CLASS. The notification-access grant is stored
 * by the system against its flattened ComponentName; renaming voids the grant
 * silently, with no callback and nothing visible to the user.
 */
class PingedNotificationListener : NotificationListenerService() {

    // One thread, so inserts arrive in the order notifications did. A
    // single-thread executor rather than limitedParallelism, which is still an
    // opt-in experimental API.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun onListenerConnected() {
        scope.launch {
            CaptureHealth.recordConnected(applicationContext, System.currentTimeMillis())
            // Narrows the gap after a kill: bank notifications often sit in the
            // shade for hours (spec section 3).
            //
            // Arrival.CATCHUP is not bookkeeping. getActiveNotifications()
            // returns only posts that are STILL LIVE, and "the notification is
            // still posted" is what spec 7.2's duplicate rule 1 actually means
            // by a refresh -- far more precisely than elapsed time does. So a
            // capture arriving this way is exempt from rule 1's ten-minute
            // window, and without the distinction every rebind after an app
            // update or a reboot would re-deliver each live banking
            // notification as a review item.
            activeNotifications?.forEach { ingest(it, Arrival.CATCHUP) }
        }
    }

    override fun onListenerDisconnected() {
        requestRebind(PingedComponents.listener(applicationContext))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scope.launch { ingest(sbn, Arrival.POSTED) }
    }

    // `arrival` is a parameter and not derived inside, because both delivery
    // paths funnel through here and nothing observable inside tells them
    // apart. Nothing can reconstruct it afterwards either, which is why spec 4
    // records it on the row.
    private suspend fun ingest(sbn: StatusBarNotification, arrival: Arrival) {
        val now = System.currentTimeMillis()

        if (sbn.packageName == applicationContext.packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        CaptureHealth.recordSeen(applicationContext, now)

        val source = Graph.captureSourceDao(applicationContext).byPackage(sbn.packageName)
        if (source?.enabled != true) {
            // Metadata only. Nothing content-bearing is written (spec 9.6).
            SourceCounters.increment(applicationContext, sbn.packageName)
            return
        }

        val fields = NotificationFields.from(sbn) ?: return

        Graph.rawCaptureDao(applicationContext).insert(
            RawCapture(
                sourcePackage = fields.sourcePackage,
                postedAt = fields.postedAt,
                whenMillis = fields.whenMillis,
                capturedAt = now,
                sbnKey = fields.sbnKey,
                notifId = fields.notifId,
                notifTag = fields.notifTag,
                userHandle = fields.userHandle,
                channelId = fields.channelId,
                flags = fields.flags,
                // Required: RawCapture declares `arrival` with no default, on
                // purpose, so a new call site cannot silently omit it.
                arrival = arrival,
                title = fields.title,
                text = fields.text,
                bigText = fields.bigText,
                subText = fields.subText,
                extrasJson = null,
                contentHash = ContentHash.of(
                    fields.sourcePackage, fields.userHandle, fields.normalizedForHash,
                ),
            )
        )

        ParseWorker.enqueue(applicationContext)
    }
}
```

`Graph` is the whole of this milestone's dependency wiring. Manual, because a
DI framework here would be scaffolding with one consumer. Every later task
uses exactly this surface:

```kotlin
package my.pinged.capture

import android.content.Context
import my.pinged.data.DatabaseFactory
import my.pinged.data.PingedDatabase
import my.pinged.data.dao.CaptureSourceDao
import my.pinged.data.dao.CategoryDao
import my.pinged.data.dao.RawCaptureDao
import my.pinged.data.dao.TxnDao
import my.pinged.parse.PackLoader
import my.pinged.parse.RuleMatcher

object Graph {
    @Volatile private var db: PingedDatabase? = null
    @Volatile private var matcher: RuleMatcher? = null
    @Volatile private var uncategorized: Long? = null

    fun database(context: Context): PingedDatabase =
        db ?: synchronized(this) {
            db ?: DatabaseFactory.build(context.applicationContext).also { db = it }
        }

    fun rawCaptureDao(context: Context): RawCaptureDao = database(context).rawCaptureDao()
    fun txnDao(context: Context): TxnDao = database(context).txnDao()
    fun categoryDao(context: Context): CategoryDao = database(context).categoryDao()
    fun captureSourceDao(context: Context): CaptureSourceDao = database(context).captureSourceDao()

    fun ruleMatcher(context: Context): RuleMatcher =
        matcher ?: synchronized(this) {
            matcher ?: RuleMatcher(
                PackLoader.load(
                    context.assets.open("pack.json").bufferedReader().use { it.readText() }
                )
            ).also { matcher = it }
        }

    fun uncategorizedId(context: Context): Long =
        uncategorized ?: categoryDao(context).uncategorizedId().also { uncategorized = it }

    /** Tests only: forces the next call to rebuild. */
    fun reset() = synchronized(this) { db = null; matcher = null; uncategorized = null }
}
```

`PingedComponents.listener(context)` is defined in Task 11 and returns the
frozen component name. Write it there; the listener's `onListenerDisconnected`
above references it.

The pack is read from `assets/pack.json` rather than from the `:core:parse`
resources, because a JVM module's resources are not packaged into the APK's
asset path. Copy `core/parse/src/main/resources/pack.json` to
`app/src/main/assets/pack.json` with a Gradle `Copy` task so the corpus tests
and the app read the same bytes:

```kotlin
val syncPack by tasks.registering(Copy::class) {
    from(project(":core:parse").file("src/main/resources/pack.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(syncPack) }
```

Two copies drifting apart would mean the tests pass against a pack the app
does not use, which is the worst possible failure for a corpus-driven design.

- [ ] **Step 6: Register the service and verify the package identifiers on a real device**

`app/src/main/AndroidManifest.xml` inside `<application>`:

```xml
<service
    android:name="my.pinged.capture.PingedNotificationListener"
    android:exported="false"
    android:label="Pinged"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

Then, on a real device with the banking apps installed:

```bash
adb shell pm list packages | grep -iE 'tng|touchngo|maybank|cimb|grab|setel'
```

Correct `<queries>` in the manifest and `pack.json` to the identifiers this prints. Two things to check while you are there, both of which the spec flags as assumptions rather than facts:

- On API 33+, does the notification-access toggle appear greyed with "For your security, this setting is currently unavailable"? If so, the restricted-settings path (App info, overflow, Allow restricted settings) is mandatory in onboarding. Test both an `adb install` and a file-manager install — the install source differs.
- Does receiving a notification from a package make it resolvable through `PackageManager` without it being in `<queries>`? Spec §9.6 assumes it does not. Confirm.

Record what you find in `docs/device-notes.md`. This is the step that turns two guessed identifiers into real ones.

- [ ] **Step 7: Write the instrumented listener test**

```kotlin
package my.pinged.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListenerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun grantNotificationAccess() {
        val component = PingedComponents.listener(context).flattenToString()
        instrumentation.uiAutomation.executeShellCommand(
            "cmd notification allow_listener $component"
        ).close()
        // Give the system a moment to bind before posting.
        Thread.sleep(2_000)
    }

    @Test fun aNotificationFromAnEnabledPackageIsCaptured() {
        Graph.captureSourceDao(context).upsert(enabledSource(context.packageName))
        val before = Graph.rawCaptureDao(context).countAll()

        postTestNotification("Payment of RM12.00 to TEST MERCHANT successful")
        Thread.sleep(3_000)

        assertEquals(before + 1, Graph.rawCaptureDao(context).countAll())
    }

    @Test fun aNotificationFromADisabledPackageStoresNothing() {
        Graph.captureSourceDao(context).upsert(disabledSource(context.packageName))
        val before = Graph.rawCaptureDao(context).countAll()

        postTestNotification("Payment of RM99.00 to SHOULD NOT APPEAR successful")
        Thread.sleep(3_000)

        // The invariant the whole privacy posture rests on (spec 9.6).
        assertEquals(before, Graph.rawCaptureDao(context).countAll())
    }
}
```

The listener skips its own package, so `postTestNotification` must post from a separate test-only package, or the test must temporarily allow the app's own package through a build-config flag. Take the flag: a second APK is disproportionate here, and the flag is `false` in release.

- [ ] **Step 8: Run everything**

Run: `./gradlew :feature:capture:test :feature:capture:connectedAndroidTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add feature/capture app/src/main/AndroidManifest.xml docs/device-notes.md
git commit -m "Add stage one: the notification listener

Extract, filter, one insert. Fields are read with getCharSequence, not
getString, which returns null for a SpannableString — banks bold the amount,
and this single line decides whether they are captured at all. A Robolectric
test builds a real bolded Notification and asserts the text survives.

postTime is authoritative and a zero notification.when is discarded, so a
capture cannot be dated to 1970 and vanish from every month view while still
counting as captured.

Own package and group summaries are skipped. Disabled packages increment a
DataStore counter and write nothing content-bearing, which is the invariant
the privacy posture rests on and is covered by an instrumented test.

The listener class name is frozen: the grant is stored against its
ComponentName."
```

---

### Gaps Task 9 surfaced and correctly did not absorb

Recorded here rather than in the execution ledger, because the ledger decays
and this file is what the next implementer reads.

**`capture_source.last_notification_at` has no writer anywhere in this plan.**
A grep for `setLastNotificationAt` across every task returns nothing. Spec
section 10.3 makes per-source liveness the detector for the likelier failure —
the listener is alive but one bank's notification channel got muted, so
capture looks healthy while one source has gone quiet — and asserts "the data
is already being written". It is not. Task 9 correctly left it alone: stage
one is extraction, the allow-list gate and one insert, and this would be a
second write on the per-notification path. It belongs in Task 11, next to the
rest of the liveness work, and it must be a targeted column update rather than
a whole-row upsert for the reason Task 8 removed `CaptureSourceDao.upsert`.

**Where stage one's boundary actually falls.** "No parsing in stage one" cannot
be read as "no `:core:parse` calls at all": `content_hash` is a column of the
row stage one writes, spec section 4 defines it as sha256 over *normalized*
text, so `TextNormalizer.forCompare` necessarily runs there. What stage one
does not do is rule matching, reject patterns, `RuleMatcher`, the duplicate
queries or the confidence gate — the expensive, pack-versioned work whose
results have to be re-derivable on a pack upgrade. Normalization is none of
those: it is stable, cheap, and the hash would be useless without it. The
stricter reading, storing raw text and hashing in stage two, would make
`content_hash` nullable and cost the duplicate check its index on the write
path; it is rejected on those grounds and not merely for convenience.

---

### Task 10: Stage two, the parse worker

> **The Kotlin in this task predates the database review and is stale in
> seven specific ways. Treat the snippets as intent and write against the
> real `:core:data` surface, which you must read first.** Each divergence
> below exists because a defect was fixed after this task was written, so
> copying the snippet reintroduces the defect.
>
> 1. **`findBySlotAndContent(sbnKey, contentHash)` no longer exists.** It is
>    `findEarlierInSlot(key, hash, selfId)`, and `selfId` is not optional:
>    stage one inserts before stage two claims, so without it the query
>    matches *the row being processed* and every capture becomes an
>    `UPDATE_OF` with no transaction ever created.
> 2. **Duplicate layer 1 rule 1 now has a ten-minute window, and a
>    `CATCHUP` exemption.** Use `findEarlierInSlotSince` for an
>    `Arrival.POSTED` capture and the unwindowed form for
>    `Arrival.CATCHUP`, per spec section 7.2 — which I rewrote. Beyond the
>    window the pair is not dropped: it becomes a transaction flagged
>    `DUPLICATE_SUSPECT` and therefore `PENDING`.
> 3. **`findByContentHash` takes three arguments now** — `hash`,
>    `sinceMillis`, `untilMillis`. The upper bound has no default on
>    purpose: unbounded, it marks an older capture a duplicate of a purchase
>    that happened months later, during re-parse.
> 4. **Do not `runCatching { txns.insert(txn) }`.** Use
>    `RawCaptureDao.commitCapture`, the `@Transaction` that inserts the
>    transaction and marks the capture together. The snippet's shape is the
>    exact lost-atomicity bug: a kill between the two leaves the capture
>    `NEW` with its transaction already written, it sorts first under
>    `claimNext`, and the unique index then throws forever.
> 5. **`state = TxnState.COMMITTED` unconditionally is wrong.** Spec
>    section 7.1's gate decides, and whatever it decides must be recorded in
>    the new `pending_reason` column, which is non-null exactly when `state`
>    is `PENDING` and enforced at the write path. A `PENDING` row with no
>    reason throws.
> 6. **The `when` has no `GaveUp` branch.** `MatchOutcome.GaveUp` exists and
>    now has a home, `ParseStatus.GAVE_UP`. Do not fold it into
>    `UNMATCHED`: a timeout is not evidence that no rule matches, section
>    5.5 says re-parse must revisit it, and folding it makes a descheduled
>    worker look like a rule gap in section 5.6's authoring loop.
> 7. **`markOutcome` takes an `expected` status and returns `Int`.** A
>    zero-row update means another writer got there first; treat that as a
>    signal, not as success.


**Files:**
- Create: `feature/capture/src/main/kotlin/my/pinged/capture/{ParseWorker,Dedup}.kt`
- Test: `feature/capture/src/androidTest/kotlin/my/pinged/capture/ParseWorkerTest.kt`

**Interfaces:**
- Consumes: `RuleMatcher`, `RawCaptureDao`, `TxnDao`, `LocalDates`, `Merchant`
- Produces: `ParseWorker.enqueue(context: Context)`, and the invariant that every `raw_capture` row leaves `NEW`

- [ ] **Step 1: Write the failing tests**

```kotlin
package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.TxnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParseWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun runWorker() =
        TestListenableWorkerBuilder<ParseWorker>(context).build().startWork().get()

    @Before fun clean() {
        context.deleteDatabase(my.pinged.data.DatabaseFactory.NAME)
        Graph.categoryDao(context).insertAll(my.pinged.data.Seed.categories())
    }

    @Test fun aMatchedCaptureBecomesACommittedTransaction() {
        val id = insertNewCapture("Payment of RM32.00 to 99 SPEEDMART successful")
        runWorker()

        val txn = Graph.txnDao(context).recent(1).single()
        assertEquals(3200L, txn.amountSen)
        assertEquals("99 SPEEDMART", txn.merchantRaw)
        assertEquals("99 Speedmart", txn.merchantDisplay)
        assertEquals(TxnState.COMMITTED, txn.state)
        assertNotNull(txn.localDate)
        assertEquals(ParseStatus.MATCHED, Graph.rawCaptureDao(context).byId(id).parseStatus)
    }

    @Test fun aRejectedCaptureProducesNoTransaction() {
        insertNewCapture("TAC 123456 for RM250.00 transfer. Do not share.")
        runWorker()
        assertEquals(0, Graph.txnDao(context).countAll())
    }

    @Test fun anUnmatchedCaptureIsMarkedAndLeftForTheUnreadList() {
        val id = insertNewCapture("Your FD of RM5,000.00 has matured.")
        runWorker()
        assertEquals(ParseStatus.UNMATCHED, Graph.rawCaptureDao(context).byId(id).parseStatus)
        assertEquals(0, Graph.txnDao(context).countAll())
    }

    @Test fun theSameSlotAndContentIsAnUpdateNotASecondTransaction() {
        insertNewCapture("Payment of RM32.00 to 99 SPEEDMART successful", sbnKey = "k1")
        runWorker()
        val second = insertNewCapture("Payment of RM32.00 to 99 SPEEDMART successful", sbnKey = "k1")
        runWorker()

        assertEquals(ParseStatus.UPDATE_OF, Graph.rawCaptureDao(context).byId(second).parseStatus)
        assertEquals(1, Graph.txnDao(context).countAll())
    }

    // INVERTED. This test used to assert UPDATE_OF and one transaction an hour
    // later, and that assertion was itself the wrong-money bug: content_hash
    // carries no timestamp, so two genuinely separate identical payments have
    // the same identity by construction, and an unbounded rule 1 dropped the
    // second one silently. Spec 7.2 was rewritten to bound it. An hour is
    // beyond the ten-minute window, so the second payment must reach the
    // ledger -- as a DUPLICATE_SUSPECT the user can Merge or Keep both, never
    // as a silent drop.
    @Test fun anHourLaterIsASecondTransactionForReview() {
        insertNewCapture("Payment of RM32.00 to 99 SPEEDMART successful", sbnKey = "k1", postedAt = 0)
        runWorker()
        val later = insertNewCapture(
            "Payment of RM32.00 to 99 SPEEDMART successful", sbnKey = "k1", postedAt = 3_600_000,
        )
        runWorker()
        assertEquals(ParseStatus.MATCHED, Graph.rawCaptureDao(context).byId(later).parseStatus)
        assertEquals(2, Graph.txnDao(context).countAll())
        val second = Graph.txnDao(context).recent(1).single()
        assertEquals(TxnState.PENDING, second.state)
        assertEquals(PendingReason.DUPLICATE_SUSPECT, second.pendingReason)
    }

    @Test fun withinTenMinutesTheSameSlotAndContentIsStillAnUpdate() {
        insertNewCapture("Payment of RM32.00 to 99 SPEEDMART successful", sbnKey = "k1", postedAt = 0)
        runWorker()
        val soon = insertNewCapture(
            "Payment of RM32.00 to 99 SPEEDMART successful", sbnKey = "k1", postedAt = 60_000,
        )
        runWorker()
        assertEquals(ParseStatus.UPDATE_OF, Graph.rawCaptureDao(context).byId(soon).parseStatus)
        assertEquals(1, Graph.txnDao(context).countAll())
    }

    @Test fun everyCaptureLeavesTheNewState() {
        insertNewCapture("Payment of RM1.00 to A successful")
        insertNewCapture("nonsense with no amount")
        insertNewCapture("TAC 999999 do not share")
        runWorker()
        assertEquals(0, Graph.rawCaptureDao(context).claimNext(50).size)
    }

    @Test fun aRerunDoesNotDoubleCount() {
        insertNewCapture("Payment of RM32.00 to 99 SPEEDMART successful")
        runWorker()
        runWorker()
        assertEquals(1, Graph.txnDao(context).countAll())
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Add `implementation(libs.androidx.work.runtime)` and `androidTestImplementation(libs.androidx.work.testing)`.

Run: `./gradlew :feature:capture:connectedAndroidTest --tests '*ParseWorkerTest*'`
Expected: FAIL — unresolved reference `ParseWorker`.

- [ ] **Step 3: Implement the worker**

```kotlin
package my.pinged.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import my.pinged.data.LocalDates
import my.pinged.data.entity.ParseStatus
import my.pinged.data.entity.RawCapture
import my.pinged.data.entity.Txn
import my.pinged.data.entity.TxnState
import my.pinged.parse.MatchOutcome
import my.pinged.parse.Merchant

class ParseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val UNIQUE = "pinged.parse"
        private const val BATCH = 50
        private const val DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1000L

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ParseWorker>().build(),
            )
        }
    }

    override suspend fun doWork(): Result {
        val captures = Graph.rawCaptureDao(applicationContext)
        val txns = Graph.txnDao(applicationContext)
        val matcher = Graph.ruleMatcher(applicationContext)
        val uncategorized = Graph.uncategorizedId(applicationContext)

        while (true) {
            val batch = captures.claimNext(BATCH)
            if (batch.isEmpty()) return Result.success()

            for (capture in batch) {
                // Same slot, same content: an update, however long ago.
                val priorSlot = captures.findBySlotAndContent(capture.sbnKey, capture.contentHash)
                if (priorSlot != null && priorSlot.id != capture.id) {
                    captures.markOutcome(
                        capture.id, ParseStatus.UPDATE_OF, null, null, null, priorSlot.id, 0,
                    )
                    continue
                }

                // Same content from a different slot, inside a minute.
                val recent = captures
                    .findByContentHash(capture.contentHash, capture.postedAt - 60_000)
                    .filter { it.id != capture.id }
                if (recent.isNotEmpty()) {
                    captures.markOutcome(
                        capture.id, ParseStatus.DUPLICATE_OF, null, null, null, recent.first().id, 0,
                    )
                    continue
                }

                when (val outcome = matcher.match(
                    capture.sourcePackage, capture.title, capture.text, capture.bigText,
                )) {
                    is MatchOutcome.Matched -> {
                        commit(capture, outcome, uncategorized, txns)
                        captures.markOutcome(
                            capture.id, ParseStatus.MATCHED, outcome.ruleId, null,
                            outcome.rejectCollisionId, null, matcher.packVersion,
                        )
                    }
                    is MatchOutcome.Rejected -> captures.markOutcome(
                        capture.id, ParseStatus.REJECTED, null, outcome.rejectRuleId, null, null,
                        matcher.packVersion,
                    )
                    MatchOutcome.Unmatched -> captures.markOutcome(
                        capture.id, ParseStatus.UNMATCHED, null, null, null, null, matcher.packVersion,
                    )
                    MatchOutcome.NoExtras -> captures.markOutcome(
                        capture.id, ParseStatus.NO_EXTRAS, null, null, null, null, matcher.packVersion,
                    )
                }
            }
        }
    }

    private fun commit(
        capture: RawCapture,
        outcome: MatchOutcome.Matched,
        uncategorizedId: Long,
        txns: my.pinged.data.dao.TxnDao,
    ) {
        val occurredAt = capture.whenMillis
            ?.takeIf { kotlin.math.abs(it - capture.postedAt) < 7 * 24 * 60 * 60 * 1000L }
            ?: capture.postedAt

        val now = System.currentTimeMillis()
        val txn = Txn(
            rawCaptureId = capture.id,
            amountSen = outcome.amountSen,
            direction = outcome.direction,
            occurredAt = occurredAt,
            localDate = LocalDates.of(occurredAt),
            merchantRaw = outcome.merchantRaw?.let(Merchant::clean),
            merchantDisplay = outcome.merchantRaw?.let(Merchant::clean)?.let(Merchant::display),
            categoryId = uncategorizedId,
            sourcePackage = capture.sourcePackage,
            sourceLabel = null,
            confidence = outcome.confidence,
            // Every match commits in this milestone. The confidence gate and
            // the review inbox are the next plan; until then a REVIEW
            // confidence is recorded but does not withhold the row.
            state = TxnState.COMMITTED,
            createdAt = now,
            updatedAt = now,
        )
        // The unique index on raw_capture_id makes this idempotent: a re-run
        // throws rather than double-counting, and the capture is already
        // marked so the second attempt never happens in practice.
        runCatching { txns.insert(txn) }
    }
}
```

Add `ruleMatcher(context)`, `packVersion`, `uncategorizedId(context)` and `byId(id)` to `Graph` and `RawCaptureDao` respectively. `RuleMatcher` gains a `val packVersion: Int` taken from the pack it was built with.

`everyCaptureLeavesTheNewState` is the invariant that matters most here. A capture stuck at `NEW` is a silently lost transaction, which is the failure the two-stage design exists to prevent.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :feature:capture:connectedAndroidTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/capture
git commit -m "Add stage two: the parse worker

Reads raw_capture rather than a queue, so process death loses nothing and an
interrupted run resumes: any row still at NEW is unfinished work. A test
asserts no capture is left at NEW after a run.

Dedup has two layers with the ordering the spec requires, and rule 1 is
bounded. Same slot and same content inside ten minutes is an update; beyond
it the second payment reaches the ledger as a DUPLICATE_SUSPECT for review,
because content_hash carries no timestamp and so two genuinely separate
identical payments have the same identity by construction. A rebind catch-up
capture is exempt from the window, since getActiveNotifications returns only
posts that are still live -- which is what a refresh actually means.

The confidence gate decides COMMITTED or PENDING, and whichever of section
7.1's five conditions fired is recorded in pending_reason, so the review
inbox can say why rather than guess."
```

---

### Task 11: Rebinding, without which capture dies on every build

This is the task most likely to be skipped and most likely to waste a week. Replacing the APK unbinds the listener and nothing rebinds it until a reboot or a permission toggle. During development that means capture stops on every install, and the symptom looks like a parser bug.

**Files:**
- Create: `feature/capture/src/main/kotlin/my/pinged/capture/{RebindReceiver,ListenerStatus}.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `feature/capture/src/androidTest/kotlin/my/pinged/capture/RebindTest.kt`

**Interfaces:**
- Consumes: `PingedComponents`
- Produces: `ListenerStatus.isGranted(context): Boolean`, `ListenerStatus.requestRebind(context)`, `ListenerStatus.report(context): CaptureReport`

- [ ] **Step 1: Write the failing test**

```kotlin
package my.pinged.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RebindTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun grantIsDetectedThroughTheFrameworkApi() {
        val component = PingedComponents.listener(context).flattenToString()
        instrumentation.uiAutomation
            .executeShellCommand("cmd notification allow_listener $component").close()
        Thread.sleep(1_500)
        assertTrue(ListenerStatus.isGranted(context))
    }

    @Test fun requestRebindDoesNotThrowWhenGranted() {
        ListenerStatus.requestRebind(context)
    }

    @Test fun theReportDistinguishesGrantFromLiveness() = kotlinx.coroutines.runBlocking {
        val report = ListenerStatus.report(context)
        // The grant and the binding are independent. Grant-true-but-dead is
        // the OEM-kill signature (spec 10.1), so the report must be able to
        // say so rather than collapsing both into one boolean.
        assertTrue(report.javaClass.declaredFields.map { it.name }.containsAll(
            listOf("granted", "lastNotificationAt")
        ))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :feature:capture:connectedAndroidTest --tests '*RebindTest*'`
Expected: FAIL — unresolved reference `ListenerStatus`.

- [ ] **Step 3: Implement status and rebinding**

```kotlin
package my.pinged.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

// PingedComponents is NOT declared here. Ruling 5 moved it into Task 9 so
// that task could compile on its own, and it now exists at
// feature/capture/src/main/kotlin/my/pinged/capture/PingedComponents.kt.
// Declaring it again is a duplicate-class error, and the frozen listener
// name lives in exactly one place for the same reason the name is frozen.

data class CaptureReport(
    val granted: Boolean,
    val lastNotificationAt: Long,
    val staleForMillis: Long,
) {
    /** Grant present but nothing seen in a day: the OEM-kill signature. */
    val looksDead: Boolean get() = granted && staleForMillis > 24 * 60 * 60 * 1000L
}

object ListenerStatus {
    fun isGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(PingedComponents.listener(context))

    fun requestRebind(context: Context) {
        runCatching {
            NotificationListenerService.requestRebind(PingedComponents.listener(context))
        }
    }

    suspend fun report(context: Context): CaptureReport {
        val last = CaptureHealth.lastSeenAt(context)
        return CaptureReport(
            granted = isGranted(context),
            lastNotificationAt = last,
            staleForMillis = if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last,
        )
    }
}
```

- [ ] **Step 4: Implement the receiver**

```kotlin
package my.pinged.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Replacing the APK unbinds the listener and the system does not rebind it
 * until a reboot or a permission toggle (spec 10.1). Without this receiver
 * capture dies on every build installed, and the symptom looks like a parser
 * bug rather than a binding one.
 */
class RebindReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED -> {
                ListenerStatus.requestRebind(context)
                ParseWorker.enqueue(context)
            }
        }
    }
}
```

Manifest, inside `<application>`:

```xml
<receiver
    android:name="my.pinged.capture.RebindReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 5: Call rebind on every app foreground**

In `PingedApp` or the single Activity's `onStart`:

```kotlin
override fun onStart() {
    super.onStart()
    if (ListenerStatus.isGranted(this)) {
        ListenerStatus.requestRebind(this)
        ParseWorker.enqueue(this)
    }
}
```

Three paths, all needed: the receiver covers reinstall and reboot, `onListenerDisconnected` covers a surviving process, and the foreground call covers everything else including the case where `requestRebind` did not take the first time.

- [ ] **Step 6: Verify by hand, because no test covers the real failure**

1. Install, grant notification access, post a matching notification, confirm a capture lands.
2. `./gradlew :app:installDebug` again, without touching settings.
3. Post another matching notification.
4. Confirm it lands.

Before this task, step 4 fails. That manual check is the whole point of the task, and no instrumented test reproduces it — the process is replaced, so nothing of yours is running to observe it.

Record the result in `docs/device-notes.md`.

- [ ] **Step 7: Commit**

```bash
git add feature/capture app/src/main/AndroidManifest.xml docs/device-notes.md
git commit -m "Rebind the listener after reinstall and reboot

Replacing the APK unbinds the notification listener and nothing rebinds it
until a reboot or a permission toggle. Without this, capture dies on every
build installed and the symptom looks like a parser bug.

Three paths, all needed: a MY_PACKAGE_REPLACED and BOOT_COMPLETED receiver,
onListenerDisconnected for a surviving process, and a requestRebind on every
app foreground for when the first attempt did not take.

CaptureReport keeps the grant and liveness separate, because grant-present
but nothing-seen is the OEM-kill signature and collapsing them into one
boolean hides it."
```

---

- [ ] **Step 5: Wire per-source liveness, which no task currently does**

`CaptureSourceDao.setLastNotificationAt` exists and has **no caller
anywhere**. Spec section 10.3 makes per-source liveness the detector for the
likelier failure — the listener is alive and healthy while one bank's
notification channel has been muted, so global capture looks fine and one
source has silently gone quiet — and it asserts "the data is already being
written". It is not.

Task 9 correctly refused to absorb this: stage one is extraction, the
allow-list gate and one insert, and this would have been a second Room write
on the per-notification path. It belongs here, with the rest of the liveness
work, and it must be **throttled the same way the heartbeat is** — at most
one write per source per throttle window, not one per notification. A phone
posts 100-300 notifications a day and Room's invalidation tracker is
table-granular, which is the entire reason section 10.2 keeps the global
heartbeat in `DataStore`.

Use the targeted column update, never a whole-row upsert. Task 8 removed
`CaptureSourceDao.upsert` precisely because `REPLACE` is a delete-and-insert
that writes back every column a partial caller did not know about — and the
column it would silently reset here is `enabled`, which is the user's
allow-list consent. A liveness write that disables a source makes every
later notification from that bank unrecoverable, because the default-deny
gate discards them before any write.

Test that a liveness write leaves `enabled`, `is_authoritative` and
`first_seen_at` untouched, and that the throttle actually throttles.

---

### Task 12: The Receipt theme and the capture-source screen

Without a way to enable a package, nothing can be verified on a device. This task builds the minimum UI that makes the milestone provable, in the design system so it is not thrown away.

**Files:**
- Create: `feature/ledger/build.gradle.kts`
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/theme/{Color,Type,Theme}.kt`
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/sources/{SourcesViewModel,SourcesScreen}.kt`
- Create: `app/src/main/kotlin/my/pinged/MainActivity.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/SourcesScreenTest.kt`

**Interfaces:**
- Consumes: `CaptureSourceDao`, `SourceCounters`, `ListenerStatus`

`SourceRow` is the screen's own view type, defined in `SourcesViewModel.kt`.
It is used by the test below and was previously defined nowhere:

```kotlin
data class SourceRow(
    val pkg: String,
    val label: String,
    val seenCount: Int,
    val enabled: Boolean,
)
```

Deliberately not the `CaptureSource` entity. `seen_count` lives in
`DataStore`, not in that table (spec 9.6), so the row is a join of two
stores and the screen should say so in its type rather than pretend one
query produces it.
- Produces: `PingedTheme { }`, `SourcesScreen(viewModel)`

- [ ] **Step 1: Write the design tokens**

Values are copied verbatim from the design system published with the screen set. Do not adjust them.

```kotlin
package my.pinged.ledger.theme

import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFFBF6EA)
val Card = Color(0xFFEFE7D8)
val Ink = Color(0xFF1A1714)
val Muted = Color(0xFF6B6459)
val Faint = Color(0xFF8A8175)
val Rule = Color(0xFFCFC4AE)
val Border = Color(0xFFDED5C4)
val Stamp = Color(0xFFA03826)

/** One hue, six steps, darkest for the largest category. */
val ChartRamp = listOf(
    Color(0xFF1A1714), Color(0xFF3D372F), Color(0xFF5C554A),
    Color(0xFF7A7264), Color(0xFF98907F), Color(0xFFB6AD99),
)
```

Add Instrument Serif, Karla and IBM Plex Mono as bundled font resources and wire them in `Type.kt` with three roles: display for totals and titles, body for everything readable, mono for numerals and uppercase labels. **Mono never carries body copy** — that is a system rule, not a preference.

`Theme.kt` supplies a `MaterialTheme` with these colours and typography, and commits to the single light palette the direction chose.

- [ ] **Step 2: Write the failing screen test**

```kotlin
package my.pinged.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SourcesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsSeenCountsAndTogglesASource() {
        var toggled: Pair<String, Boolean>? = null
        compose.setContent {
            my.pinged.ledger.theme.PingedTheme {
                my.pinged.ledger.sources.SourcesScreenContent(
                    suggested = listOf(
                        SourceRow("my.com.tngdigital.ewallet", "Touch 'n Go eWallet", 412, true),
                        SourceRow("com.maybank2u.life", "Maybank MAE", 208, false),
                    ),
                    seenNotCaptured = listOf(
                        SourceRow("com.whatsapp", "WhatsApp", 3204, false),
                    ),
                    onToggle = { pkg, on -> toggled = pkg to on },
                )
            }
        }

        compose.onNodeWithText("Touch 'n Go eWallet").assertIsDisplayed()
        compose.onNodeWithText("412 SEEN").assertIsDisplayed()
        // The row that makes the privacy model concrete (spec 9.6).
        compose.onNodeWithText("3,204 SEEN · NOT ONE WORD STORED").assertIsDisplayed()

        compose.onNodeWithText("Maybank MAE").performClick()
        assertTrue(toggled == "com.maybank2u.life" to true)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :feature:ledger:connectedAndroidTest`
Expected: FAIL — unresolved reference `SourcesScreenContent`.

- [ ] **Step 4: Implement the screen**

Build it from the published `Sources.dc.html` artboard: back chevron and serif title, a mono sub-line reading "N ENABLED · TEXT IS STORED ONLY FOR THESE", a section for suggested installed packages, a torn edge, then "SEEN RECENTLY, NOT CAPTURED". Rows carry label, mono seen count, and a 42×24 pill toggle filled `Stamp` when on and outlined `Border` when off. Tap targets are at least 44dp.

Split it in two so the test above needs no database: `SourcesScreenContent(suggested, seenNotCaptured, onToggle)` is pure and stateless; `SourcesScreen(viewModel)` collects state and delegates to it.

`SourcesViewModel` reads `CaptureSourceDao.enabled()`, merges `SourceCounters.seenCount` per package, resolves labels through `PackageManager` for packages in `<queries>`, and falls back to showing the raw identifier when the label cannot be resolved — which is the expected case on API 30+ for anything not declared (spec §9.6).

- [ ] **Step 5: Wire the activity**

`MainActivity` shows `SourcesScreen`, plus a banner when `ListenerStatus.report()` says the grant is missing, linking to `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (API 30) with `ACTION_NOTIFICATION_LISTENER_SETTINGS` as fallback. Every deep link is wrapped against `Throwable`, not just `ActivityNotFoundException`: OEM settings activities are frequently `exported="false"`, which makes `startActivity` throw `SecurityException` after `resolveActivity` returned non-null (spec §10.4).

- [ ] **Step 6: Run and commit**

Run: `./gradlew :feature:ledger:connectedAndroidTest`
Expected: PASS.

```bash
git add feature/ledger app/src/main/kotlin gradle/libs.versions.toml settings.gradle.kts
git commit -m "Add the Receipt theme and the capture-source screen

Tokens copied verbatim from the published design system, so this is the real
screen rather than scaffolding to be thrown away. Mono carries numerals and
labels only.

The screen splits into a stateless SourcesScreenContent and a
database-backed SourcesScreen, so the UI test needs no database.

Labels fall back to the raw package identifier when PackageManager cannot
resolve them, which on API 30+ is the expected case for anything not
declared in <queries>."
```

---

### Task 13: JSON export and import

> **This task predates every schema change made after it was written, and its
> snippets enumerate fields by hand.** `RawCapture` now has 25 fields and
> `Txn` has 21 -- counted from their constructors; an earlier version of
> this note said 27 and 22, from a grep that counted every `val` in the
> file -- including `pending_reason`, `arrival` and `scoped_package`,
> none of which appear anywhere below. Section 12 calls the JSON export "full
> fidelity", so a writer that omits a column is not a formatting bug — it
> loses the reason a transaction needs review, or the arrival path duplicate
> layer 1 depends on, with no error at either end.
>
> **A field-by-field round-trip assertion is necessary but not sufficient**,
> because it only tests the fields someone remembered to write. The guard that
> matters catches the *class*: enumerate each entity's constructor parameters
> reflectively and assert every one appears in the exported object, so adding
> a column to an entity without adding it to the export fails immediately.
> That is the same shape as the enum-vocabulary test and the harness-DDL
> comparison, and it exists for the same reason — this schema has changed
> under a downstream consumer four times today alone.


Import is in this milestone, not a later one, for a development reason as much as a user-facing one: without it, every debug reinstall destroys capture history that Android will not replay.

**Files:**
- Create: `feature/ledger/src/main/kotlin/my/pinged/ledger/transfer/{Backup,ExportJson,ImportJson}.kt`
- Test: `feature/ledger/src/androidTest/kotlin/my/pinged/ledger/transfer/RoundTripTest.kt`

**Interfaces:**
- Consumes: all DAOs
- Produces: `ExportJson.write(db, out: OutputStream, onProgress: (Int) -> Unit)`, `ImportJson.read(db, input: InputStream): ImportReport`

- [ ] **Step 1: Write the failing round-trip test**

```kotlin
package my.pinged.ledger.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everythingSurvivesAnExportAndReimport() {
        // Defined in this file. It was previously used by five tests and
        // declared nowhere, which is the same decay that hit SourceRow.
        //
        //   private fun freshDatabaseWith(captures: Int, txns: Int): PingedDatabase {
        //       context.deleteDatabase(DatabaseFactory.NAME)
        //       Graph.reset()
        //       val db = DatabaseFactory.build(context)     // seeds categories
        //       val uncategorized = db.categoryDao().requireUncategorizedId()
        //       repeat(captures) { db.rawCaptureDao().insert(sampleCapture(hash = "h$it")) }
        //       repeat(txns) { db.txnDao().insert(sampleTxn(categoryId = uncategorized)) }
        //       return db
        //   }
        //
        // Build through DatabaseFactory, never Room.inMemoryDatabaseBuilder:
        // export/import is exactly where the encrypted-open path and the
        // seeded categories have to hold, and an in-memory database proves
        // neither.
        val db = freshDatabaseWith(captures = 120, txns = 40)
        val out = ByteArrayOutputStream()
        ExportJson.write(db, out) {}
        db.close()

        val restored = freshDatabaseWith(captures = 0, txns = 0)
        val report = ImportJson.read(restored, ByteArrayInputStream(out.toByteArray()))

        assertEquals(120, report.rawCaptures)
        assertEquals(40, report.txns)
        assertEquals(120, restored.rawCaptureDao().countAll())
        assertEquals(40, restored.txnDao().countAll())
    }

    @Test fun reimportingTwiceDoesNotDuplicate() {
        val db = freshDatabaseWith(captures = 10, txns = 3)
        val out = ByteArrayOutputStream()
        ExportJson.write(db, out) {}

        ImportJson.read(db, ByteArrayInputStream(out.toByteArray()))
        assertEquals(10, db.rawCaptureDao().countAll())
    }

    @Test fun exportStreamsRatherThanBuffering() {
        val db = freshDatabaseWith(captures = 5_000, txns = 500)
        val out = ByteArrayOutputStream()
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        ExportJson.write(db, out) {}
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        // A buffered implementation holds every row plus the whole document.
        // Streaming should not grow the heap by anything like the payload size.
        assertEquals(true, (after - before) < out.size())
    }

    @Test fun malformedJsonIsReportedNotCrashed() {
        val db = freshDatabaseWith(captures = 0, txns = 0)
        val result = runCatching {
            ImportJson.read(db, ByteArrayInputStream("{ not json".toByteArray()))
        }
        assertEquals(true, result.exceptionOrNull() is ImportFormatException)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :feature:ledger:connectedAndroidTest --tests '*RoundTripTest*'`
Expected: FAIL — unresolved reference `ExportJson`.

- [ ] **Step 3: Implement streaming export**

Use `android.util.JsonWriter` straight onto the `OutputStream`, reading rows with a paged DAO query so neither side holds the whole set:

```kotlin
package my.pinged.ledger.transfer

import android.util.JsonWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import my.pinged.data.PingedDatabase

object ExportJson {
    private const val PAGE = 500

    fun write(db: PingedDatabase, out: OutputStream, onProgress: (Int) -> Unit) {
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            w.setIndent("")
            w.beginObject()
            w.name("format").value(1)
            w.name("exported_at").value(System.currentTimeMillis())

            w.name("categories").beginArray()
            db.categoryDao().all().forEach { writeCategory(w, it) }
            w.endArray()

            w.name("raw_captures").beginArray()
            var offset = 0
            var written = 0
            while (true) {
                val page = db.rawCaptureDao().page(PAGE, offset)
                if (page.isEmpty()) break
                page.forEach { writeCapture(w, it); written++ }
                onProgress(written)
                offset += page.size
            }
            w.endArray()

            w.name("txns").beginArray()
            offset = 0
            while (true) {
                val page = db.txnDao().page(PAGE, offset)
                if (page.isEmpty()) break
                page.forEach { writeTxn(w, it) }
                offset += page.size
            }
            w.endArray()

            w.endObject()
        }
    }
}
```

Write `writeCategory`, `writeCapture` and `writeTxn` as private functions emitting every column with its snake_case name, and add `page(limit, offset)` to both DAOs ordered by `id`.

- [ ] **Step 4: Implement import**

```kotlin
package my.pinged.ledger.transfer

class ImportFormatException(message: String) : IllegalArgumentException(message)

data class ImportReport(val categories: Int, val rawCaptures: Int, val txns: Int)
```

`ImportJson.read` uses `android.util.JsonReader`, inserts in batches of 500 inside `db.runInTransaction`, and keys inserts on the exported `id` so a second import replaces rather than duplicates. Any parse failure raises `ImportFormatException` with the field it failed on — never a bare exception, because the user is at their most anxious when restoring.

CSV export is deliberately not in this milestone: it has its own requirements (RFC 4180 quoting, a UTF-8 BOM for Excel, and neutralising a leading `=`, `+`, `-` or `@` so a merchant string cannot become a formula) and no capture depends on it.

- [ ] **Step 5: Run and commit**

Run: `./gradlew :feature:ledger:connectedAndroidTest`
Expected: PASS.

```bash
git add feature/ledger core/data
git commit -m "Add streaming JSON export and import

Import is in this milestone rather than a later one because without it every
debug reinstall destroys capture history that Android will not replay, and
because section 12's claim that the export is sufficient to rebuild the
database is only true if something reads it back.

JsonWriter and JsonReader straight onto the stream, with paged DAO reads and
batched inserts, so neither side holds the whole payload. A test asserts the
heap does not grow by the payload size, which a buffered implementation would
fail.

Inserts are keyed on the exported id, so a second import replaces rather than
duplicates. Malformed input raises ImportFormatException naming the field: a
user restoring a backup is at their most anxious, and a bare stack trace is
the wrong answer."
```

---

### Task 14: Prove the milestone on real hardware

Nothing above proves the thing that actually matters. The failure this app dies from cannot be reproduced on an emulator.

**Files:**
- Create: `docs/device-notes.md`

**Interfaces:**
- Consumes: everything
- Produces: a recorded verification, and identifiers confirmed rather than guessed

- [ ] **Step 1: Run the whole suite**

Run: `./gradlew test connectedAndroidTest`
Expected: PASS. Record the counts in `docs/device-notes.md`.

- [ ] **Step 2: Install on a real phone with real banking apps**

Not an emulator. Grant notification access, working through the restricted-settings path if the toggle is greyed on API 33+. Note in `docs/device-notes.md` whether it was greyed, and for which install method.

- [ ] **Step 3: Confirm the identifiers**

```bash
adb shell pm list packages | grep -iE 'tng|touchngo|maybank|cimb|grab|setel|boost|bigpay|shopee'
```

Correct `<queries>` and `pack.json`. Commit the corrections with the device and OS version in the message, because the next person will want to know where the identifiers came from.

- [ ] **Step 4: Spend one ringgit**

Make a real payment. Confirm a `raw_capture` row and a `txn` row appear with the right amount and merchant. If it does not parse, use `adb shell` to read the captured text out of the unread rows and add a fixture and a rule — that loop is the product, and this is the first time it runs for real.

- [ ] **Step 5: Reinstall and spend again**

`./gradlew :app:installDebug`, then make another payment without touching any setting. It must still capture. This is Task 11's manual verification and the single most valuable check in the milestone.

- [ ] **Step 6: Leave it running for three days**

Note the capture count daily against what you actually spent. On a Xiaomi, Oppo, Vivo or Realme device, note whether capture survives a screen-off overnight and whether it survives swiping the app from recents. Record the results per device.

This is the device matrix from spec §13. It cannot be automated, so it has to be scheduled.

- [ ] **Step 7: Commit the notes**

```bash
git add docs/device-notes.md app/src/main/AndroidManifest.xml core/parse/src/main/resources/pack.json
git commit -m "Record the on-device verification for the capture milestone

Package identifiers confirmed on real hardware rather than guessed, the
restricted-settings behaviour recorded per install method, and a three-day
soak logged against actual spending.

The reinstall check is the one that matters: capture survives an APK replace
without touching any setting."
```

---

## Milestone Complete

At this point the app: captures notifications from packages the user enabled, into an encrypted database whose key never leaves the device and never enters a backup; parses them with a data-driven pack validated at load; writes transactions idempotently; and survives reinstall and reboot.

**Correction, written after the milestone was built.** This paragraph used to end "and can export and re-import everything as JSON". It cannot. `ExportJson` and `ImportJson` are implemented and tested end to end, and no production code calls either: reaching them needs the settings screen of spec 9.5, which Task 12 correctly left out of scope. The capability exists at the module boundary and not in the app, and the difference matters to anyone reading this as a statement of what ships.

Two further corrections to the table below, in the other direction -- three rows describe work this milestone turned out to include.

It does **not** yet do any of the following, each of which is a later plan:

| Spec | Not in this milestone |
|---|---|
| §5.5 | Re-parse after a pack upgrade, in any of its three modes |
| §5.7 | User reject rules, and the unread-captures screen they act on |
| §5.8 | Teach a rule by example |
| §5.9 | Pack import, validation and the dry run |
| §6 | Categorization beyond Uncategorized, and learned merchant rules |
| §7.1 | ~~The confidence gate~~ — **built**: `ConfidenceGate` returns every condition that fired and `pending_reason` records the most specific. The review inbox that reads it is not |
| §7.2 | ~~Duplicate layer 2~~ — **built**: `Dedup.layerTwo` flags cross-source same-amount suspects. Resolving one (Merge / Keep both) needs the inbox, so it is not |
| §7.3 | Transfer and reload confirmation — `kind` is recorded, not acted on |
| §8 | Charts, and the transaction list itself |
| §9.1–9.5 | Every screen except capture sources |
| §10.3 | ~~Per-source liveness checks~~ — **built**: `SourceLiveness` writes `capture_source.last_notification_at` past the allow-list gate. Nothing reads it to raise an alert yet |
| §11.3 | Delete all data |
| §12 | CSV export |
| §15.2, §15.4 | FTS search and paging — neither has a consumer yet |

All of them are safe to build on a capture path that is provably correct, and
none of them are safe to build on one that is not. That is the whole argument
for this milestone boundary.

## Where this plan is thin, stated rather than hidden

**The review-inbox ratio is unknown.** Every match commits in this milestone. Once real capture runs for a week, count how many would have been `PENDING`. If it is a large fraction, the review inbox is the app's main surface rather than a badge, and that is a design change before it is an implementation one.

**Two package identifiers are guesses until Task 9 Step 6.** Everything downstream of them is testable regardless, which is why the corpus keys on package name rather than assuming any particular one is right.

**The `<queries>` and pack-identifier lists will disagree.** The manifest list needs a release to grow; pack rules do not. Nothing enforces that they stay in step, and a check that they do is worth adding once the identifier list is longer than two entries.
