plugins {
    // AGP 9's built-in Kotlin, plus the Compose compiler plugin on top of it;
    // see :feature:ledger's build file for why there is no kotlin-android.
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "my.pinged.read"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // `ScriptedRunner`, the runner a test hands a `ScreenRead` to answer, refuse or
        // hold on command. A source directory rather than a published fixture, as
        // `HeldClock` is, so a feature module's JVM tests add it and gain no dependency.
        getByName("test") { kotlin.directories.add(rootProject.file("core/read/src/sharedTest/kotlin").path) }
    }

    testOptions {
        // Compose on Robolectric needs the merged resources of its test manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api: `PingedDatabase` is in `ScreenRead`'s constructor and `ReadRunner`.
    api(project(":core:data"))
    api(libs.kotlinx.coroutines.android)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    // `LifecycleResumeEffect`, to read again on each resume.
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}

// The runner is the seam (#296, decision 8): `CaptureStorage.guarded` and its
// policy live in :feature:capture, which depends on this module for the adapter. An
// edge back would make a cycle, and an edge to a screen would hand it every other
// one's dependencies. Checked on every configuration, test ones included, at
// resolution, as :core:ui checks its own edges.
configurations.configureEach {
    val configuration = name
    withDependencies {
        filterIsInstance<ProjectDependency>()
            .map { it.path }
            .filter { it.startsWith(":feature:") }
            .forEach {
                throw GradleException(
                    ":core:read must not depend on $it (#296, decision 9). Found on `$configuration`.",
                )
            }
    }
}
