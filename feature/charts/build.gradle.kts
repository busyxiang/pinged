plugins {
    // AGP 9's built-in Kotlin, plus the Compose compiler plugin on top of it;
    // see :feature:ledger's build file for why there is no kotlin-android.
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    // §8's numerals are verified in screenshots (#87). `verifyRoborazziDebug`
    // is what compares; plain `test` only renders.
    alias(libs.plugins.roborazzi)
}

roborazzi {
    // Goldens are committed, as plain PNGs with no LFS, next to the tests
    // that draw them. The default, build/outputs/roborazzi, is not in source
    // control. Tests name their file relative to this directory: see
    // `Golden.kt`.
    outputDir.set(file("src/test/screenshots"))
}

android {
    namespace = "my.pinged.charts"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The screenshots draw :core:ui's res/font files; without merged
        // resources Robolectric has no R.font to load.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // The DAOs, `Databases` and the month-trust rule.
    implementation(project(":core:data"))
    // `CaptureStorage.guarded`: the lease, the IO hop and the storage flag
    // every screen's read goes through.
    implementation(project(":feature:capture"))
    // The theme and the fonts, shared with the Ledger without depending on it.
    implementation(project(":core:ui"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // `AndroidViewModel` and `SavedStateHandle`, which are this module's
    // public surface: `:app` builds the holder. `api` for the reason
    // :feature:ledger's build file gives.
    api(libs.androidx.lifecycle.viewmodel.compose)
    // `LifecycleResumeEffect`, to re-read on each foreground.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The mapping layer: pure functions of a read, tested with no device.
    testImplementation(libs.junit)
    // The screenshots: Compose on Robolectric NATIVE graphics, captured by
    // Roborazzi. `ui-test-manifest` is test-only, unlike :feature:ledger's
    // debugImplementation, so its stub Activity stays out of the debug AAR.
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

    // `MonthsSheetTest` drives the screen: the header, the sheet and a pick;
    // `GridDayClickTest` taps the grid's squares (#88).
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Not optional on API 37. See the note in the version catalog.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    // createComposeRule()'s ComponentActivity, in the debug manifest the
    // androidTest APK is built against; see :feature:ledger's build file.
    debugImplementation(composeBom)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The Charts may never depend on the Ledger (#81, Modules; #70): what the two
// share lives in :core:ui and :core:data, and a grid cell reaches the Day
// screen through :app. Checked on every configuration, test ones included, at
// resolution, as :core:ui checks its own edges.
configurations.configureEach {
    val configuration = name
    withDependencies {
        filterIsInstance<ProjectDependency>()
            .map { it.path }
            .filter { it == ":feature:ledger" }
            .forEach {
                throw GradleException(
                    ":feature:charts must not depend on $it (#81, Modules). Found on `$configuration`.",
                )
            }
    }
}
