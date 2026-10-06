package com.invoiceextract.desktop.di

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.batch.DesktopBatchCoordinator
import com.invoiceextract.desktop.data.document.DesktopDocumentProcessor
import com.invoiceextract.desktop.data.engine.DesktopOllamaLifecycleManager
import com.invoiceextract.desktop.data.export.DesktopAccountingExportManager
import com.invoiceextract.desktop.data.export.DesktopAnalyticsExportManager
import com.invoiceextract.desktop.data.export.DesktopBatchExportManager
import com.invoiceextract.desktop.data.export.DesktopGlobalAccountingExportManager
import com.invoiceextract.desktop.data.export.DesktopClientStatementExportManager
import com.invoiceextract.desktop.data.export.DesktopPettyCashExportManager
import com.invoiceextract.desktop.data.backup.DesktopBackupManager
import com.invoiceextract.desktop.data.automation.DesktopFolderWatcherService
import com.invoiceextract.desktop.data.licensing.DesktopLicenseManager
import com.invoiceextract.desktop.data.export.DesktopFormalInvoiceGenerator
import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.domain.validation.DuplicateInvoiceDetector
import com.invoiceextract.desktop.data.ocr.DesktopImageOcrEngine
import com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * The whole desktop object graph, built with the classic Koin DSL (`single { }`) and
 * manual wiring — no compiler plugin, no KSP — exactly like the Android app's module.
 *
 * Every binding is app-scoped because every one of them is stateless or safely shared:
 * the OkHttp client owns a connection pool that must be reused, the Json instance
 * carries configuration, and the engines and validators hold no mutable state.
 *
 * Declared as a `val` list so start-up composes it into the graph:
 *
 * ```
 * startKoin { modules(desktopModules) }
 * ```
 */
