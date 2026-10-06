package com.invoiceextract.app.di

import com.invoiceextract.app.core.network.NetworkConfig
import com.invoiceextract.app.data.remote.RemoteInvoiceAiExtractor
import com.invoiceextract.app.data.batch.BatchExportManager
import com.invoiceextract.app.data.batch.BatchProcessingCoordinator
import com.invoiceextract.app.data.export.InvoiceExportManager
import com.invoiceextract.app.data.file.CacheCleaner
import com.invoiceextract.app.data.file.FileMetadataHelper
import com.invoiceextract.app.data.local.auth.UserSessionManager
import com.invoiceextract.app.data.local.db.InvoiceDatabase
import com.invoiceextract.app.data.local.quota.DailyQuotaManager
import com.invoiceextract.app.data.ocr.MlKitOcrEngine
import com.invoiceextract.app.data.ocr.PersianTextNormalizer
import com.invoiceextract.app.data.processor.DocumentProcessor
import com.invoiceextract.app.data.processor.DocumentProcessorImpl
import com.invoiceextract.app.data.processor.ImagePreprocessor
import com.invoiceextract.app.data.processor.PdfBitmapRenderer
import com.invoiceextract.app.data.repository.AuthRepositoryImpl
import com.invoiceextract.app.data.repository.InvoiceRepositoryImpl
import com.invoiceextract.app.data.session.InvoiceSessionHolder
import com.invoiceextract.app.domain.usecase.ProcessInvoiceUseCase
import com.invoiceextract.app.presentation.batch.BatchViewModel
import com.invoiceextract.app.presentation.profile.ProfileViewModel
import com.invoiceextract.app.presentation.review.ReviewViewModel
import com.invoiceextract.app.ui.screens.home.HomeViewModel
import com.invoiceextract.domain.extractor.InvoiceAiExtractor
import com.invoiceextract.domain.extractor.OcrEngine
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.repository.AuthRepository
import com.invoiceextract.domain.validation.InvoiceValidator
import androidx.room.Room
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Application-scoped Koin module: data helpers and the ViewModels of the app shell.
 *
 * Uses the classic Koin DSL (`single { }` / `viewModel { }`) with manual wiring, so
 * the graph resolves at runtime without the Koin compiler plugin or KSP.
 */
