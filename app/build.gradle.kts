// Build script for the :app module — this is where the actual Android app is
// configured. Most later Phase 1 checkboxes will add dependencies here.

plugins {
    alias(libs.plugins.android.application)  // makes this an Android app module
    alias(libs.plugins.kotlin.android)       // Kotlin support
    alias(libs.plugins.kotlin.compose)       // Compose compiler (Kotlin 2.0+)
    alias(libs.plugins.ksp)                  // Room annotation processor
}

android {
    // Package used for the generated R class and BuildConfig. Matches CLAUDE.md.
    namespace = "com.scrollkiller"
    // Android SDK version the app is COMPILED against (uses newest APIs, guarded at runtime).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scrollkiller"  // the unique ID on the device / Play Store
        minSdk = 26                         // oldest Android version supported (per CLAUDE.md)
        targetSdk = 35                      // version the app is tested/optimized for
        versionCode = 1                     // integer that must increase on each release
        versionName = "0.1.0"               // human-readable version shown to users

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Code shrinking is off for now; turn on when we ship (Phase 5).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Kotlin/Java both target JVM 17 (bundled with current Android Studio).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true       // enable Jetpack Compose in this module
        buildConfig = true   // generate BuildConfig so DEBUG gates the diagnostics (AGP 8 defaults this off)
    }

    // Export the Room schema JSON so future migrations can diff against it.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    // Core Android + lifecycle helpers.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)  // viewModel() in composables

    // Room: on-device persistence. ksp() runs the compiler at build time.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Compose: import the BOM once, then declare artifacts without versions.
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Tools for @Preview rendering inside Android Studio (debug builds only).
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Local unit tests (run on your machine's JVM, no device needed).
    testImplementation(libs.junit)
    // Real org.json for the JVM: the framework one is a throwing stub in unit tests, and the
    // guilt-pack parser is worth testing off-device. Test classpath only — never shipped.
    testImplementation(libs.org.json)

    // Instrumented tests (run on an emulator/device).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
