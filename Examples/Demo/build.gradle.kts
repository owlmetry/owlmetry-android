// Demo app module. Applies `com.android.application` (not the library plugin) so
// it produces an installable APK, plus the Kotlin + Compose plugins. It depends
// on BOTH library modules by project reference, so it's a build-time canary:
// any source-incompatible change to the public SDK surface breaks `assembleDebug`.
//
// No `vanniktech.maven.publish` here — the demo is never published. It also lives
// under Examples/, which release.yml paths-ignores, so a demo-only change can't
// trigger a release.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.owlmetry.android.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.owlmetry.android.demo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The SDK under test — both modules by project reference so the demo always
    // compiles against the in-tree source, not a published artifact.
    implementation(project(":owlmetry-android"))
    implementation(project(":owlmetry-android-compose"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling.preview)
}
