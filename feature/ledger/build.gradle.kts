plugins {
    // AGP 9 has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is a hard error, so this module -- like every other Android
    // module here -- applies only the AGP plugin plus the compiler plugins it
    // genuinely needs.
    alias(libs.plugins.android.library)
    // The Compose compiler plugin does apply on top of AGP 9's built-in Kotlin.
    // It is a KotlinCompilerPluginSupportPlugin, so it attaches to whatever
    // Kotlin compilation AGP has already registered rather than registering one
    // of its own; there is no kotlin{} extension involved and none is needed.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "my.pinged.ledger"
    compileSdk = 37

    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    // Bytecode target through compileOptions, not a kotlin{} block: with AGP's
    // built-in Kotlin there is no kotlin extension here. Kept at 17 to match
    // every other module.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // `implementation`, not `api`. This was `api` because SourcesViewModel
    // reached :feature:capture's `Graph` for the database and handed
    // :feature:capture types back out; the database half of `Graph` is now
    // `Databases` in :core:data, and what remains of the edge -- CaptureStorage,
    // SourceCounters, CaptureReport -- is used inside this module rather than
    // re-exported. :app depends on :feature:capture directly for the banner.
    implementation(project(":feature:capture"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // `AndroidViewModel`, `viewModelScope` and `CreationExtras` for
    // `SourcesViewModel`. The -compose artifact rather than bare
    // lifecycle-viewmodel because Task 5's screens call `viewModel()` from this
    // module too, and it carries lifecycle-viewmodel as an `api` dependency.
    //
    // `api`, not `implementation`, and unlike the :feature:capture edge above:
    // `SourcesViewModel` now *extends* `AndroidViewModel`, so the supertype is
    // part of this module's public surface. As `implementation` the consumers
    // failed to compile with "Cannot access 'androidx.lifecycle
    // .AndroidViewModel' which is a supertype of 'SourcesViewModel'" --
    // :feature:capture's androidTest, which drives the allow-list write, and
    // any future one.
    api(libs.androidx.lifecycle.viewmodel.compose)
    // `LifecycleResumeEffect`, which is how `SourcesScreen` reads the
    // allow-list once per foreground instead of once per holder. A separate
    // artifact from the one above and only a *constraint* of it, so it is named
    // here rather than left to arrive through compose-ui. See the catalog note.
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Task 5's ledger feed. `TxnDao.feed()` is already a PagingSource; these
    // are the `Pager`/`cachedIn` half and `collectAsLazyPagingItems`, and they
    // are wired here rather than in Task 5 so the version pin lands with the
    // rest of the dependency work.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // The two things in this module that need no device: `ContrastTest`, which
    // is arithmetic over the palette -- Colour is a value class over a ULong,
    // so the ratios compute on the JVM in milliseconds instead of booting an
    // emulator to multiply six numbers -- and `DayHeaderRuleTest`, which is a
    // comparison of two Ints over a Room entity and a value class.
    testImplementation(libs.junit)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Not optional on API 37. See the note in the version catalog.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    // SupportSQLiteDatabase, so `ExportCompletenessTest` can read
    // `PRAGMA table_info` off the real encrypted database and compare the
    // export's column names against the columns SQLite actually holds.
    androidTestImplementation(libs.androidx.sqlite)
    // createComposeRule() launches a ComponentActivity, which has to exist in
    // the manifest of the APK under test. In a library module the androidTest
    // APK is built against the debug variant, so this is the sourceSet that
    // gets the stub manifest -- debugImplementation, not androidTestImplementation.
    debugImplementation(composeBom)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
