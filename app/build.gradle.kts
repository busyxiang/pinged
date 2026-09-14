plugins {
    // AGP 9 has built-in Kotlin support; the separate
    // org.jetbrains.kotlin.android plugin is no longer required (and applying
    // it is a hard error).
    alias(libs.plugins.android.application)
    // Same story as :feature:ledger -- the Compose compiler plugin attaches to
    // the Kotlin compilation AGP 9 already registered.
    alias(libs.plugins.compose.compiler)
    // Nav3 keeps the back stack in saved state and serialises the keys to get
    // it there, so `Destinations.kt`'s objects are `@Serializable` and this
    // module needs the plugin that generates their serializers. Already in the
    // root build as `apply false` and already applied by :core:parse, so this
    // adds a compiler plugin and no new dependency.
    alias(libs.plugins.kotlin.serialization)
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

    // Navigation 3 rather than navigation-compose. The back stack is saved
    // state here, and this process is created and destroyed constantly by
    // design -- the listener is spawned for a notification and dies -- so a
    // back stack held in a StateFlow would restore the user onto the wrong
    // screen with nothing to explain it.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // `viewModel()`, which is how a destination gets its holder without the
    // Activity owning a field per screen.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // `rememberViewModelStoreNavEntryDecorator`. NavDisplay's default
    // `entryDecorators` is `listOf(rememberSaveableStateHolderNavEntryDecorator())`
    // and nothing else -- read off navigation3-ui-android-1.1.7's own sources
    // -- so without this every `viewModel()` inside an entry resolves against
    // the Activity's store and no holder is ever cleared when its destination
    // is popped. `MainActivity` passes both decorators explicitly for that
    // reason.
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // `Destinations.kt`'s two back-stack rules, which are decisions about a
    // `MutableList<NavKey>` and need no device. See `BackStackRuleTest`.
    testImplementation(libs.junit)

    // `MainActivity` lives here, so `LaunchTest` is the only place the
    // application itself can be started.
    //
    // No ui-test-manifest, unlike :feature:ledger: that artifact supplies a
    // stub Activity for `createComposeRule` in a library module, and this
    // module has the real manifest and the real Activity under test.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // ActivityScenario, for the real lifecycle rather than a Compose tree.
    androidTestImplementation(libs.androidx.test.core)
    // Driving the real Activity's Compose tree, which `LaunchTest` records as
    // the missing half of what it can assert: navigation between the two
    // destinations is a Compose control away, and nothing here could touch
    // one. Still no ui-test-manifest -- that artifact supplies a stub Activity
    // for a library module, and this one has the real Activity under test.
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Not optional on API 37. See the note in the version catalog.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit)
}
