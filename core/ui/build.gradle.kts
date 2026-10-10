plugins {
    // AGP 9's built-in Kotlin, plus the Compose compiler plugin on top of it;
    // see :feature:ledger's build file for why there is no kotlin-android.
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    // The fonts' R class is `my.pinged.ui.R`. Moving them changes that
    // package, and `Type.kt` is the only reader.
    namespace = "my.pinged.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // `ContrastTest`: arithmetic over the palette. `Color` is a value class
    // over a ULong, so the ratios compute on the JVM with no device.
    testImplementation(libs.junit)
}

// The theme is shared by the Ledger and the Charts, and :feature:charts may
// never depend on :feature:ledger (#81, Modules). This module is that seam
// only while it reaches nothing above it: an edge to :core:data or to a
// feature module would hand every screen's dependencies to every other one.
// Checked on every configuration, test ones included, at resolution.
configurations.configureEach {
    val configuration = name
    withDependencies {
        filterIsInstance<ProjectDependency>()
            .map { it.path }
            .filter { it == ":core:data" || it.startsWith(":feature:") }
            .forEach {
                throw GradleException(
                    ":core:ui must not depend on $it (#81, Modules). Found on `$configuration`."
                )
            }
    }
}
