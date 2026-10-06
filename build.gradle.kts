// Top-level build file. Plugin versions are resolved centrally through the
// version catalog in gradle/libs.versions.toml and merely declared here so that
// every module applies the same version without duplicating it.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
}
