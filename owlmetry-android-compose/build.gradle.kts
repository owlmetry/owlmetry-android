plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.owlmetry.android.compose"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
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

    testOptions {
        unitTests {
            // Robolectric needs the merged Android resources, and the Compose UI
            // test harness (createComposeRule) needs them to resolve the
            // empty-activity manifest entry from compose-ui-test-manifest.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // The Compose UI module sits on top of the zero-dep core. Apps that don't
    // need the drop-in Feedback/Questionnaire UI depend on `owlmetry-android`
    // alone and stay free of Compose.
    api(project(":owlmetry-android"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // Compose UI tests run under Robolectric (JVM `test/`), so the Compose test
    // harness + the empty-activity manifest must be on the unit-test classpath.
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
