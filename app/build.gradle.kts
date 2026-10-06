import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0.21 ships the Compose Compiler as a first-party plugin, so the
    // legacy androidx.compose.compiler:compiler artifact is not needed.
    alias(libs.plugins.kotlin.compose)
    // KSP for Room codegen (must track Kotlin 2.0.21: 2.0.21-1.0.27).
    alias(libs.plugins.ksp)
    // Generates @Serializable serializers for the network DTOs at compile time.
    alias(libs.plugins.kotlin.serialization)
}

// Phase 16 — secrets are injected from local.properties into BuildConfig so they
// never live in committed Kotlin source. A missing file or property degrades to
// empty values, which the worker rejects — fail closed — so a fresh clone builds
// without secrets and simply cannot call the proxy until configured.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}
val baseUrl = localProps.getProperty("INVOICE_BASE_URL")
    ?: "https://invoice-extract-proxy.invoice-aierix.workers.dev"
val clientKey = localProps.getProperty("INVOICE_CLIENT_KEY") ?: ""

android {
    namespace = "com.invoiceextract.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.invoiceextract.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Phase 16 — the worker URL and shared client key. The values are baked into
        // BuildConfig at compile time; NetworkConfig reads them from there.
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "CLIENT_KEY", "\"$clientKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local testing only: lets a release-variant APK install on a device without
            // a real keystore. A production upload must be re-signed with a proper
            // upload key before it reaches Play Console.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Local pure-Kotlin domain layer: models, validation and extraction contracts.
    implementation(project(":domain"))

    // Core AndroidX + lifecycle-aware Compose integration.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose, with every artifact version pinned by the BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation Compose.
    implementation(libs.androidx.navigation.compose)

    // EXIF metadata, used to correct captured-image orientation before OCR.
    implementation(libs.androidx.exifinterface)

    // Phase 12.1 — Preferences DataStore for the offline-first user session.
    implementation(libs.androidx.datastore.preferences)

    // Phase 18 — AndroidX Core Splashscreen. installSplashScreen() handles the
    // Android 12+ system splash and falls back to a themed window on older APIs.
    implementation(libs.androidx.core.splashscreen)

    // On-device text recognition (bundled model, fully offline). ML Kit has no
    // Arabic/Persian recognizer, so this is the Latin script recognizer for now.
    implementation(libs.mlkit.text.recognition)

    // Koin - pure-Kotlin dependency injection, no KSP and no annotation processing.
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Room SQLite persistence (Phase 8.1). Compiler goes on the KSP processor
    // path, never the runtime classpath.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Phase 11.2 — real AI extraction through the Cloudflare Worker proxy.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Debug-only body logging: the interceptor prints OCR payloads, which are
    // sensitive document content, so it never ships in a release build.
    debugImplementation(libs.okhttp.logging.interceptor)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // JUnit 4 for the JVM unit tests of the pure data helpers (Phase 15.1): the
    // normalizer and the CSV exporter touch no Android types, so they test without an
    // emulator or Robolectric.
    testImplementation(libs.junit)
}
