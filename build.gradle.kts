plugins {
    alias(libs.plugins.android.application) apply false
    // AGP is already on the build classpath by the line above, so a
    // subproject asking for com.android.library *with* a version fails
    // ("already on the classpath with an unknown version"). Declaring it
    // here, once, is what makes `alias(libs.plugins.android.library)` in
    // core/data resolve.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Lint is a gate, not a report. Configured once here rather than four times,
// because a warning that only appears in a local HTML report is a warning
// nobody reads: four of them had accumulated unseen before CI ran lint at all.
subprojects {
    plugins.withId("com.android.base") {
        extensions.configure<com.android.build.api.dsl.CommonExtension>("android") {
            lint.warningsAsErrors = true
            lint.abortOnError = true
            // The three dependency-currency checks are the only lint checks
            // whose result can change with no commit to this repository:
            // an upstream release would turn CI red on a branch nobody
            // touched. They also disagree, because one reads lint's bundled
            // version data and the others query the network, so which
            // version they name depends on when you ask. Dependency currency
            // is a decision to take deliberately, not a build failure to be
            // ambushed by.
            //
            // `AndroidGradlePluginVersion` is listed on its own because
            // `NewerVersionAvailable` does not cover it: without it, an AGP
            // release turns every branch red against a pin nobody touched.
            lint.disable += setOf(
                "NewerVersionAvailable",
                "GradleDependency",
                "AndroidGradlePluginVersion",
            )
        }
    }
}
