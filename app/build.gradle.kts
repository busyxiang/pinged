plugins {
    // AGP 9 has built-in Kotlin support; the separate
    // org.jetbrains.kotlin.android plugin is no longer required (and applying
    // it is a hard error).
    alias(libs.plugins.android.application)
    // Same story as :feature:ledger -- the Compose compiler plugin attaches to
    // the Kotlin compilation AGP 9 already registered.
    alias(libs.plugins.compose.compiler)
}

/**
 * The version being built, from the tag the release workflow is running for.
 *
 * `versionCode` and `versionName` were literals, `1` and `"0.1.0"`, while
 * `release.yml` fires on `v*` tags and names every artifact after its tag. So
 * `v0.2.0` produced `pinged-0.2.0.apk` whose manifest said 0.1.0, code 1 -- and
 * `versionCode` is the only thing Android compares, so every release after the
 * first would have been the same version to the package manager forever.
 *
 * `PINGED_VERSION` is the tag with its leading `v` removed. A build without it
 * is not a release and says so: `0.0.0-dev`, code 1, deliberately not a
 * plausible version.
 */
data class PingedVersion(val code: Int, val name: String)

val pingedVersion: PingedVersion = run {
    val tag = System.getenv("PINGED_VERSION")?.trim().orEmpty()
    if (tag.isEmpty()) return@run PingedVersion(1, "0.0.0-dev")
    val parts = Regex("^([0-9]+)\\.([0-9]+)\\.([0-9]+)$").matchEntire(tag)
        ?: throw GradleException(
            "PINGED_VERSION is '$tag', which is not major.minor.patch. The release " +
                "workflow passes the tag with its leading 'v' removed, so the tag " +
                "itself is what needs fixing.",
        )
    val (major, minor, patch) = parts.destructured.toList().map(String::toInt)
    if (minor > 99 || patch > 99) {
        throw GradleException(
            "PINGED_VERSION is '$tag'. versionCode packs minor and patch into two " +
                "digits each, so a component above 99 would collide with the next one " +
                "up. Widen the packing here, and remember that versionCode may never " +
                "decrease on a device.",
        )
    }
    PingedVersion(major * 10_000 + minor * 100 + patch, tag)
}

android {
    namespace = "my.pinged.tracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "my.pinged.tracker"
        minSdk = 27
        targetSdk = 37
        versionCode = pingedVersion.code
        versionName = pingedVersion.name
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The signing config from docs/release.md. The workflow passes the
    // keystore through PINGED_* environment variables; falling back to no
    // signing config when they are absent keeps a local assembleRelease
    // working without secrets.
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

    buildFeatures { compose = true }

    // Kept at 17 to match :core:parse, whose bytecode must stay at class-file
    // major 61 for D8.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:parse"))
    // Without this the manifest declares a service whose class is not in the
    // APK, and the listener cannot start.
    implementation(project(":feature:capture"))
    // Declared here, not taken transitively: PingedApp implements
    // WorkManager's Configuration.Provider because the manifest removes
    // WorkManagerInitializer, so this module uses the API directly.
    implementation(libs.androidx.work.runtime)
    // The theme and the allow-list screen.
    implementation(project(":feature:ledger"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // setContent on a ComponentActivity, and nothing else from it.
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // This module had no androidTest source set at all, and that is how a
    // crash on launch shipped in v0.1.0: `MainActivity` lives here, so no
    // other module can start it, and nothing else in the tree exercises
    // process start. `LaunchTest` is the only test in the project whose
    // subject is the app rather than a component of it.
    //
    // No ui-test-manifest here, unlike :feature:ledger. That artifact exists
    // to supply a stub Activity for `createComposeRule` in a library module;
    // this module has the real manifest and the real Activity under test.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // ActivityScenario, which drives the real lifecycle rather than asserting
    // about a Compose tree in isolation -- onResume is where v0.1.0 died.
    androidTestImplementation(libs.androidx.test.core)
    // Not optional on API 37. See the note in the version catalog.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit)
}
