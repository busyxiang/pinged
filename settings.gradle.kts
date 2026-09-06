pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pinged"

include(":app")
include(":core:parse")
include(":core:data")
include(":feature:capture")
include(":feature:ledger")
