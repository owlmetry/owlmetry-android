pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "owlmetry-android"

include(":owlmetry-android")
include(":owlmetry-android-compose")

// Demo app — a build-time canary that always exercises the public SDK + Compose
// UI surface. Lives under Examples/ (release.yml paths-ignores it) and carries no
// publish plugin, so it never ships to Maven Central. Mirrors the Swift SDK's
// Examples/Demo iOS app, section for section.
include(":examples:demo")
project(":examples:demo").projectDir = file("Examples/Demo")
