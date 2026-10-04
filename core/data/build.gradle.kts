plugins {
    // AGP 9 has built-in Kotlin support, and applying org.jetbrains.kotlin.android
    // alongside it is a hard error. No Android module here applies it, and the
    // kotlin-android catalog alias does not exist.
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "my.pinged.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // compileOptions, not a kotlin{} block: AGP's built-in Kotlin leaves no
    // kotlin extension to configure. 17 matches :core:parse and :app, whose
    // bytecode must stay at class-file major 61 for D8.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // MigrationTestHelper reads the schema JSON out of the test APK's
        // assets, so without this every migration test fails with "Cannot find
        // the schema file in the assets folder". `directories.add` rather than
        // the `srcDirs` vararg AGP 9 deprecates.
        getByName("androidTest") { assets.directories.add("$projectDir/schemas") }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    // api: Txn exposes Direction and Confidence in its public signature.
    api(project(":core:parse"))
    // api likewise: PingedDatabase extends RoomDatabase, so a consumer cannot
    // resolve a member on it without Room on the compile classpath.
    api(libs.androidx.room.runtime)
    // Room's PagingSource return type. Pinned to Room's own version, not
    // Paging's: the generated code and this artifact's LimitOffsetPagingSource
    // are the same Room release's API.
    api(libs.androidx.room.paging)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    // A preferences store in a Room module, for `IntegrityStore` alone: the
    // integrity verdict is a fact about this database that outlives any one
    // process and has to be legible to both features. It cannot live in Room --
    // a verdict kept inside the file it judges is unreadable in exactly the
    // state it exists to report -- and it cannot live in either feature,
    // because :feature:capture writes it and :feature:ledger reads it.
    implementation(libs.androidx.datastore.preferences)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(libs.androidx.room.testing)
    // `runBlocking`, for the suspend surface DataStore gives IntegrityStore
    // and, through it, Wipe.everything.
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
