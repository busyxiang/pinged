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
include(":core:ui")
include(":feature:capture")
include(":feature:ledger")
include(":feature:charts")
include(":smoke")