val appModule = module {

    // File import helper. Given the Application context so it can resolve
    // contentResolver and the private cache directory.
    single<FileMetadataHelper> { FileMetadataHelper(androidContext()) }

    // Phase 17 — automated cache pruning. App-scoped: one sweeper serves every caller,
    // and the work it schedules always lands on Dispatchers.IO.
    single { CacheCleaner(androidContext()) }

    // Preprocessing pipeline: pure CPU-bound graphics, no external PDF libraries.
    single { ImagePreprocessor() }
    single { PdfBitmapRenderer() }
    single<DocumentProcessor> {
        DocumentProcessorImpl(
            imagePreprocessor = get(),
            pdfBitmapRenderer = get(),
        )
    }

    // OCR: ML Kit's bundled recognizer plus Persian text repair.
    single { PersianTextNormalizer() }
    single<OcrEngine> { MlKitOcrEngine(normalizer = get()) }

    // Structured extraction (Phase 11.2). The remote extractor talks to the
    // Cloudflare Worker proxy, which holds the LLM provider keys and the failover
    // logic; the app only ships OCR text and reads one JSON object back.
    // StubInvoiceAiExtractor stays in the codebase as an offline fallback for
    // instrumented tests and dev builds without network access.
    single {
        OkHttpClient.Builder()
            .connectTimeout(NetworkConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // Lenient by design: unknown keys are ignored and explicit nulls coerce to the
    // default, so a worker schema update can never crash the client mid-parse.
    single {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    single<InvoiceAiExtractor> { RemoteInvoiceAiExtractor(androidContext(), get(), get()) }

    // Financial-integrity gate over the extracted invoice: pure Kotlin, no state, so a
    // single app-scoped instance serves every run.
    single { InvoiceValidator() }

    // Process-scoped scratch space for the invoice the user is currently reviewing. Home
    // writes the extraction result, Review reads and rewrites it as the user edits.
    single { InvoiceSessionHolder() }

    // The whole pipeline behind one use case that the ViewModels call. The quota gate is
    // injected here so the free-tier limit is enforced for every single-file run, and the
    // batch coordinator consumes the same engines under its own accounting.
    single {
        ProcessInvoiceUseCase(
            documentProcessor = get(),
            ocrEngine = get(),
            aiExtractor = get(),
            invoiceValidator = get(),
            quotaManager = get(),
        )
    }

    // Phase 8.2 — Room. One database instance for the process; the DAO and the
    // repository both derive from it. fallbackToDestructiveMigration is acceptable
    // while v1 is the only shipped schema and no user data exists yet; a real
    // Migration object replaces it the moment the schema changes.
    single {
        Room.databaseBuilder(
            androidContext(),
            InvoiceDatabase::class.java,
            "invoice_extract.db",
        ).fallbackToDestructiveMigration().build()
    }
    single { get<InvoiceDatabase>().invoiceDao() }

    // Domain contract implemented against Room; writes land on Dispatchers.IO.
    single<InvoiceRepository> { InvoiceRepositoryImpl(get()) }

    // Phase 12.1 — offline-first authentication. Preferences DataStore holds the session;
    // the guest UUID is written on first launch and never removed, so a local invoice is
    // always owned by a user identity. The repository is stateless between calls, so one
    // app-scoped instance serves the whole graph.
    single { UserSessionManager(androidContext()) }
    single<AuthRepository> { AuthRepositoryImpl(get()) }

    // Phase 13.1 — the daily free-tier quota. Preferences DataStore holds the counter, the
    // epoch day it last reset on, and the user's personal API key; rollover is arithmetic
    // on the epoch day, so no alarm or job is needed. A single app-scoped instance serves
    // the whole graph, and every mutation is one serialized DataStore edit.
    single { DailyQuotaManager(androidContext()) }

    // Phase 9.1 — zero-dependency CSV / Excel XML export into a SAF Uri.
    single { InvoiceExportManager(androidContext()) }

    // Phase 10.1 — batch engine: the sequential queue coordinator with per-file fault
    // isolation, and the consolidated multi-invoice CSV / Excel exporter.
    single {
        BatchProcessingCoordinator(
            documentProcessor = get(),
            ocrEngine = get(),
            aiExtractor = get(),
            invoiceValidator = get(),
            invoiceRepository = get(),
        )
    }
    single { BatchExportManager(androidContext()) }

    viewModel { HomeViewModel(get(), get(), get()) }

    viewModel {
        com.invoiceextract.app.ui.screens.review.ReviewViewModel(get(), get())
    }

    // Phase 8.2 canonical Review & Edit screen: session + validator + repository
    // + export manager.
    viewModel {
        ReviewViewModel(get(), get(), get(), get())
    }

    // Phase 10.2 — Batch screen: the sequential queue plus the consolidated exporter,
    // backed by the same file helper that stages single documents in the cache.
    // `get()` resolves the Application for the AndroidViewModel; Koin's android module
    // binds the application context to that type automatically.
    viewModel {
        BatchViewModel(
            get(),
            coordinator = get(),
            exportManager = get(),
            fileHelper = get(),
        )
    }

    // Phase 12.2 — Profile & Authentication sheet. A plain ViewModel over the AuthRepository
    // contract: no Application context is needed, since credential failures are reported as
    // Persian constants rather than resolved from string resources.
    viewModel { ProfileViewModel(get()) }
}

/** All Koin modules owned by the :app feature layer. */
val appModules: List<Module> = listOf(appModule)
