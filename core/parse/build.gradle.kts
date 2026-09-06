import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The compiler runs on JDK 25, but the emitted bytecode must stay at 17
// (class-file major 61). Toolchain 25 alone emitted major 69, which D8
// rejects outright, so the first Android module to depend on this one would
// have failed to dex.
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
