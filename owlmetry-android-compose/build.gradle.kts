import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.vanniktech.maven.publish)
}

// Published coordinates. GROUP + VERSION_NAME come from gradle.properties so the
// release workflow has a single bump target; keep VERSION_NAME in sync with
// OwlmetryVersion.CURRENT (the runtime SDK-version constant stamped on events).
group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

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

// AGP 8.7's JavaDocGenerationTask bundles an old Dokka whose ASM can't read the
// `PermittedSubclasses` attribute on the Compose/AndroidX dependencies' sealed
// classes (JDK 17+ bytecode → "PermittedSubclasses requires ASM9"), so javadoc
// generation crashes and would fail every Maven Central publish. The core module
// has no Compose deps and generates fine; only this module trips it. We don't ship
// API docs inside the jar (they live at owlmetry.com/docs), so publish an EMPTY
// javadoc jar: disabling the Dokka generation step leaves vanniktech's javaDocJar
// to zip nothing into a valid, empty javadoc artifact that Central still accepts.
tasks.matching { it.name == "javaDocReleaseGeneration" }.configureEach {
    enabled = false
}

// Maven Central (Sonatype Central Portal) publishing for the Compose UI artifact.
//
// Signing + Sonatype credentials are supplied at publish time via Gradle
// properties / env vars — never hardcoded here. The CI release job must provide:
//   ORG_GRADLE_PROJECT_mavenCentralUsername      — Central Portal token username
//   ORG_GRADLE_PROJECT_mavenCentralPassword      — Central Portal token password
//   ORG_GRADLE_PROJECT_signingInMemoryKey        — ASCII-armored GPG secret key
//   ORG_GRADLE_PROJECT_signingInMemoryKeyPassword — GPG key passphrase
// (set as repo secrets / actions env so they reach the `publish` task).
mavenPublishing {
    coordinates("com.owlmetry", "owlmetry-android-compose", version.toString())

    pom {
        name.set("Owlmetry Android SDK — Compose UI")
        description.set(
            "Optional Jetpack Compose UI for the Owlmetry Android SDK — drop-in " +
                "OwlFeedbackView, OwlQuestionnaireView, and the owlScreen modifier. Builds on owlmetry-android."
        )
        url.set("https://owlmetry.com/github")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("owlmetry")
                name.set("Owlmetry")
                url.set("https://owlmetry.com")
            }
        }

        scm {
            url.set("https://github.com/owlmetry/owlmetry-android")
            connection.set("scm:git:git://github.com/owlmetry/owlmetry-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/owlmetry/owlmetry-android.git")
        }
    }

    // Sonatype Central Portal, auto-release after the upload validates.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    // Sign all publications (skipped automatically when no signing key is present,
    // e.g. a local publishToMavenLocal smoke test).
    signAllPublications()
}
