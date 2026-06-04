// Root build file. Plugins are declared here with `apply false` so the
// version catalog pins one version org-wide; each module applies what it needs.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Maven Central publishing — applied (not `apply false`) only in the two
    // library modules below. Build-time plugin only; not a runtime dependency.
    alias(libs.plugins.vanniktech.maven.publish) apply false
}
