plugins {
    // AGP 9 has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is a hard error, so every Android module in this project
    // applies only the AGP plugin -- see core/data/build.gradle.kts.
    alias(libs.plugins.android.library)
}

android {
    namespace = "my.pinged.capture"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Bytecode target through compileOptions, not a kotlin{} block: with AGP's
    // built-in Kotlin there is no kotlin extension here. Kept at 17 to match
    // :core:parse, :core:data and :app, whose bytecode must stay at class-file
    // major 61 for D8.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric builds a real Notification, which reaches into
        // framework resources. Without this the unit tests run against a
        // resource-less android.jar and Notification.Builder fails.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api, not implementation: RawCapture and Arrival appear in this module's
    // own surface (Graph hands out DAOs typed on them), so a consumer of
    // :feature:capture needs them on its compile classpath too.
    api(project(":core:data"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // Stage two. `work-runtime`, not `work-runtime-ktx`: as of 2.11.2 the ktx
    // artifact carries no classes at all and CoroutineWorker lives here. See
    // the note in the version catalog.
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    // The allow-list screen's view model, for the one test that joins the two
    // halves of the milestone.
    //
    // **androidTestImplementation, and it points the "wrong" way on purpose.**
    // :feature:ledger depends on this module. That is not a cycle -- androidTest is
    // a separate compilation consuming :feature:ledger's debug variant, and nothing
    // in main or in the shipped APK gains an edge.
    //
    // The alternative was widening `CaptureIngest` from `internal` to public, which
    // would put this module's private stage-one entry point into every other
    // module's compile surface, permanently, to buy one test.
    androidTestImplementation(project(":feature:ledger"))
    // Compose is deliberately absent. Driving `SourcesScreen` from here would
    // mean applying the Compose compiler plugin to a module that draws nothing;
    // :feature:ledger's own `SourcesAllowListTest` already drives the real
    // screen down to `SourcesViewModel.setEnabled`, which is where this test
    // picks the chain up.
    // SupportSQLiteDatabase, so the default-deny test can sweep every text
    // column in the database for the notification's text rather than trusting
    // a row count.
    androidTestImplementation(libs.androidx.sqlite)
    // TestListenableWorkerBuilder, which constructs the worker the way
    // WorkManager does without needing a scheduler to run it.
    androidTestImplementation(libs.androidx.work.testing)
}
