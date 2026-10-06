package com.invoiceextract.desktop.presentation

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.ai.OllamaStatus
import com.invoiceextract.desktop.data.ai.PullProgress
import com.invoiceextract.desktop.data.batch.DesktopBatchCoordinator
import com.invoiceextract.desktop.data.batch.DesktopBatchProgress
import com.invoiceextract.desktop.data.document.InvoiceFileFormats
import com.invoiceextract.desktop.data.engine.DesktopOllamaLifecycleManager
import com.invoiceextract.desktop.data.engine.OllamaState
import androidx.compose.ui.graphics.ImageBitmap
import com.invoiceextract.desktop.data.mapping.ProductMapping
import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.domain.validation.DuplicateInvoiceDetector
import com.invoiceextract.desktop.domain.validation.DuplicateMatch
import com.invoiceextract.desktop.domain.validation.withGlobalTaxIdAudit
import com.invoiceextract.desktop.domain.validation.withNationalIdAudit
import com.invoiceextract.desktop.data.preview.DesktopDocumentPreviewManager
import com.invoiceextract.desktop.domain.analytics.DashboardAnalytics
import com.invoiceextract.desktop.data.reconciliation.StatementParseFailure
import com.invoiceextract.desktop.data.reconciliation.StatementParseOutcome
import com.invoiceextract.desktop.data.reconciliation.parseBankStatement
import com.invoiceextract.desktop.domain.reconciliation.ReconciliationMatch
import com.invoiceextract.desktop.domain.reconciliation.ReconciliationResult
import com.invoiceextract.desktop.domain.reconciliation.matchStatement
import com.invoiceextract.desktop.presentation.ui.theme.toPersianDigits
import com.invoiceextract.desktop.domain.analytics.PettyCashSettlement
import com.invoiceextract.desktop.domain.analytics.availableYears
import com.invoiceextract.desktop.domain.analytics.computeAnalytics
import com.invoiceextract.desktop.domain.analytics.gregorianYearOf
import com.invoiceextract.desktop.domain.analytics.jalaliYearOf
import com.invoiceextract.desktop.data.export.AccountingTemplate
import com.invoiceextract.desktop.data.export.ClientStatementConfig
import com.invoiceextract.desktop.data.export.DesktopAccountingExportManager
import com.invoiceextract.desktop.data.export.DesktopAnalyticsExportManager
import com.invoiceextract.desktop.data.export.DesktopBatchExportManager
import com.invoiceextract.desktop.data.export.DesktopGlobalAccountingExportManager
import com.invoiceextract.desktop.data.export.GlobalAccountingTemplate
import com.invoiceextract.desktop.data.export.DesktopClientStatementExportManager
import com.invoiceextract.desktop.data.export.DesktopCommercialInvoiceGenerator
import com.invoiceextract.desktop.data.export.DesktopFormalInvoiceGenerator
import com.invoiceextract.desktop.data.export.DesktopPettyCashExportManager
import com.invoiceextract.desktop.data.backup.BackupResult
import com.invoiceextract.desktop.data.backup.DesktopBackupManager
import com.invoiceextract.desktop.data.backup.RestoreResult
import com.invoiceextract.desktop.data.licensing.DesktopLicenseManager
import com.invoiceextract.desktop.data.licensing.LicenseState
import com.invoiceextract.desktop.data.automation.DesktopFolderWatcherService
import com.invoiceextract.desktop.data.automation.FolderWatcherConfig
import com.invoiceextract.desktop.data.automation.WatcherEvent
import com.invoiceextract.desktop.data.export.InvoiceCsvExporter
import com.invoiceextract.desktop.data.export.InvoiceXmlSpreadsheetExporter
import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.model.isForeignCurrency
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.validation.InvoiceValidator
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the desktop window's state and every action it can take.
 *
 * Deliberately not an AndroidX `ViewModel`: that class exists to survive Android
 * configuration changes, which a desktop window does not have. Its scope is tied to the
 * window instead, and [dispose] is called once on close — so a closed window drops its
 * coroutines and a reopened one starts clean.
 *
 * **State.** Everything the UI reads is a cold [StateFlow] on the hot side, so the window
 * recomposes on change and never polls. The invoice is never exposed mutably: edits go
 * through [updateItem] / [updateMetadata], each of which re-validates before emitting, so
 * an invariant of "what you see is always validated" is impossible to break by editing
 * fields in the wrong order.
 *
 * **Error handling.** No method throws to the caller. A dropped non-PDF file, an unwritable
 * export target and a failed extraction all become a message the UI can show, because a
 * window that crashes on bad input is a window the user closes once and never reopens.
 *
 * @property processInvoiceUseCase The extract → structure → validate pipeline.
 * @property ollamaExtractor Read for the live health probe of the local daemon.
 * @property invoiceValidator Re-run after every edit so the verdict is never stale.
 * @property invoiceRepository The persistent history behind `savedInvoices` and the
 *   save/delete actions.
 * @property batchCoordinator Runs multi-file drops through the pipeline with per-file
 *   fault isolation, feeding [batchProgress].
 * @property batchExportManager Builds the consolidated ledger files from a finished batch.
 * @property accountingExportManager Builds the Sepidar/Holoo import sheets, single and
 *   consolidated.
 * @property clientStatementExportManager Builds the agency client statements
 *   (project cost plus markup).
 * @property analyticsExportManager Builds the executive BI analytics workbook
 *   from the dashboard snapshot.
 * @property pettyCashExportManager Builds the petty-cash settlement sheets.
 * @property formalInvoiceGenerator Builds the printable formal A4 tax invoice.
 * @property commercialInvoiceGenerator Builds the printable commercial/tax
 *   invoice for the global workspace.
 * @property backupManager Packs and restores the whole merchant state as one
 *   transactional zip.
 * @property watcherService The scanner hot-folder loop; shut down with the window.
 * @property licenseManager The demo quota and license activation behind the
 *   commercial trial.
 * @property mappingRepository The warehouse-code table behind `productMappings` and the
 *   code-cell auto-sync; persistence failures surface on the transient outcome channel,
 *   never as a screen-level error.
 * @property duplicateDetector The double-entry guard checked on every processed
 *   invoice against the archive.
 */

/**
 * The dashboard working area selected in the sidebar.
 *
 * History is deliberately not a tab: the archive floats above every state as a dialog,
 * so it never replaces the task the user is in the middle of.
 */
