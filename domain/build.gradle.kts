plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.invoiceextract"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // Phase 0 domain layer is pure Kotlin with zero third-party dependencies,
        // so it must stay free of any JVM-target or library assumptions.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Pure-Kotlin coroutines for the repository contract (Flow). No Android,
    // no Room — the interface stays testable on the JVM.
    implementation(libs.kotlinx.coroutines.core)

    // JUnit 4 for the hermetic unit tests of the domain layer (Phase 15.1). The domain
    // module has no Android dependency, so its tests run on a plain JVM.
    testImplementation(libs.junit)
}
