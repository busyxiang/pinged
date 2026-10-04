plugins {
    alias(libs.plugins.android.test)
}

/**
 * The shipped dex, driven from outside it (issue #2).
 *
 * `:app`'s own instrumented suite runs inside the app's process and cannot be
 * pointed at R8's output -- see the `minified` build type in
 * app/build.gradle.kts. This module installs that build type and drives it as
 * a user's phone does: from another process, with a real notification.
 *
 * **This APK's package is Touch 'n Go's.** A notification is attributed to the
 * package that posted it and the pack is keyed by package, so this is the only
 * way to put a bank's notification in front of the real listener. It also puts
 * the bank in the app's `<queries>` list as installed, which is how it reaches
 * the allow-list screen. Installing it on a phone that has the real eWallet
 * fails on the signature, which is the failure wanted.
 */
android {
    namespace = "my.com.tngdigital.ewallet"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Named to match the target's build type, which is how com.android.test
    // picks the variant of :app it installs. The fallback is for the library
    // modules behind :app, which have no `minified` of their own.
    buildTypes {
        create("minified") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"

    // In this APK's own process. Instrumenting :app would load this test's
    // classes into the app, which is the arrangement that cannot see R8.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Nothing to test in debug: the subject is the minified target.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "minified" }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit)
}