val desktopModule: Module = module {

    // Text repair shared by the document stage: pure, no state, one instance serves
    // every extraction.
    single { DesktopPersianNormalizer() }

    // Offline Tesseract OCR (Persian) for photos, scans and raster-only PDFs. App-scoped
    // for a reason: the recognizer is lazily built once and guarded by its own lock, so
    // every extraction shares one warm engine instead of paying for a cold boot each.
    single { DesktopImageOcrEngine(normalizer = get()) }

    // The document stage. Takes the normalizer so its output is already sanitized
    // before the model ever sees it, and the OCR engine for everything without a
    // usable text layer.
    single { DesktopDocumentProcessor(normalizer = get(), imageOcrEngine = get()) }

    // OkHttp for the local Ollama daemon. Timeouts are deliberately generous: local LLM
    // reasoning for a dense invoice takes tens of seconds even on a fast CPU, and the
    // platform default of 10 seconds would fail every single extraction. All three get
    // the full three minutes now — the cold-start case is the one that bites, because
    // the daemon loads the model into memory on first request and can hold the socket
    // idle for minutes before answering, which surfaces as SocketTimeoutException on an
    // otherwise healthy machine. Connect is included: the daemon serializes model loads
    // and can be slow to accept while it is still busy with one.
    single {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // Lenient by design, and must stay lenient: the Ollama chat envelope carries
    // telemetry the DTOs do not model, and the model's payload can include fields the
    // schema does not know. Unknown keys are ignored and explicit nulls coerce to the
    // default, so a schema drift can never crash the pipeline mid-parse.
    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    // The local AI engine. Bound as the concrete type the use case injects.
    single { LocalOllamaAiExtractor(client = get(), json = get()) }

    // Auto-provisions the Ollama daemon + model on Windows and publishes each stage
    // as OllamaState. App-scoped: the state flow must survive across window-scoped
    // view models so a reopened window sees the already-ready engine instantly.
    single { DesktopOllamaLifecycleManager(get()) }

    // File-backed invoice history in %APPDATA%/InvoiceExtract: one JSON document rewritten
    // atomically behind a Mutex, so the store survives a crash mid-write and a concurrent
    // save and delete never interleave. It gets its own Json rather than the shared one:
    // that instance is tuned for Ollama envelopes, while the store wants a human-readable
    // (pretty-printed) file that stays forward-compatible by ignoring unknown keys. Bound
    // concretely as well as through the domain contract: the backup manager needs the
    // concrete reload hook, while the view model keeps reading the contract.
    single {
        DesktopInvoiceRepository(
            json = Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            },
        )
    }
    single<InvoiceRepository> { get<DesktopInvoiceRepository>() }

    // Financial-integrity gate: pure Kotlin, no state, one app-scoped instance.
    single { InvoiceValidator() }

    // Double-entry guard: pure comparison over the archive, no state, one app-scoped
    // instance like the validator beside it.
    single { DuplicateInvoiceDetector(normalizer = get()) }

    // The warehouse-code table behind the *کد کالا* column. Same file-store recipe as
    // the invoice history (its own pretty-printed JSON, unknown keys ignored), so the
    // mappings survive upgrades and stay human-inspectable.
    single {
        ProductMappingRepository(
            normalizer = get(),
            json = Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            },
        )
    }

    // The whole pipeline behind one call that the presentation layer invokes.
    single {
        DesktopProcessInvoiceUseCase(
            documentProcessor = get(),
            ollamaExtractor = get(),
            mappingRepository = get(),
            invoiceValidator = get(),
        )
    }

    // Sequential multi-file runs with per-file fault isolation; stateless apart from
    // its injected collaborators, so one app-scoped instance serves every batch.
    single {
        DesktopBatchCoordinator(
            processInvoiceUseCase = get(),
            invoiceRepository = get(),
        )
    }

    // Builds the consolidated ledger files. Pure generation, no state — one instance.
    single { DesktopBatchExportManager() }

    // Builds the Sepidar/Holoo import sheets, single and consolidated. Pure
    // generation, no state — one instance, like the ledger manager beside it.
    single { DesktopAccountingExportManager() }

    // Builds the agency client statements (project cost + markup). Pure
    // generation, no state — one instance, like the managers beside it.
    single { DesktopClientStatementExportManager() }

    // Builds the petty-cash settlement sheets. Pure generation, no state —
    // one instance, like the managers beside it.
    single { DesktopPettyCashExportManager() }

    // Builds the executive BI analytics workbook. Pure generation, no state —
    // one instance, like the managers beside it.
    single { DesktopAnalyticsExportManager() }

    // Builds the QuickBooks/Xero import sheets, single and consolidated. Pure
    // generation, no state — one instance, like the managers beside it.
    single { DesktopGlobalAccountingExportManager() }

    // Builds the printable formal A4 tax invoice. Pure generation, no state —
    // one instance, like the managers beside it.
    single { DesktopFormalInvoiceGenerator() }

    // Packs and restores the whole merchant state (invoices plus mappings) as
    // one transactional zip. App-scoped: the stores it reloads are app-scoped too.
    single {
        DesktopBackupManager(
            invoiceRepository = get(),
            mappingRepository = get(),
        )
    }

    // Watches a scanner hot folder on its own IO scope, turning fresh scans
    // into saved invoices. App-scoped with the pipeline it drives; the window
    // shuts it down on close so no watch thread outlives it.
    single {
        DesktopFolderWatcherService(
            processInvoiceUseCase = get(),
            invoiceRepository = get(),
            lifecycleManager = get(),
            licenseManager = get(),
        )
    }

    // Demo quota and license activation behind the commercial trial. App-scoped:
    // the quota meter must survive window restarts, or deleting nothing would
    // mint fresh trial runs.
    single { DesktopLicenseManager() }
}

/** All Koin modules owned by the desktop front-end. */
val desktopModules: List<Module> = listOf(desktopModule)

private const val CONNECT_TIMEOUT_SECONDS = 180L
private const val READ_TIMEOUT_SECONDS = 180L
private const val WRITE_TIMEOUT_SECONDS = 180L