enum class NavTab {
    SCANNER,
    ANALYTICS,
    BATCH,
}
class DesktopViewModel(
    private val processInvoiceUseCase: DesktopProcessInvoiceUseCase,
    private val ollamaExtractor: LocalOllamaAiExtractor,
    private val invoiceValidator: InvoiceValidator,
    private val invoiceRepository: InvoiceRepository,
    private val batchCoordinator: DesktopBatchCoordinator,
    private val batchExportManager: DesktopBatchExportManager,
    private val accountingExportManager: DesktopAccountingExportManager,
    private val analyticsExportManager: DesktopAnalyticsExportManager =
        DesktopAnalyticsExportManager(),
    private val globalAccountingExportManager: DesktopGlobalAccountingExportManager =
        DesktopGlobalAccountingExportManager(),
    private val clientStatementExportManager: DesktopClientStatementExportManager =
        DesktopClientStatementExportManager(),
    private val pettyCashExportManager: DesktopPettyCashExportManager =
        DesktopPettyCashExportManager(),
    private val formalInvoiceGenerator: DesktopFormalInvoiceGenerator =
        DesktopFormalInvoiceGenerator(),
    private val commercialInvoiceGenerator: DesktopCommercialInvoiceGenerator =
        DesktopCommercialInvoiceGenerator(),
    private val backupManager: DesktopBackupManager,
    private val watcherService: DesktopFolderWatcherService,
    private val licenseManager: DesktopLicenseManager,
    private val lifecycleManager: DesktopOllamaLifecycleManager,
    private val mappingRepository: ProductMappingRepository,
    private val duplicateDetector: DuplicateInvoiceDetector,
) {

    private val _uiState = MutableStateFlow<DesktopUiState>(DesktopUiState.Idle)
    val uiState: StateFlow<DesktopUiState> = _uiState.asStateFlow()

    // Starts "not running" rather than a neutral state: if the daemon never answers, the
    // header must say so, not silently claim readiness. The first probe refreshes it.
    private val _ollamaStatus = MutableStateFlow<OllamaStatus>(OllamaStatus.OllamaNotRunning)
    val ollamaStatus: StateFlow<OllamaStatus> = _ollamaStatus.asStateFlow()

    /**
     * Auto-provisioning state of the local Ollama stack, owned by
     * [DesktopOllamaLifecycleManager]. The status banner renders this and the
     * invoice actions stay disabled until it becomes [OllamaState.Ready].
     */
    val ollamaState: StateFlow<OllamaState> = lifecycleManager.state

    /**
     * The live state of an in-app model download, or `null` when none has started.
     *
     * The missing-model panel renders this instead of its download button: byte counters
     * arrive straight from the daemon's streaming pull endpoint, so the bar tracks the
     * transfer in real time and the user watching a 1.9 GB download always knows it is
     * moving and roughly how far in it is.
     */
    private val _pullProgress = MutableStateFlow<PullProgress?>(null)
    val pullProgress: StateFlow<PullProgress?> = _pullProgress.asStateFlow()

    /**
     * One transient outcome, or `null` when there is nothing to report.
     *
     * Export and persistence failures must not become [DesktopUiState.Error]: that state
     * replaces the invoice on screen with a message, so a failed save would throw away the
     * invoice the user is trying to keep. This flow reports the outcome without touching
     * the invoice.
     */
    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    /**
     * Window-scoped. A [SupervisorJob] so a failed extraction cannot cancel the status
     * probe, and so one dropping error does not take down the whole window.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Every invoice the user has kept, newest first, straight off the persistent store.
     *
     * Collected eagerly so the store's lazy load happens while the window is opening and
     * the history is already in memory by the time any panel asks for it. The initial
     * value is an empty list — a missing store is not an error — and every later save and
     * delete re-emits through the repository, so the history panel never reloads itself.
     */
    val savedInvoices: StateFlow<List<Invoice>> = invoiceRepository.getInvoices()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The analytics period filter: a Jalali year, or `null` for all years.
     *
     * Owned here rather than in Compose state so the selection survives tab
     * switches and recomposition storms. Starts unscoped: a fresh dashboard
     * shows the whole archive until the user picks a year chip.
     */
    val selectedAnalyticsYear: MutableStateFlow<Int?> = MutableStateFlow(null)

    /**
     * The dashboard language: Jalali buckets and years while false, Gregorian
     * while true. Owned here (rather than read in Compose) so the snapshot,
     * the chips and the period scope always agree — three readers, one value.
     *
     * Flipping it clears the selected year: a Jalali 1403 and a Gregorian
     * 2026 are incomparable periods, so carrying a selection across the
     * switch would strand the dashboard on an empty scope.
     */
    val analyticsEnglish: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Scopes the dashboard language; see [analyticsEnglish]. */
    fun setAnalyticsEnglish(english: Boolean) {
        if (analyticsEnglish.value == english) return
        analyticsEnglish.value = english
        selectedAnalyticsYear.value = null
    }

    /**
     * Scopes the analytics dashboard to [year], or back to the whole archive
     * for `null` — the "All Years" chip.
     */
    fun selectAnalyticsYear(year: Int?) {
        selectedAnalyticsYear.value = year
    }

    /**
     * The Mini-BI snapshot behind the analytics dashboard, recomputed from the archive on
     * every save and delete — and rescoped by the period filter above.
     *
     * Derived with [stateIn] over a [combine] so late collectors still get the
     * latest snapshot instantly: the store re-emits the whole archive on each mutation,
     * and the pure [computeAnalytics] reduction turns that emission into fresh KPIs,
     * vendor shares and monthly buckets. The initial value is the empty snapshot, so the
     * dashboard renders its empty state instead of waiting on the store.
     */
    val analytics: StateFlow<DashboardAnalytics> =
        combine(savedInvoices, selectedAnalyticsYear, analyticsEnglish) { invoices, year, english ->
            val scoped = if (year == null) {
                invoices
            } else {
                // Either calendar may name the selected year: Jalali years live
                // in 1390..1410 and Gregorian in 2000..2099, so the two ranges
                // can never collide on one value.
                invoices.filter {
                    jalaliYearOf(it.date) == year || gregorianYearOf(it.date) == year
                }
            }
            computeAnalytics(scoped, english)
        }.stateIn(scope, SharingStarted.Eagerly, DashboardAnalytics())

    /**
     * Distinct valid Jalali years present in the archive, newest first — the
     * period filter's chips. Recomputed from the *unfiltered* archive, so the
     * chips never narrow themselves out of existence.
     */
    val availableAnalyticsYears: StateFlow<List<Int>> = combine(
        savedInvoices,
        analyticsEnglish,
    ) { invoices, english ->
        availableYears(invoices, english)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The hot-folder configuration: target directory and whether the watch
     * loop runs. Owned by the watcher service so it survives recomposition;
     * the dialog writes through the funnels below.
     */
    val watcherConfig: StateFlow<FolderWatcherConfig> = watcherService.config

    /**
     * The latest hot-folder outcome, or `null` when there is no toast to show.
     * Each arrival overwrites the previous line — the banner always reports
     * the freshest file.
     */
    val latestWatcherEvent: StateFlow<WatcherEvent?> = watcherService.latestEvent

    /**
     * Spec-facing alias for [latestWatcherEvent]: the toast the banner shows.
     * A read-through `get`, so both names always observe the same event.
     */
    val latestAutoEvent: StateFlow<WatcherEvent?>
        get() = latestWatcherEvent

    /** Points the watcher at [path], creating the folder when needed. */
    fun setWatchedFolder(path: String) {
        watcherService.setFolder(path)
    }

    /**
     * Spec-facing alias for [setWatchedFolder]: picks the scanner directory.
     */
    fun setWatcherPath(path: String) {
        setWatchedFolder(path)
    }

    /** Turns the watch loop on or off; enabling needs a usable folder. */
    fun toggleWatcher(enabled: Boolean) {
        watcherService.setEnabled(enabled)
    }

    /** Dismisses the watcher toast; the archive is untouched. */
    fun dismissWatcherToast() {
        watcherService.dismissEvent()
    }

    /**
     * The license state behind donation-ware: always activated, unlimited.
     * Surfaced for the header and the exporters; nothing gates on it anymore.
     */
    val licenseState: StateFlow<LicenseState> = licenseManager.state

    /**
     * The dashboard working area currently on screen.
     *
     * Owned here rather than in Compose state so the canvas survives recomposition
     * storms and every entry point — sidebar taps, batch auto-switch, reset — writes
     * through one funnel. Starts on the scanner: a fresh window is a drop zone first.
     */
    val currentNavTab: MutableStateFlow<NavTab> = MutableStateFlow(NavTab.SCANNER)

    /**
     * Switches the dashboard working area, e.g. back to the scanner after the
     * archive hands an invoice to the editor. A named funnel (rather than
     * writing [currentNavTab] inline) so every entry point reads the same.
     */
    fun selectTab(tab: NavTab) {
        currentNavTab.value = tab
    }

    /**
     * The warehouse-code table behind the management dialog, newest mapping last,
     * straight off the persistent store.
     *
     * Collected eagerly for the same reason as [savedInvoices]: the dialog must open
     * onto a populated table, not onto a loading spinner. A missing store is an empty
     * table, never an error.
     */
    val productMappings: StateFlow<List<ProductMapping>> = mappingRepository.getMappings()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The archived invoice that looks like a re-entry of the one on screen, or `null`
     * when the archive is clean.
     *
     * Set by [runDuplicateCheck] on every processed invoice; cleared when a new run
     * starts, when the window resets, when an archived record is opened deliberately,
     * and when the user dismisses it. The banner reads this and nothing else, so a
     * `null` here always means silence.
     */
    val duplicateMatch = MutableStateFlow<DuplicateMatch?>(null)

    /**
     * The invoice document the split-view preview reads, or `null` when no invoice is
     * on screen.
     *
     * Set from the dropped file on [processFile] and from the stored origin path on
     * [loadSavedInvoice]. The preview manager resolves a missing or deleted file into
     * its Persian fallback on its own, so this flow never filters — the pane decides
     * what to draw from the manager's state, not from nullability here.
     */
    private val _currentSourceFile = MutableStateFlow<File?>(null)
    val currentSourceFile: StateFlow<File?> = _currentSourceFile.asStateFlow()

    /**
     * Whether the split-view preview pane is open. Starts open: the first thing a
     * reviewer wants beside a fresh extraction is the page it came from.
     */
    val isPreviewPaneVisible: MutableStateFlow<Boolean> = MutableStateFlow(true)

    /** The preview engine behind the split-view pane; closed on [dispose]. */
    private val previewManager = DesktopDocumentPreviewManager()

    /** Rendered pages in the open preview document; `0` when empty. */
    val previewPageCount: StateFlow<Int> = previewManager.pageCount

    /** Zero-based preview page on screen. */
    val previewPageIndex: StateFlow<Int> = previewManager.currentPageIndex

    /** The preview bitmap the pane draws, or `null` while loading or on failure. */
    val previewBitmap: StateFlow<ImageBitmap?> = previewManager.currentBitmap

    /** Persian fallback sentence when the source cannot be shown; `null` otherwise. */
    val previewError: StateFlow<String?> = previewManager.errorMessage

    /** Steps the preview one page forward; clamped at the last page. */
    fun nextPreviewPage() {
        scope.launch { previewManager.nextPage() }
    }

    /** Steps the preview one page back; clamped at the first page. */
    fun prevPreviewPage() {
        scope.launch { previewManager.prevPage() }
    }

    /** Opens or collapses the split-view preview pane. */
    fun togglePreviewPane() {
        isPreviewPaneVisible.value = !isPreviewPaneVisible.value
    }

    /**
     * The live snapshot of a multi-file batch run, or `null` when no batch is (or was)
     * on screen.
     *
     * The batch panel renders this instead of the normal state machine: progress counts
     * arrive per file from the coordinator, so the bar and the per-row badges track the
     * run in real time. It stays non-null after the run finishes — and after a cancel —
     * until [clearBatch], so partial results remain exportable and dismissible.
     */
    private val _batchProgress = MutableStateFlow<DesktopBatchProgress?>(null)
    val batchProgress: StateFlow<DesktopBatchProgress?> = _batchProgress.asStateFlow()

    /** The running batch collector, if any. Cancelled on replace, on cancel, on clear. */
    private var batchJob: Job? = null

    init {
        // Start auto-provisioning immediately so a fresh install downloads, installs
        // and starts Ollama without any manual step. The banner follows each stage.
        scope.launch { lifecycleManager.ensureReady() }

        // Probe immediately so the header does not spend the first seconds of the window
        // claiming the daemon is down when it is simply the startup race.
        scope.launch { refreshOllamaStatus() }

        // Then on a slow loop: the user starts and stops Ollama outside the app, and the
        // header must follow without a manual refresh button. The probe is a localhost
        // call, so the cost of polling while the window is idle is negligible.
        scope.launch {
            while (isActive) {
                delay(OLLAMA_STATUS_REFRESH_INTERVAL_MS)
                refreshOllamaStatus()
            }
        }
    }

    /**
     * Re-runs auto-provisioning after an [OllamaState.Error]. The manager is
     * mutex-guarded, so a double tap cannot start two installers at once.
     */
    fun retryOllama() {
        scope.launch { lifecycleManager.ensureReady() }
    }

    /**
     * Runs [file] through the pipeline.
     *
     * Anything that is not a supported invoice file (PDF or image) is rejected locally
     * without launching the pipeline: a folder dropped by accident would otherwise travel
     * all the way to the use case before failing, and the error would be less actionable.
     */
    fun processFile(file: File) {
        // Interaction gating: the engine is not provisioned yet, so the run would
        // only fail after a long wait. The banner already explains the stage.
        if (ollamaState.value !is OllamaState.Ready) {
            _uiState.value = DesktopUiState.Error(OLLAMA_NOT_READY_MESSAGE)
            return
        }

        // A second drop while the first is still in flight would race the two results; the
        // later one can win with an invoice for a file the user is no longer looking at.
        if (_uiState.value is DesktopUiState.Processing) return

        if (!file.isFile || !InvoiceFileFormats.isSupported(file.extension)) {
            _uiState.value = DesktopUiState.Error(NOT_A_SUPPORTED_FILE_MESSAGE)
            return
        }

        scope.launch {
            _uiState.value = DesktopUiState.Processing(file.name)
            _currentSourceFile.value = file
            previewManager.loadDocument(file)
            // A previous invoice's warning must not linger over the spinner, let alone
            // over the next invoice: every run starts unflagged.
            duplicateMatch.value = null

            try {
                _uiState.value = processInvoiceUseCase.processInvoice(file).fold(
                    onSuccess = { invoice ->
                        autoOpenPreviewFor(invoice)
                        runDuplicateCheck(invoice)
                        scope.launch { licenseManager.recordProcessed() }
                        DesktopUiState.Ready(invoice)
                    },
                    onFailure = { cause ->
                        // The pipeline already folds everything into a typed exception with
                        // a Persian message; the trace goes to the terminal so the exact
                        // failure site is in the log, and the diagnostic chain keeps a
                        // class name when that message happens to be blank.
                        cause.printStackTrace()
                        DesktopUiState.Error(diagnosticOf(cause))
                    },
                )
            } catch (e: CancellationException) {
                // A window closed mid-extraction cancels this job; that unwinds and must
                // never be reported to the user as a failure.
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = DesktopUiState.Error(diagnosticOf(e))
            }
        }
    }

    /**
     * Runs [files] through the pipeline as one batch.
     *
     * A second drop replaces the running batch rather than interleaving with it: two
     * concurrent runs would race the progress snapshots and double-report rows. The
     * single-file [processFile] path is untouched — batch and single runs never share
     * state, so one cannot disturb the other.
     */
    fun startBatchProcessing(files: List<File>) {
        if (files.isEmpty()) return
        // Same gating as the single-file path: a batch without an engine only
        // produces per-file failures the user must then dismiss one by one.
        if (ollamaState.value !is OllamaState.Ready) {
            _uiState.value = DesktopUiState.Error(OLLAMA_NOT_READY_MESSAGE)
            return
        }

        batchJob?.cancel()
        batchJob = scope.launch {
            try {
                batchCoordinator.processBatch(files).collect { progress ->
                    _batchProgress.value = progress
                }
            } catch (e: CancellationException) {
                // [cancelBatchProcessing] and [clearBatch] cancel this job deliberately;
                // unwinding is not a failure and must not reach the error screen.
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = DesktopUiState.Error(diagnosticOf(e))
            } finally {
                // Successes count even on a cancelled run: the partial ledger
                // stays exportable, so its extractions count as spent.
                val succeeded = _batchProgress.value?.items?.count { it.invoice != null } ?: 0
                if (succeeded > 0) licenseManager.recordProcessed(succeeded)
            }
        }
    }

    /**
     * Stops the running batch where it stands.
     *
     * The partial snapshot stays visible on purpose: files already finished are still
     * exportable, and the panel still dismisses. Clearing the panel is [clearBatch]'s
     * job, not this one's.
     */
    fun cancelBatchProcessing() {
        batchJob?.cancel()
        batchJob = null
    }

    /**
     * Writes the batch's successful invoices as one consolidated Excel ledger.
     *
     * Only successes export — failed files have no invoice to ledger, and exporting
     * them as blank rows would silently understate the batch. With no successes there
     * is nothing to write, so the call is a no-op (the panel disables the button
     * in that case anyway).
     */
    fun exportBatchExcel(targetFile: File) {
        val invoices = successfulBatchInvoices()
        if (invoices.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            runCatching { batchExportManager.exportBatchExcel(targetFile, invoices) }
                .onSuccess { _exportMessage.value = "${EXPORT_SUCCEEDED_MESSAGE} ${targetFile.name}" }
                .onFailure { cause ->
                    _exportMessage.value = "$EXPORT_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
                }
        }
    }

    /** Writes the batch's successful invoices as one consolidated CSV ledger. */
    fun exportBatchCsv(targetFile: File) {
        val invoices = successfulBatchInvoices()
        if (invoices.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            runCatching { batchExportManager.exportBatchCsv(targetFile, invoices) }
                .onSuccess { _exportMessage.value = "${EXPORT_SUCCEEDED_MESSAGE} ${targetFile.name}" }
                .onFailure { cause ->
                    _exportMessage.value = "$EXPORT_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
                }
        }
    }

    /**
     * Writes the batch's successful invoices as one consolidated Sepidar sheet.
     *
     * Same success-only rule as the ledger exports: failed files have no invoice to
     * import, and the panel disables the action when there is nothing to write.
     */
    fun exportBatchSepidar(targetFile: File) {
        val invoices = successfulBatchInvoices()
        if (invoices.isEmpty()) return

        exportAccounting(targetFile) {
            accountingExportManager.exportBatchAccounting(targetFile, invoices, AccountingTemplate.SEPIDAR)
        }
    }

    /** Writes the batch's successful invoices as one consolidated Holoo sheet. */
    fun exportBatchHoloo(targetFile: File) {
        val invoices = successfulBatchInvoices()
        if (invoices.isEmpty()) return

        exportAccounting(targetFile) {
            accountingExportManager.exportBatchAccounting(targetFile, invoices, AccountingTemplate.HOLOO)
        }
    }

    /**
     * Dismisses the batch panel, stopping the run if it is still going.
     *
     * Persisted successes stay in history — clearing forgets the panel, never the
     * store — so a dismissed batch's invoices remain exactly where a saved invoice
     * would be.
     */
    fun clearBatch() {
        batchJob?.cancel()
        batchJob = null
        _batchProgress.value = null
    }

    /**
     * Downloads the model the extractor needs, from inside the window.
     *
     * The one hard prerequisite of the whole product is a local LLM, and telling a
     * non-technical user to open a terminal and type `ollama pull qwen2.5:3b` is close to
     * telling them the product does not work. This turns that step into a single button: the
     * daemon's streaming pull endpoint is driven directly and every chunk updates
     * [pullProgress], so the panel shows a live progress bar rather than an hourglass.
     *
     * On the terminal event the model is installed and resolvable, so the status probe is
     * re-run immediately and the header flips to ready on its own — the user confirms nothing,
     * the window they were looking at simply becomes usable.
     *
     * Failures are published into [pullProgress] as a [PullProgress.Status] carrying the
     * Persian sentence of the typed exception rather than as a screen-level error: the
     * download panel is where the explanation belongs, and the invoice underneath it is left
     * untouched.
     */
    fun downloadRequiredModel(modelName: String = DEFAULT_MODEL) {
        // A second tap while the first pull is downloading would start a concurrent pull of
        // the same model; the daemon tolerates it, but the bar would jump between the two
        // streams' byte counters.
        if (_pullProgress.value is PullProgress.Downloading) return

        scope.launch {
            runCatching {
                ollamaExtractor.pullModel(modelName).collect { progress ->
                    _pullProgress.value = progress

                    // Completed means the daemon now holds the model and checkStatus will
                    // resolve it, so the header is refreshed and the window leaves the
                    // download state on its own.
                    if (progress is PullProgress.Completed) refreshOllamaStatus()
                }
            }.onFailure { cause ->
                // A window closed mid-download cancels the pull; that is not a failure and
                // must not be reported as one — it unwinds instead.
                if (cause is CancellationException) throw cause

                cause.printStackTrace()

                _pullProgress.value = PullProgress.Status(diagnosticOf(cause))
            }
        }
    }

    /**
     * Rewrites one line item from its edited fields and re-validates the invoice.
     *
     * The line total and the invoice totals are *recomputed* from the parts rather than
     * edited directly, so an edit can never produce an invoice whose arithmetic
     * contradicts itself — the validator would flag exactly what the user just fixed.
     * Invalid numeric input (a partially typed number, a stray `-`) is ignored rather than
     * applied, so a keystroke never zeroes a price or crashes the field.
     *
     * @param index Row position in [Invoice.items]; out-of-range indices are ignored.
     */
    fun updateItem(
        index: Int,
        name: String,
        quantity: Double,
        unitPrice: Double,
        discount: Double,
        tax: Double,
    ) {
        val invoice = (_uiState.value as? DesktopUiState.Ready)?.invoice ?: return
        if (index !in invoice.items.indices) return

        val items = invoice.items.toMutableList()
        items[index] = items[index].copy(
            name = name.trim().ifBlank { items[index].name },
            quantity = quantity,
            unitPrice = unitPrice,
            discount = discount,
            tax = tax,
            totalPrice = quantity * unitPrice - discount + tax,
        )

        emitEdited(invoice.copy(items = items))
    }

    /**
     * Rewrites one line item's warehouse code and teaches the mapping table the pair.
     *
     * The code is not part of the arithmetic, so totals are untouched — but the invoice
     * is still re-emitted through [emitEdited] so the table, the totals and the export
     * row all observe the same record. A blank code clears the field without touching
     * the table: wiping a mapping mid-keystroke (clear-to-retype) would surprise, while
     * explicit deletion lives in the management dialog where it belongs.
     *
     * The mapping write is an upsert keyed on the normalized item name and vendor, so
     * typing a code character by character rewrites one row instead of stacking one row
     * per keystroke. A store failure lands on the transient outcome channel, leaving the
     * on-screen code exactly where the user put it.
     *
     * @param index Row position in [Invoice.items]; out-of-range indices are ignored.
     * @param code The warehouse code as typed; blanks clear the field only.
     */
    fun updateProductCode(index: Int, code: String) {
        val invoice = (_uiState.value as? DesktopUiState.Ready)?.invoice ?: return
        if (index !in invoice.items.indices) return

        val trimmed = code.trim()
        val items = invoice.items.toMutableList()
        val current = items[index]
        items[index] = current.copy(productCode = trimmed.ifBlank { null })

        emitEdited(invoice.copy(items = items))

        if (trimmed.isNotBlank()) {
            val rawName = current.name
            val vendor = invoice.sellerName
            scope.launch {
                runCatching {
                    mappingRepository.saveMapping(rawName, vendor, trimmed)
                }.onFailure { cause ->
                    _exportMessage.value = "$MAPPING_SAVE_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
                }
            }
        }
    }

    /**
     * Records a warehouse-code mapping explicitly, e.g. from the management dialog's
     * add form. Blank names or codes are ignored — the dialog already gates on them.
     */
    fun saveProductMapping(
        rawItemName: String,
        vendorName: String?,
        internalCode: String,
        internalName: String? = null,
    ) {
        if (rawItemName.isBlank() || internalCode.isBlank()) return

        scope.launch {
            runCatching {
                mappingRepository.saveMapping(rawItemName, vendorName, internalCode, internalName)
            }.onFailure { cause ->
                _exportMessage.value = "$MAPPING_SAVE_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
            }
        }
    }

    /**
     * Removes one mapping row.
     *
     * History only: invoices already carrying the code keep it — the row forgets the
     * *rule*, never the past applications of it.
     */
    fun deleteProductMapping(id: String) {
        scope.launch {
            runCatching { mappingRepository.deleteMapping(id) }
                .onFailure { cause ->
                    _exportMessage.value = "$MAPPING_DELETE_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
                }
        }
    }

    /**
     * Rewrites the invoice header fields and re-validates. Empty strings become `null` so
     * a cleared field reads as "not extracted" to the validator rather than as a
     * blank-but-present value. National IDs flow through the same rule, so clearing a
     * mistyped identifier removes its audit warning instead of validating an empty one.
     */
    fun updateMetadata(
        seller: String,
        invoiceNumber: String,
        date: String,
        sellerNationalId: String,
        buyerNationalId: String,
        clientName: String,
        projectName: String,
    ) {
        val invoice = (_uiState.value as? DesktopUiState.Ready)?.invoice ?: return

        emitEdited(
            invoice.copy(
                sellerName = seller.trim().ifBlank { null },
                invoiceNumber = invoiceNumber.trim().ifBlank { null },
                date = date.trim().ifBlank { null },
                sellerNationalId = sellerNationalId.trim().ifBlank { null },
                buyerNationalId = buyerNationalId.trim().ifBlank { null },
                clientName = clientName.trim().ifBlank { null },
                projectName = projectName.trim().ifBlank { null },
            ),
        )
    }

    /**
     * Rewrites the invoice currency and, for foreign money, its day rate into
     * Toman — then re-validates like every other edit, so the converted pill,
     * ledgers and analytics always observe the same record.
     *
     * The rate is kept only for USD/EUR/USDT: domestic money converts by
     * contract, never by a typed number. A non-positive or non-finite rate is
     * stored as `null`, which reads as `1.0` downstream rather than as a
     * corrupting zero or `NaN`.
     */
    fun updateCurrency(currency: CurrencyType, exchangeRate: Double?) {
        val invoice = (_uiState.value as? DesktopUiState.Ready)?.invoice ?: return

        emitEdited(
            invoice.copy(
                currency = currency,
                exchangeRate = if (currency.isForeignCurrency) {
                    exchangeRate?.takeIf { it.isFinite() && it > 0.0 }
                } else {
                    null
                },
            ),
        )
    }

    /**
     * Moves the on-screen invoice through the collection cycle. Status never
     * feeds the validator — money changing hands says nothing about the
     * document — so this re-emits without re-validating, keeping the verdict
     * the pipeline produced.
     */
    fun updatePaymentStatus(status: PaymentStatus) {
        val invoice = (_uiState.value as? DesktopUiState.Ready)?.invoice ?: return
        emitEdited(invoice.copy(paymentStatus = status))
    }

    /**
     * Sets the payment deadline. A blank field clears it back to `null`
     * ("no deadline"), mirroring the header-field contract in [updateMetadata].
     */
    fun updateDueDate(dueDate: String) {
        val invoice = (_uiState.value as? DesktopUiState.Ready)?.invoice ?: return
        emitEdited(invoice.copy(dueDate = dueDate.trim().ifBlank { null }))
    }

    /**
     * Persists an already-archived [invoice] after an out-of-editor change —
     * the follow-up dialog's "mark as paid". The store upserts by id and the
     * [savedInvoices] flow re-emits, so history, analytics and statements all
     * observe the paid record without a reload.
     */
    fun updateStoredInvoice(invoice: Invoice) {
        scope.launch { invoiceRepository.saveInvoice(invoice) }
    }

    /**
     * The latest bank reconciliation answer, or `null` when none is open.
     * Set by [reconcileFile], cleared by [applyMatchedPayments] and
     * [clearReconciliation] — the dialog reads it and never computes it.
     */
    val reconciliationResult: MutableStateFlow<ReconciliationResult?> =
        MutableStateFlow(null)

    /**
     * Whether the last statement run failed to parse. Read beside
     * [reconciliationResult]: a `null` result alone cannot tell "no file yet"
     * from "unreadable file", and the dialog renders a different state for
     * each.
     */
    val reconciliationFailed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Parses a bank statement export and links its deposits to the archive's
     * open receivables, off the render thread. The outcome lands in
     * [reconciliationResult] for the dialog, and a one-line summary rides the
     * transient channel like every other file action.
     */
    fun reconcileFile(file: File) {
        scope.launch(Dispatchers.IO) {
            when (val outcome = parseBankStatement(file)) {
                is StatementParseOutcome.Failed -> {
                    reconciliationResult.value = null
                    reconciliationFailed.value = true
                    _exportMessage.value = when (outcome.failure) {
                        StatementParseFailure.UnsupportedFormat -> RECONCILE_FORMAT_MESSAGE
                        StatementParseFailure.NoTransactionsFound -> RECONCILE_EMPTY_MESSAGE
                    }
                }
                is StatementParseOutcome.Parsed -> {
                    val result = matchStatement(outcome.transactions, savedInvoices.value)
                    reconciliationResult.value = result
                    reconciliationFailed.value = false
                    _exportMessage.value = RECONCILE_FOUND_MESSAGE
                        .replace("{n}", result.matched.size.toPersianDigits())
                        .replace(
                            "{amount}",
                            AmountFormatter.formatToman(result.totalMatchedAmount.toDouble()),
                        )
                }
            }
        }
    }

    /**
     * Settles every matched invoice in one batch: each is re-saved as
     * [PaymentStatus.PAID], the store re-emits, and history, analytics and
     * statements observe the paid records. The result is cleared so the dialog
     * closes onto a settled archive, and the count rides the transient channel.
     */
    fun applyMatchedPayments(matches: List<ReconciliationMatch>) {
        if (matches.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            matches.forEach { match ->
                invoiceRepository.saveInvoice(
                    match.invoice.copy(paymentStatus = PaymentStatus.PAID),
                )
            }
            reconciliationResult.value = null
            reconciliationFailed.value = false
            _exportMessage.value = RECONCILE_APPLIED_MESSAGE
                .replace("{n}", matches.size.toPersianDigits())
        }
    }

    /** Closes the reconciliation answer without settling anything. */
    fun clearReconciliation() {
        reconciliationResult.value = null
        reconciliationFailed.value = false
    }
    /**
     * Writes the on-screen invoice as a printable formal A4 tax sheet into
     * [targetFile].
     *
     * Synchronous by design, unlike the streaming exporters: the caller runs
     * it off the render thread and opens the file the moment it lands, so the
     * browser never races a half-written page.
     *
     * @return the finished file, or a failure when there is no invoice on
     *   screen or the file cannot be written.
     */
    fun generateFormalInvoice(targetFile: File): Result<File> {
        val invoice = currentInvoice()
            ?: return Result.failure(IllegalStateException(NO_INVOICE_MESSAGE))
        return formalInvoiceGenerator.generateFormalInvoiceHtml(
            targetFile,
            invoice,
        )
    }

    /**
     * Writes the on-screen invoice as a printable commercial sheet into
     * [targetFile] — the global workspace's answer to [generateFormalInvoice],
     * routed by the caller on the window language.
     */
    fun generateCommercialInvoice(targetFile: File): Result<File> {
        val invoice = currentInvoice()
            ?: return Result.failure(IllegalStateException(NO_INVOICE_MESSAGE))
        return commercialInvoiceGenerator.generateCommercialInvoiceHtml(
            targetFile,
            invoice,
        )
    }

    /**
     * Publishes one transient outcome line, e.g. a localized success sentence
     * after a caller-owned file action, without touching the invoice on screen.
     */
    fun announceExportMessage(message: String) {
        _exportMessage.value = message
    }

    /**
     * Publishes one transient failure line for a caller-owned file action,
     * naming the cause instead of replacing the screen with an error state.
     */
    fun reportExportFailure(cause: Throwable) {
        _exportMessage.value =
            "$EXPORT_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
    }

    /**
     * Writes the on-screen invoice as a UTF-8-BOM CSV into [targetFile].
     *
     * Runs off the UI thread: the generator streams row by row, but the file write itself
     * must never stall the window. The outcome lands in [exportMessage], leaving the
     * invoice untouched regardless of success or failure.
     */
    fun exportCsv(targetFile: File) {
        val invoice = currentInvoice() ?: return
        scope.launch(Dispatchers.IO) { export(targetFile) { stream -> InvoiceCsvExporter.export(stream, invoice) } }
    }

    /** Writes the on-screen invoice as an Excel XML Spreadsheet into [targetFile]. */
    fun exportExcel(targetFile: File) {
        val invoice = currentInvoice() ?: return
        scope.launch(Dispatchers.IO) { export(targetFile) { stream -> InvoiceXmlSpreadsheetExporter.export(stream, invoice) } }
    }

    /**
     * Writes a stored [invoice] as an Excel XML Spreadsheet into [targetFile].
     *
     * History rows export their own record rather than whatever is on screen: routing
     * them through [exportExcel] would silently export the wrong invoice whenever the
     * window shows a different one. The outcome reporting is shared with the other
     * exporters, so a failed save still names its file instead of replacing the screen.
     */
    fun exportSavedInvoiceExcel(invoice: Invoice, targetFile: File) {
        scope.launch(Dispatchers.IO) { export(targetFile) { stream -> InvoiceXmlSpreadsheetExporter.export(stream, invoice) } }
    }

    /** Writes the on-screen invoice as a Sepidar-importable sheet into [targetFile]. */
    fun exportCurrentSepidar(targetFile: File) {
        val invoice = currentInvoice() ?: return
        exportAccounting(targetFile) { accountingExportManager.exportSepidar(targetFile, invoice) }
    }

    /** Writes the on-screen invoice as a Holoo-importable sheet into [targetFile]. */
    fun exportCurrentHoloo(targetFile: File) {
        val invoice = currentInvoice() ?: return
        exportAccounting(targetFile) { accountingExportManager.exportHoloo(targetFile, invoice) }
    }

    /**
     * Writes a stored [invoice] as a Sepidar-importable sheet into [targetFile].
     *
     * History rows export their own record rather than whatever is on screen, for the
     * same reason [exportSavedInvoiceExcel] exists: routing them through the
     * current-invoice action would silently export the wrong invoice.
     */
    fun exportSavedInvoiceSepidar(invoice: Invoice, targetFile: File) {
        exportAccounting(targetFile) { accountingExportManager.exportSepidar(targetFile, invoice) }
    }

    /** Writes a stored [invoice] as a Holoo-importable sheet into [targetFile]. */
    fun exportSavedInvoiceHoloo(invoice: Invoice, targetFile: File) {
        exportAccounting(targetFile) { accountingExportManager.exportHoloo(targetFile, invoice) }
    }

    /**
     * Writes [invoice] — or the on-screen record when `null` — as a
     * QuickBooks-importable CSV into [targetFile].
     */
    fun exportQuickBooks(targetFile: File, invoice: Invoice? = null) {
        val record = invoice ?: currentInvoice() ?: return
        exportAccounting(targetFile) {
            globalAccountingExportManager.exportQuickBooks(targetFile, record)
        }
    }

    /**
     * Writes [invoice] — or the on-screen record when `null` — as a
     * Xero-importable CSV into [targetFile].
     */
    fun exportXero(targetFile: File, invoice: Invoice? = null) {
        val record = invoice ?: currentInvoice() ?: return
        exportAccounting(targetFile) {
            globalAccountingExportManager.exportXero(targetFile, record)
        }
    }

    /** Writes the batch's successful invoices as one consolidated QuickBooks file. */
    fun exportBatchQuickBooks(targetFile: File) {
        val invoices = successfulBatchInvoices()
        if (invoices.isEmpty()) return

        exportAccounting(targetFile) {
            globalAccountingExportManager.exportBatchGlobalAccounting(
                targetFile,
                invoices,
                GlobalAccountingTemplate.QUICKBOOKS,
            )
        }
    }

    /** Writes the batch's successful invoices as one consolidated Xero file. */
    fun exportBatchXero(targetFile: File) {
        val invoices = successfulBatchInvoices()
        if (invoices.isEmpty()) return

        exportAccounting(targetFile) {
            globalAccountingExportManager.exportBatchGlobalAccounting(
                targetFile,
                invoices,
                GlobalAccountingTemplate.XERO,
            )
        }
    }

    /**
     * Writes a stored [invoice] as a QuickBooks-importable CSV into
     * [targetFile].
     *
     * History rows export their own record rather than whatever is on screen:
     * routing them through [exportQuickBooks] would silently export the wrong
     * invoice whenever the window shows a different one.
     */
    fun exportSavedInvoiceQuickBooks(invoice: Invoice, targetFile: File) {
        exportAccounting(targetFile) {
            globalAccountingExportManager.exportQuickBooks(targetFile, invoice)
        }
    }

    /** Writes a stored [invoice] as a Xero-importable CSV into [targetFile]. */
    fun exportSavedInvoiceXero(invoice: Invoice, targetFile: File) {
        exportAccounting(targetFile) {
            globalAccountingExportManager.exportXero(targetFile, invoice)
        }
    }

    /**
     * Writes a client statement (project costs plus agency markup) for [invoices]
     * under [config] into [targetFile].
     *
     * An empty invoice list is a no-op — there is nothing to state — and the
     * outcome rides the transient channel like every other export, leaving
     * whatever is on screen untouched either way.
     */
    fun exportClientStatement(
        targetFile: File,
        config: ClientStatementConfig,
        invoices: List<Invoice>,
    ) {
        if (invoices.isEmpty()) return
        exportAccounting(targetFile) {
            clientStatementExportManager.exportClientStatement(targetFile, config, invoices)
        }
    }

    /**
     * Writes the dashboard's current analytics snapshot — honoring the period
     * filter — as an executive BI workbook into [targetFile].
     *
     * Synchronous like [generateFormalInvoice], for the same reason: the caller
     * runs it off the render thread and announces the localized outcome itself,
     * so success and failure toasts share the window language instead of the
     * ViewModel guessing it. The snapshot is read once, up front: the archive
     * cannot change mid-write because the manager receives values, never the
     * flow.
     *
     * @return the finished file, or a failure to report.
     */
    fun exportCurrentAnalytics(targetFile: File, isEnglish: Boolean): Result<File> {
        val snapshot = analytics.value
        val year = selectedAnalyticsYear.value
        return analyticsExportManager.exportAnalyticsReport(
            targetFile = targetFile,
            analytics = snapshot,
            selectedYear = year,
            isEnglish = isEnglish,
        )
    }

    /**
     * Writes a petty-cash settlement sheet for [invoices] against their advance
     * into [targetFile].
     *
     * An empty invoice list still exports: an untouched advance is a valid
     * full-surplus settlement. The outcome rides the transient channel like
     * every other export, leaving whatever is on screen untouched either way.
     */
    fun exportPettyCashSettlement(
        targetFile: File,
        clientName: String,
        projectName: String,
        settlement: PettyCashSettlement,
        invoices: List<Invoice>,
    ) {
        exportAccounting(targetFile) {
            pettyCashExportManager.exportPettyCashSettlement(
                targetFile,
                clientName,
                projectName,
                settlement,
                invoices,
            )
        }
    }

    /**
     * Packs the whole merchant state into [targetZipFile].
     *
     * Suspending rather than fire-and-forget, so the backup dialog can await
     * the outcome and report it inline instead of through the transient
     * channel. Safe to call from any dispatcher — the manager confines itself
     * to IO.
     */
    suspend fun performBackup(targetZipFile: File): Result<BackupResult> =
        backupManager.createBackup(targetZipFile)

    /**
     * Restores the merchant state from [sourceZipFile], reloading both stores
     * so the window refreshes without a restart.
     *
     * Same awaiting contract as [performBackup]: the dialog reports the
     * outcome inline, and a corrupt archive fails here before touching
     * anything the window shows.
     */
    suspend fun performRestore(sourceZipFile: File): Result<RestoreResult> =
        backupManager.restoreBackup(sourceZipFile)

    /**
     * Persists the invoice on screen into the local store.
     *
     * The store upserts by id, so the first save inserts and every later save rewrites the
     * same record — pressing the action again after an edit updates the one entry instead
     * of stacking history rows. On success the on-screen invoice is marked clean and saved,
     * mirroring the export rule; on failure it stays dirty *and* unsaved, telling the truth
     * about what is actually persisted, and the failure rides the transient outcome channel
     * rather than replacing the invoice with an error screen.
     */
    fun saveCurrentInvoice() {
        val invoice = currentInvoice() ?: return

        scope.launch {
            invoiceRepository.saveInvoice(invoice).fold(
                onSuccess = {
                    (_uiState.value as? DesktopUiState.Ready)?.let { ready ->
                        _uiState.value = ready.copy(isDirty = false, isSaved = true)
                    }
                },
                onFailure = { cause ->
                    _exportMessage.value = "$SAVE_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
                },
            )
        }
    }

    /**
     * Removes [id] from the local store.
     *
     * History only: the invoice on screen is left exactly where it is, because the record
     * under review may be an unsaved working copy of the row being deleted.
     */
    fun deleteSavedInvoice(id: String) {
        scope.launch { invoiceRepository.deleteInvoice(id) }
    }

    /**
     * Puts a previously saved [invoice] back on screen.
     *
     * It lands saved and clean: it came out of the store, so there is nothing to persist,
     * and the verdict stored with it is the one the validator produced when it was
     * extracted — no re-validation needed to show it faithfully. The workspace always
     * returns to the scanner tab, so the editor is visible no matter which tab the
     * archive was opened from.
     *
     * A missing source file degrades gracefully: the preview pane stays hidden and
     * the editor still opens, because the stored record — not the original file —
     * is what is being reviewed.
     */
    fun loadSavedInvoice(invoice: Invoice) {
        _uiState.value = DesktopUiState.Ready(invoice, isDirty = false, isSaved = true)
        currentNavTab.value = NavTab.SCANNER
        val source = invoice.sourceFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
        _currentSourceFile.value = source
        if (source != null) {
            scope.launch { previewManager.loadDocument(source) }
        } else {
            previewManager.close()
        }
        autoOpenPreviewFor(invoice)
        // Opening an archived record is a deliberate act, not a suspected double
        // entry: any warning belongs to whatever was on screen before, so it goes.
        // (The detector would skip the opened record itself anyway via self-ignorance.)
        duplicateMatch.value = null
    }

    /** Dismisses the duplicate-invoice warning; the archive is untouched. */
    fun dismissDuplicateWarning() {
        duplicateMatch.value = null
    }

    /** Returns the window to the drop zone, discarding whatever invoice was on screen. */
    fun reset() {
        _uiState.value = DesktopUiState.Idle
        _currentSourceFile.value = null
        previewManager.close()
        duplicateMatch.value = null
    }

    /** Clears the last export outcome so the message box does not linger after dismissal. */
    fun clearExportMessage() {
        _exportMessage.value = null
    }

    /** Cancels every coroutine the window started. Call exactly once, on window close. */
    fun dispose() {
        // The open PDF holds a file handle and native raster memory: release it before
        // the scope goes away, while this thread can still block on the close.
        previewManager.close()
        // The watcher is app-scoped but window-driven: its native watch service
        // must not outlive the only window that observes it.
        watcherService.shutdown()
        scope.cancel()
    }

    /**
     * Re-validates [invoice] and emits it as [DesktopUiState.Ready], marking it dirty.
     *
     * Centralizing this is what keeps the invariant: there is no path to a visible invoice
     * that skips the validator, so the banner, the suspicious-row outlines and the totals
     * are always consistent with the table. The national-ID audit rides along, so a
     * hand-corrected identifier re-judges on every keystroke exactly like a fresh
     * extraction does.
     */
    private fun emitEdited(invoice: Invoice) {
        _uiState.value = DesktopUiState.Ready(
            invoice = invoiceValidator.validate(invoice).withNationalIdAudit().withGlobalTaxIdAudit(),
            isDirty = true,
        )
    }

    private fun currentInvoice(): Invoice? = (_uiState.value as? DesktopUiState.Ready)?.invoice

    /**
     * Opens the preview pane for an invoice that needs review.
     *
     * Any non-[ValidationStatus.Valid] verdict — warnings and hard failures alike —
     * means the reviewer must compare the table against the page it came from, so the
     * pane opens itself instead of waiting to be discovered. A clean invoice leaves the
     * user's toggle choice alone.
     */
    private fun autoOpenPreviewFor(invoice: Invoice) {
        if (invoice.validationStatus != ValidationStatus.Valid) {
            isPreviewPaneVisible.value = true
        }
    }

    /**
     * Cross-references [invoice] against the persistent archive for accidental
     * double-entry, publishing the first hit — or silence — into [duplicateMatch].
     *
     * Runs synchronously on the caller's coroutine: the detector is pure comparison
     * over an in-memory list, with no I/O to dispatch. Called only for freshly
     * processed invoices; opening an archived record dismisses instead of checking
     * (see [loadSavedInvoice]).
     */
    private fun runDuplicateCheck(invoice: Invoice) {
        duplicateMatch.value = duplicateDetector.detectDuplicate(invoice, savedInvoices.value)
    }

    /**
     * Builds the one sentence a [DesktopUiState.Error] shows, from the failure itself
     * rather than from a generic placeholder.
     *
     * Order matters: the pipeline's typed exceptions already carry a Persian sentence the
     * user can act on, so [Throwable.getLocalizedMessage] / [Throwable.getMessage] come
     * first and only a genuinely empty message falls through to the exception's class
     * name plus its cause — which still names what broke, instead of the old generic
     * "خطای ناشناخته" that told the user nothing and support nothing either.
     */
    private fun diagnosticOf(e: Throwable): String =
        e.localizedMessage?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: "${e.javaClass.simpleName}: ${e.cause?.message ?: NO_DIAGNOSTIC_MESSAGE}"

    /**
     * The invoices a finished (or partial) batch actually produced, in batch order.
     *
     * Read off the progress snapshot rather than the store on purpose: it is exactly
     * the set the panel showed, so the ledger can never contain a row the user did
     * not see reported — or miss one they did.
     */
    private fun successfulBatchInvoices(): List<Invoice> =
        _batchProgress.value?.items?.mapNotNull { it.invoice }.orEmpty()

    /**
     * Runs one accounting-manager call off the UI thread with the standard outcome
     * reporting: success names the file, failure names it too, and the invoice on
     * screen is never touched either way.
     *
     * Unlike [export], there is no stream to own here — the accounting manager takes
     * the target file directly and closes everything it opens — so this helper only
     * dispatches and reports.
     */
    private fun exportAccounting(targetFile: File, write: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { write() }
                .onSuccess { _exportMessage.value = "${EXPORT_SUCCEEDED_MESSAGE} ${targetFile.name}" }
                .onFailure { cause ->
                    _exportMessage.value = "$EXPORT_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
                }
        }
    }

    /**
     * Owns the output stream lifecycle so a generator never leaks a handle: `use` closes
     * the stream on every path, including a mid-write failure that leaves a partial file.
     */
    private fun export(targetFile: File, write: (java.io.OutputStream) -> Unit) {
        val outcome = runCatching {
            targetFile.outputStream().use { stream ->
                write(stream)
                stream.flush()
            }
        }

        _exportMessage.value = outcome.fold(
            onSuccess = { "${EXPORT_SUCCEEDED_MESSAGE} ${targetFile.name}" },
            onFailure = { cause ->
                // Anything from a full disk to a permissions problem; the message names the
                // file so the user can find it, and the cause is logged by the caller.
                "$EXPORT_FAILED_MESSAGE ${cause.message ?: cause.javaClass.simpleName}"
            },
        )

        if (outcome.isSuccess) {
            // The saved file now matches what is on screen.
            (_uiState.value as? DesktopUiState.Ready)?.let { ready ->
                _uiState.value = ready.copy(isDirty = false)
            }
        }
    }

    private suspend fun refreshOllamaStatus() {
        // checkStatus already folds transport failures into OllamaNotRunning; a throw here
        // would mean the probe itself is broken, and the header must not flicker on it.
        runCatching { ollamaExtractor.checkStatus() }
            .onSuccess { _ollamaStatus.value = it }
    }

    private companion object {
        // The tag the download button pulls. Matches the extractor's own default so the model
        // the window installs is the one the pipeline resolves once it lands.
        const val DEFAULT_MODEL = "qwen2.5:3b"

        const val OLLAMA_STATUS_REFRESH_INTERVAL_MS = 5_000L

        const val NOT_A_SUPPORTED_FILE_MESSAGE =
            "فقط فایل PDF و تصویر (PNG یا JPG) پشتیبانی می‌شود."

        const val EXPORT_SUCCEEDED_MESSAGE = "خروجی با موفقیت ذخیره شد:"

        const val EXPORT_FAILED_MESSAGE = "خطا در ذخیره خروجی:"

        const val SAVE_FAILED_MESSAGE = "خطا در ذخیره فاکتور:"

        const val MAPPING_SAVE_FAILED_MESSAGE = "خطا در ذخیره نگاشت کالا:"

        const val MAPPING_DELETE_FAILED_MESSAGE = "خطا در حذف نگاشت کالا:"

        const val OLLAMA_NOT_READY_MESSAGE =
            "سرویس هوش مصنوعی هنوز آماده نیست. لطفاً تا پایان آماده‌سازی خودکار صبر کنید."

        const val NO_DIAGNOSTIC_MESSAGE = "جزئیات خطا در دسترس نیست"

        const val NO_INVOICE_MESSAGE = "فاکتوری روی صفحه نیست."

        const val RECONCILE_FORMAT_MESSAGE =
            "فرمت فایل پشتیبانی نمی‌شود. صورتحساب را با فرمت CSV ذخیره کنید."

        const val RECONCILE_EMPTY_MESSAGE =
            "در این فایل هیچ واریزی معتبری پیدا نشد."

        const val RECONCILE_FOUND_MESSAGE =
            "تطبیق انجام شد: {n} فاکتور ({amount} تومان) با واریزی‌ها لینک شد."

        const val RECONCILE_APPLIED_MESSAGE =
            "تسویه خودکار انجام شد: {n} فاکتور پرداخت‌شده ثبت شد."
    }
}
