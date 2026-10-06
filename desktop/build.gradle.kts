import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Kotlin 2.0.21 ships the Compose Compiler as a first-party plugin; Compose
    // Multiplatform 1.7.x refuses to configure without it.
    alias(libs.plugins.kotlin.compose)
    // Exposes the `compose` dependency accessors plus the `compose.desktop`
    // native-distribution DSL below.
    alias(libs.plugins.compose.multiplatform)
    // Generates @Serializable serializers at compile time for the Ollama REST DTOs.
    alias(libs.plugins.kotlin.serialization)
}

group = "com.invoiceextract"
version = "0.1.0"

kotlin {
    // JVM 17 toolchain, shared with :app and :domain and auto-provisioned by the
    // foojay resolver in settings.gradle.kts. A 17 bytecode target also runs
    // unchanged on a JVM 21 host, which is the other supported runtime.
    jvmToolchain(17)
}

dependencies {
    // Pure-Kotlin domain layer: Invoice/InvoiceItem models, validation and the
    // extraction contracts shared with the Android app.
    implementation(project(":domain"))

    // Coroutines for the IO-dispatched extraction work and cancellable OkHttp calls.
    implementation(libs.kotlinx.coroutines.core)

    // HTTP client for the local Ollama daemon (http://127.0.0.1:11434). Chosen over
    // HttpURLConnection for real timeouts and cancelable calls that unblock on
    // coroutine cancellation, mirroring the Android network layer.
    implementation(libs.okhttp)

    // JSON (de)serialization for the Ollama request/response envelopes and the
    // extracted invoice payload. Robust by default: unknown keys are ignored and
    // explicit nulls coerce to defaults, so a model schema drift can never crash
    // the pipeline mid-parse.
    implementation(libs.kotlinx.serialization.json)

    // Apache PDFBox 3.0: the desktop fast path pulls text straight out of a digital
    // PDF's text layer, which skips OCR entirely for the common case of an exported
    // (rather than scanned) invoice. Its PDFRenderer is the same artifact's fallback
    // for raster-only scans, rendered at 200 DPI into the OCR engine below.
    implementation(libs.pdfbox)

    // Tess4J: the JNA bindings over the native Tesseract OCR engine that recognize the
    // Persian text of scanned and photographed invoices. Ships its own Windows
    // binaries, so no local Tesseract install is required at runtime.
    //
    // Excludes `pdfbox-tools`, which Tess4J pulls in transitively: it is a CLI utility
    // module the engine never calls, and it carries JUnit 5, picocli and the PDFBox
    // debugger onto the runtime classpath — several megabytes of test framework that
    // would otherwise be baked into the .exe/.msi for no reason.
    implementation("net.sourceforge.tess4j:tess4j:5.11.0") {
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
    }

    // Koin Core: the desktop object graph. The JVM artifact only — the Android
    // variants belong to :app. No compiler plugin, no KSP.
    implementation(libs.koin.core)

    // Compose Multiplatform artifacts. material3 is the common (multiplatform)
    // Material 3 library; desktop.currentOs pulls the Skiko native renderer for the
    // host OS so the JVM window can actually paint.
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    // Deliberately NOT `compose.materialIconsExtended`: that artifact is a large
    // additional download and the window uses only the handful of vector icons that
    // ship inside the core set (Check, Warning, Refresh, …) which material3 already
    // resolves transitively. Staying off the extended set keeps offline builds clean.
    implementation(compose.desktop.currentOs)

    // JUnit 4 for hermetic JVM tests of the pure mapping and parsing helpers. The
    // extractor's logic touches no Android types, so tests run on a plain JVM.
    testImplementation(libs.junit)
}

// Tesseract Persian language pack for the offline OCR engine. Fetched once from the
// official tessdata_fast mirror into this module's resources, where the engine loads
// it off the classpath at runtime (and the native installer bundles it like any
// other resource). Best-effort by design: skipped once the file exists, and reduced
// to a warning when the network is unreachable — so offline and air-gapped builds
// always proceed, and the engine reports the missing pack itself at recognition time
// instead of the build failing over a language file.
// Plain path string on purpose: the task actions capture it, and plain values keep
// that capture cheap. Cache hygiene comes from the conditional wiring below, not
// from here.
val tessDataPath =
    layout.projectDirectory.file("src/main/resources/tessdata/fas.traineddata").asFile.absolutePath

tasks.register("downloadTessData") {
    group = "build setup"
    description = "Fetches fas.traineddata for the offline OCR engine (once, best-effort)."

    // A best-effort network fetch cannot participate in the configuration cache
    // (script-captured state is not serializable); it runs outside it while the
    // rest of the graph keeps caching. The onlyIf guard below keeps it a no-op
    // on every build after the first successful fetch.
    notCompatibleWithConfigurationCache("downloads an external language pack on demand")

    onlyIf { !File(tessDataPath).exists() }

    doLast {
        try {
            val target = File(tessDataPath)
            target.parentFile.mkdirs()
            // Inlined on purpose (see above): no script-object references in actions.
            URI("https://github.com/tesseract-ocr/tessdata_fast/raw/main/fas.traineddata")
                .toURL().openStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
        } catch (cause: Exception) {
            logger.warn("downloadTessData: could not fetch fas.traineddata ({}); " +
                "image OCR will report the pack as missing until it is provided.", cause.message)
        }
    }
}

tasks.named("processResources") {
    // Wired only while the pack is missing: a configuration-time existence check, so
    // routine builds never carry the best-effort downloader in their graph and the
    // configuration cache keeps storing cleanly. The fetch build itself runs
    // uncached (the task opts out below); every build after it caches again.
    // Air-gapped checkouts can also drop fas.traineddata here by hand.
    if (!File(tessDataPath).exists()) {
        dependsOn("downloadTessData")
    }
}

compose.desktop {
    application {
        mainClass = "com.invoiceextract.desktop.MainKt"

        nativeDistributions {
            // Windows installers only: .exe (with bundled JVM) and .msi (per-machine).
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)

            packageName = "InvoiceExtract"
            packageVersion = "1.0.0"
            // WiX bakes this into the MSI/EXE version resources, which are single-byte
            // encoded by the toolchain, so it stays Latin/ASCII here. The Persian product
            // name is shown where it actually renders — inside the app window itself.
            description = "InvoiceExtract - Offline AI invoice extraction"
            vendor = "InvoiceExtract Team"
            copyright = "© 2026 InvoiceExtract. All rights reserved."

            windows {
                // Start Menu shortcut inside its own group, plus a desktop shortcut.
                menu = true
                menuGroup = "InvoiceExtract"
                shortcut = true

                // Installs into %LOCALAPPDATA% per user, so a standard (non-admin)
                // account can install and upgrade without a UAC elevation prompt.
                perUserInstall = true

                // Lets the user override the install directory in the wizard.
                dirChooser = true

                // Stable GUID MSI uses to recognise an existing install and run an
                // in-place upgrade instead of registering a second product.
                upgradeUuid = "a4c28f11-7390-4c8d-b3b4-934c9c10f821"

                // Shared by the installer, the executable and the taskbar/window icon.
                iconFile.set(project.layout.projectDirectory.file("src/main/resources/icon.ico"))
            }
        }
    }
}
