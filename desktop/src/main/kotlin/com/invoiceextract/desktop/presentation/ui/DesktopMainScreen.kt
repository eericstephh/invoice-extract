package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.data.ai.OllamaStatus
import com.invoiceextract.desktop.data.ai.PullProgress
import com.invoiceextract.desktop.data.automation.WatcherEvent
import com.invoiceextract.desktop.data.batch.DesktopBatchProgress
import com.invoiceextract.desktop.data.document.InvoiceFileFormats
import com.invoiceextract.desktop.data.engine.OllamaState
import com.invoiceextract.desktop.domain.validation.DuplicateMatch
import com.invoiceextract.desktop.domain.validation.DuplicateReason
import com.invoiceextract.desktop.presentation.DesktopUiState
import com.invoiceextract.desktop.presentation.DesktopViewModel
import com.invoiceextract.desktop.presentation.NavTab
import com.invoiceextract.desktop.presentation.ui.hotkeys.HotkeyAction
import com.invoiceextract.desktop.presentation.ui.hotkeys.KeyboardShortcutsDialog
import com.invoiceextract.desktop.presentation.ui.hotkeys.matchHotkey
import com.invoiceextract.desktop.presentation.ui.automation.FolderWatcherDialog
import com.invoiceextract.desktop.presentation.ui.donation.CryptoDonationDialog
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayCard
import com.invoiceextract.desktop.presentation.ui.theme.ClayDockButtons
import com.invoiceextract.desktop.presentation.ui.theme.ClayGreetingHeader
import com.invoiceextract.desktop.presentation.ui.theme.ClayDockButton
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.LocalClayColors
import com.invoiceextract.desktop.presentation.ui.theme.ambientBrush
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.desktop.presentation.ui.theme.clayColors
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.defaultCurrencyFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Frame
import java.io.File

/**
 * The suggested formal-invoice filename: `Formal_Invoice_[number].html`, with
 * filesystem-hostile characters replaced so a typed invoice number can never
 * produce an unsavable name.
 */
private fun formalInvoiceName(invoiceNumber: String?): String {
    val safe = invoiceNumber
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.map { char -> if (char in FILENAME_HOSTILE_CHARS) '_' else char }
        ?.joinToString("")
        .orEmpty()
        .ifBlank { "invoice" }
    return "Formal_Invoice_${safe}.html"
}

private fun commercialInvoiceName(invoiceNumber: String?): String {
    val safe = invoiceNumber
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.map { char -> if (char in FILENAME_HOSTILE_CHARS) '_' else char }
        ?.joinToString("")
        .orEmpty()
        .ifBlank { "invoice" }
    return "Commercial_Invoice_${safe}.html"
}

/**
 * Opens [file] in the default browser, print-ready. Best-effort by design: a
 * headless host or a browser-less kiosk degrades to the saved file itself,
 * which the success line already names — opening must never fail the export.
 */
private fun openInBrowser(file: File) {
    runCatching {
        if (Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
        ) {
            Desktop.getDesktop().open(file)
        }
    }
}

private const val FILENAME_HOSTILE_CHARS = "/\\:*?\"<>|"

/**
 * Claymorphic desktop screen: fluid greeting header on top, clay workspace
 * in the middle, 3D floating bottom dock.
 *
 * All ViewModel logic, split-view preview and duplicate/validation detection
 * are retained 1:1 — only the chrome around them is re-skinned.
 *
 * Bilingual: [isEnglish] flips the whole tree between RTL Persian and LTR
 * English via [LocalLayoutDirection], and every chrome string reads off the
 * matching [AppStrings] dictionary.
 */
@Composable
fun DesktopMainScreen(
    viewModel: DesktopViewModel,
    parentFrame: Frame,
    isDragOver: Boolean = false,
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {},
    isEnglish: Boolean = false,
    onToggleLanguage: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val ollamaStatus by viewModel.ollamaStatus.collectAsState()
    val ollamaState by viewModel.ollamaState.collectAsState()
    val pullProgress by viewModel.pullProgress.collectAsState()
    val savedInvoices by viewModel.savedInvoices.collectAsState()
    val reconciliation by viewModel.reconciliationResult.collectAsState()
    val reconciliationFailed by viewModel.reconciliationFailed.collectAsState()
    val productMappings by viewModel.productMappings.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val analytics by viewModel.analytics.collectAsState()
    val availableYears by viewModel.availableAnalyticsYears.collectAsState()
    val selectedYear by viewModel.selectedAnalyticsYear.collectAsState()
    val navTab by viewModel.currentNavTab.collectAsState()
    val watcherConfig by viewModel.watcherConfig.collectAsState()
    val watcherEvent by viewModel.latestWatcherEvent.collectAsState()
    val scope = rememberCoroutineScope()
    val isOllamaReady = ollamaState is OllamaState.Ready

    val clay = remember(isDarkMode) { clayColors(isDarkMode) }
    val strings = remember(isEnglish) {
        appStrings(if (isEnglish) AppLanguage.EN else AppLanguage.FA)
    }
    val layoutDir = if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl
    val greeting = remember(isEnglish) { strings.greetingText() }
    val dateLabel = remember(isEnglish) { strings.dateText() }

    var showHistoryDialog by remember { mutableStateOf(false) }
    var showMappingsDialog by remember { mutableStateOf(false) }
    var showShortcutsDialog by remember { mutableStateOf(false) }
    var showWatcherDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }

    val batch = batchProgress
    val activeTab = if (batch != null) NavTab.BATCH else navTab

    // The universal way home: discard the working invoice, dismiss any batch,
    // and return the workspace to the scanner drop-zone.
    val goHome: () -> Unit = {
        viewModel.reset()
        viewModel.clearBatch()
        viewModel.selectTab(NavTab.SCANNER)
    }

    val launchSinglePicker: () -> Unit = {
        if (isOllamaReady) {
            scope.launch(Dispatchers.IO) {
                openInvoiceFileDialog(parentFrame)?.let(viewModel::processFile)
            }
        }
    }
    val launchBatchPicker: () -> Unit = {
        if (isOllamaReady) {
            scope.launch(Dispatchers.IO) {
                val files = openMultipleFilesDialog(
                    parentFrame,
                    InvoiceFileFormats.ALL.toList(),
                ).filter { file ->
                    file.isFile && InvoiceFileFormats.isSupported(file.extension)
                }
                if (files.isNotEmpty()) viewModel.startBatchProcessing(files)
            }
        }
    }

    // Hoisted beside the pickers so both the editor buttons and the keyboard
    // interceptor trigger the same flows: each reads the on-screen invoice at
    // tap time rather than capturing a stale one.
    val triggerExcelExport: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            saveFileDialog(parentFrame, DEFAULT_EXCEL_NAME, EXCEL_EXTENSION)
                ?.let(viewModel::exportExcel)
        }
    }
    val triggerPrintFormal: () -> Unit = {
        val number = (viewModel.uiState.value as? DesktopUiState.Ready)?.invoice?.invoiceNumber
        scope.launch(Dispatchers.IO) {
            // The print template follows the window language: the global
            // workspace gets the commercial sheet, Persian keeps Section 169.
            if (isEnglish) {
                saveFileDialog(parentFrame, commercialInvoiceName(number), HTML_EXTENSION)
                    ?.let { target ->
                        viewModel.generateCommercialInvoice(target)
                            .onSuccess {
                                openInBrowser(it)
                                viewModel.announceExportMessage(strings.commercialInvoiceGenerated)
                            }
                            .onFailure(viewModel::reportExportFailure)
                    }
            } else {
                saveFileDialog(parentFrame, formalInvoiceName(number), HTML_EXTENSION)
                    ?.let { target ->
                        viewModel.generateFormalInvoice(target)
                            .onSuccess {
                                openInBrowser(it)
                                viewModel.announceExportMessage(strings.formalInvoiceGenerated)
                            }
                            .onFailure(viewModel::reportExportFailure)
                    }
            }
        }
    }

    // Global hotkeys, previewed at the root so they fire from anywhere in the
    // window that does not consume them first. State-gated actions (save,
    // print, export) run only with an invoice open; everything else is
    // unconditional. Returns true for consumed presses.
    val handleHotkey: (KeyEvent) -> Boolean = { event ->
        when (
            matchHotkey(
                ctrlPressed = event.isCtrlPressed,
                shiftPressed = event.isShiftPressed,
                key = event.key,
                keyDown = event.type == KeyEventType.KeyDown,
            )
        ) {
            null -> false
            HotkeyAction.OPEN_SINGLE -> {
                launchSinglePicker()
                true
            }
            HotkeyAction.OPEN_BATCH -> {
                launchBatchPicker()
                true
            }
            HotkeyAction.SAVE -> {
                if (uiState is DesktopUiState.Ready) {
                    viewModel.saveCurrentInvoice()
                    true
                } else {
                    false
                }
            }
            HotkeyAction.PRINT -> {
                if (uiState is DesktopUiState.Ready) {
                    triggerPrintFormal()
                    true
                } else {
                    false
                }
            }
            HotkeyAction.EXPORT_EXCEL -> {
                if (uiState is DesktopUiState.Ready) {
                    triggerExcelExport()
                    true
                } else {
                    false
                }
            }
            HotkeyAction.HISTORY -> {
                showHistoryDialog = !showHistoryDialog
                true
            }
            HotkeyAction.THEME -> {
                onToggleTheme()
                true
            }
            HotkeyAction.LANGUAGE -> {
                onToggleLanguage()
                true
            }
            HotkeyAction.HELP -> {
                showShortcutsDialog = !showShortcutsDialog
                true
            }
            HotkeyAction.BACK -> {
                if (showShortcutsDialog || showHistoryDialog || showMappingsDialog) {
                    showShortcutsDialog = false
                    showHistoryDialog = false
                    showMappingsDialog = false
                    true
                } else if (uiState is DesktopUiState.Ready || navTab != NavTab.SCANNER) {
                    goHome()
                    true
                } else {
                    false
                }
            }
        }
    }

    // The watcher toast dismisses itself after a few seconds; a fresh arrival
    // restarts the timer by keying on the event itself.
    LaunchedEffect(watcherEvent) {
        if (watcherEvent != null) {
            delay(WATCHER_TOAST_MS)
            viewModel.dismissWatcherToast()
        }
    }

    // The whole window — chrome, workspace and dialogs — follows the language
    // direction, so English mirrors the layout instead of sitting inside RTL.
    // The clay palette rides along, so every nested card, badge and field
    // reads ClayTheme.colors instead of hardcoded literals.
    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDir,
        LocalClayColors provides clay,
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(clay.ambientBrush())
            .onPreviewKeyEvent(handleHotkey),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ClayGreetingHeader(
                clay = clay,
                greeting = greeting,
                jalaliDate = dateLabel,
                ollamaStatus = ollamaStatus,
                isDarkMode = isDarkMode,
                onToggleTheme = onToggleTheme,
                strings = strings,
                isEnglish = isEnglish,
                onToggleLanguage = onToggleLanguage,
                showBack = uiState is DesktopUiState.Ready || activeTab != NavTab.SCANNER,
                onBack = goHome,
                onShowShortcuts = { showShortcutsDialog = true },
                watcherActive = watcherConfig.isEnabled,
                onWatcherClick = { showWatcherDialog = true },
                onDonateClick = { showDonateDialog = true },
            )

            // Hot-folder toasts float under the header: success in emerald,
            // failure in error red with the pipeline's own reason. Processing
            // lines stay silent — the outcome toast that follows names the file.
            when (val event = watcherEvent) {
                is WatcherEvent.Success -> WatcherToastBanner(
                    text = strings.autoProcessedSuccess(event.fileName, event.sellerName),
                    isError = false,
                    onDismiss = viewModel::dismissWatcherToast,
                )
                is WatcherEvent.Failure -> WatcherToastBanner(
                    text = event.reason,
                    isError = true,
                    onDismiss = viewModel::dismissWatcherToast,
                )
                else -> Unit
            }

            DashboardMetricRow(
                strings = strings,
                savedCount = savedInvoices.size,
                ollamaState = ollamaState,
                batch = batch,
            )

            if (ollamaState !is OllamaState.Ready) {
                OllamaProvisioningBanner(
                    state = ollamaState,
                    onRetry = viewModel::retryOllama,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ClayCard(
                clay = clay,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (batch != null) {
                        DesktopBatchPanel(
                            progress = batch,
                            onCancel = viewModel::cancelBatchProcessing,
                            onExportExcel = {
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, BATCH_EXCEL_NAME, EXCEL_EXTENSION)
                                        ?.let(viewModel::exportBatchExcel)
                                }
                            },
                            onExportCsv = {
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, BATCH_CSV_NAME, CSV_EXTENSION)
                                        ?.let(viewModel::exportBatchCsv)
                                }
                            },
                            onExportSepidar = {
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, BATCH_SEPIDAR_NAME, EXCEL_EXTENSION)
                                        ?.let(viewModel::exportBatchSepidar)
                                }
                            },
                            onExportHoloo = {
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, BATCH_HOLOO_NAME, EXCEL_EXTENSION)
                                        ?.let(viewModel::exportBatchHoloo)
                                }
                            },
                            onExportQuickBooks = {
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, BATCH_QUICKBOOKS_NAME, CSV_EXTENSION)
                                        ?.let(viewModel::exportBatchQuickBooks)
                                }
                            },
                            onExportXero = {
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, BATCH_XERO_NAME, CSV_EXTENSION)
                                        ?.let(viewModel::exportBatchXero)
                                }
                            },
                            showGlobalExports = isEnglish,
                            quickBooksLabel = strings.exportQuickBooks,
                            xeroLabel = strings.exportXero,
                            onClose = viewModel::clearBatch,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (navTab == NavTab.ANALYTICS) {
                        // The snapshot, chips and scope share one language value in
                        // the view model; sync it here so a toggle re-buckets
                        // before the screen reads.
                        LaunchedEffect(isEnglish) {
                            viewModel.setAnalyticsEnglish(isEnglish)
                        }
                        DesktopAnalyticsScreen(
                            analytics = analytics,
                            modifier = Modifier.fillMaxSize(),
                            strings = strings,
                            isEnglish = isEnglish,
                            years = availableYears,
                            selectedYear = selectedYear,
                            onSelectYear = viewModel::selectAnalyticsYear,
                            onExportAnalytics = {
                                val year = selectedYear
                                val defaultName = if (year == null) {
                                    "Analytics_Report_All.xls"
                                } else {
                                    "Analytics_Report_$year.xls"
                                }
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, defaultName, "xls")
                                        ?.let { target ->
                                            viewModel.exportCurrentAnalytics(target, isEnglish)
                                                .onSuccess {
                                                    viewModel.announceExportMessage(
                                                        strings.analyticsReportSuccess,
                                                    )
                                                }
                                                .onFailure(viewModel::reportExportFailure)
                                        }
                                }
                            },
                        )
                    } else if (navTab == NavTab.BATCH) {
                        EmptyBatchState(
                            strings = strings,
                            isSelectionEnabled = isOllamaReady,
                            onPickMultipleFiles = launchBatchPicker,
                        )
                    } else if (isOllamaReady && ollamaStatus is OllamaStatus.ModelMissing) {
                        ModelDownloadPanel(
                            pullProgress = pullProgress,
                            onDownload = viewModel::downloadRequiredModel,
                            modifier = Modifier.padding(MODEL_PANEL_PADDING),
                        )
                    } else {
                        when (val state = uiState) {
                            DesktopUiState.Idle -> IdleContent(
                                strings = strings,
                                isDragOver = isDragOver && isOllamaReady,
                                isSelectionEnabled = isOllamaReady,
                                onPickFile = launchSinglePicker,
                                onPickMultipleFiles = launchBatchPicker,
                            )
                            is DesktopUiState.Processing -> ProcessingContent(fileName = state.fileName)
                            is DesktopUiState.Ready -> ReadyContent(
                                strings = strings,
                                invoice = state.invoice,
                                isSaved = state.isSaved,
                                isDirty = state.isDirty,
                                viewModel = viewModel,
                                onPrintFormal = triggerPrintFormal,
                                onBack = goHome,
                                onExportExcel = triggerExcelExport,
                                onExportCsv = {
                                    scope.launch(Dispatchers.IO) {
                                        saveFileDialog(parentFrame, DEFAULT_CSV_NAME, CSV_EXTENSION)
                                            ?.let(viewModel::exportCsv)
                                    }
                                },
                                onExportSepidar = {
                                    scope.launch(Dispatchers.IO) {
                                        saveFileDialog(parentFrame, SEPIDAR_EXCEL_NAME, EXCEL_EXTENSION)
                                            ?.let(viewModel::exportCurrentSepidar)
                                    }
                                },
                                onExportHoloo = {
                                    scope.launch(Dispatchers.IO) {
                                        saveFileDialog(parentFrame, HOLOO_EXCEL_NAME, EXCEL_EXTENSION)
                                            ?.let(viewModel::exportCurrentHoloo)
                                    }
                                },
                                onExportQuickBooks = {
                                    scope.launch(Dispatchers.IO) {
                                        saveFileDialog(parentFrame, QUICKBOOKS_CSV_NAME, CSV_EXTENSION)
                                            ?.let { viewModel.exportQuickBooks(it, null) }
                                    }
                                },
                                  onExportXero = {
                                      scope.launch(Dispatchers.IO) {
                                          saveFileDialog(parentFrame, XERO_CSV_NAME, CSV_EXTENSION)
                                              ?.let { viewModel.exportXero(it, null) }
                                      }
                                  },
                                  onSave = viewModel::saveCurrentInvoice,
                                  onReset = viewModel::reset,
                                  showGlobalExports = isEnglish,
                                  isEnglish = isEnglish,
                              )
                            is DesktopUiState.Error -> ErrorContent(
                                message = state.message,
                                onReset = viewModel::reset,
                            )
                        }
                    }
                }
            }
        }

        ClayBottomDock(
            clay = clay,
            strings = strings,
            activeTab = activeTab,
            isDarkMode = isDarkMode,
            onSelectTab = { viewModel.currentNavTab.value = it },
            onHistoryClick = { showHistoryDialog = true },
            onMappingsClick = { showMappingsDialog = true },
            onToggleTheme = onToggleTheme,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showHistoryDialog) {
        HistoryDialog(
            invoices = savedInvoices,
            parentFrame = parentFrame,
            onDismiss = { showHistoryDialog = false },
            // The archive hands the record to the editor: load it, return the
            // workspace to the scanner tab so it is visible, and dismiss.
            onSelect = { invoice ->
                viewModel.loadSavedInvoice(invoice)
                viewModel.selectTab(NavTab.SCANNER)
                showHistoryDialog = false
            },
            onDelete = viewModel::deleteSavedInvoice,
            onUpdateInvoice = viewModel::updateStoredInvoice,
            reconciliation = reconciliation,
            reconciliationFailed = reconciliationFailed,
            onReconcileFile = viewModel::reconcileFile,
            onApplyMatched = viewModel::applyMatchedPayments,
            onExportExcel = viewModel::exportSavedInvoiceExcel,
            onExportSepidar = viewModel::exportSavedInvoiceSepidar,
            onExportHoloo = viewModel::exportSavedInvoiceHoloo,
            onExportQuickBooks = viewModel::exportSavedInvoiceQuickBooks,
            onExportXero = viewModel::exportSavedInvoiceXero,
            onExportClientStatement = viewModel::exportClientStatement,
            onExportPettyCashSettlement = viewModel::exportPettyCashSettlement,
            mappingCount = productMappings.size,
            onCreateBackup = viewModel::performBackup,
            onRestoreBackup = viewModel::performRestore,
            onWatcherClick = { showWatcherDialog = true },
            strings = strings,
            isEnglish = isEnglish,
        )
    }

    if (showMappingsDialog) {
        ProductMappingsDialog(
            mappings = productMappings,
            onDismiss = { showMappingsDialog = false },
            onAdd = { rawName, vendor, code, internalName ->
                viewModel.saveProductMapping(rawName, vendor, code, internalName)
            },
            onDelete = viewModel::deleteProductMapping,
            strings = strings,
            isEnglish = isEnglish,
        )
    }

    if (showShortcutsDialog) {
        KeyboardShortcutsDialog(
            onDismiss = { showShortcutsDialog = false },
            strings = strings,
            isEnglish = isEnglish,
        )
    }

    if (showWatcherDialog) {
        FolderWatcherDialog(
            config = watcherConfig,
            parentFrame = parentFrame,
            onDismiss = { showWatcherDialog = false },
            onFolderChosen = viewModel::setWatchedFolder,
            onToggle = viewModel::toggleWatcher,
            strings = strings,
            isEnglish = isEnglish,
        )
    }

    if (showDonateDialog) {
        CryptoDonationDialog(
            onDismiss = { showDonateDialog = false },
            strings = strings,
            isEnglish = isEnglish,
        )
    }
    }
}

/**
 * The hot-folder toast: a floating clay pill under the header, emerald for a
 * saved invoice, error red for a failure with its reason. Dismisses itself
 * after a few seconds or immediately via the close dot.
 */
@Composable
private fun WatcherToastBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
            .border(
                1.dp,
                if (isError) ClayTheme.colors.badgeRed.border else ClayTheme.colors.badgeGreen.border,
                RoundedCornerShape(20.dp),
            ),
        color = if (isError) ClayTheme.colors.badgeRed.background else ClayTheme.colors.badgeGreen.background,
        contentColor = if (isError) ClayTheme.colors.badgeRed.text else ClayTheme.colors.badgeGreen.text,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}

private const val WATCHER_TOAST_MS = 5_000L

/**
 * Centered floating frosted-glass dock with six glossy 3D capsule buttons.
 */
@Composable
private fun ClayBottomDock(
    clay: com.invoiceextract.desktop.presentation.ui.theme.ClayColors,
    strings: AppStrings,
    activeTab: NavTab,
    isDarkMode: Boolean,
    onSelectTab: (NavTab) -> Unit,
    onHistoryClick: () -> Unit,
    onMappingsClick: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(bottom = 16.dp)
            .shadow(16.dp, RoundedCornerShape(40.dp), clip = false)
            .border(1.dp, clay.dockBorder, RoundedCornerShape(40.dp)),
        color = clay.dockContainer,
        shape = RoundedCornerShape(40.dp),
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClayDockButton(
                label = strings.dockScan,
                icon = Icons.Filled.Add,
                gradient = ClayDockButtons.scan,
                selected = activeTab == NavTab.SCANNER,
                onClick = { onSelectTab(NavTab.SCANNER) },
            )
            ClayDockButton(
                label = strings.dockBatch,
                icon = Icons.AutoMirrored.Filled.List,
                gradient = ClayDockButtons.batch,
                selected = activeTab == NavTab.BATCH,
                onClick = { onSelectTab(NavTab.BATCH) },
            )
            ClayDockButton(
                label = strings.dockAnalytics,
                icon = Icons.Filled.Star,
                gradient = ClayDockButtons.analytics,
                selected = activeTab == NavTab.ANALYTICS,
                onClick = { onSelectTab(NavTab.ANALYTICS) },
            )
            ClayDockButton(
                label = strings.dockHistory,
                icon = Icons.Filled.DateRange,
                gradient = ClayDockButtons.archive,
                selected = false,
                onClick = onHistoryClick,
            )
            ClayDockButton(
                label = strings.dockMapping,
                icon = Icons.Filled.Build,
                gradient = ClayDockButtons.mapping,
                selected = false,
                onClick = onMappingsClick,
            )
            ClayDockButton(
                label = strings.dockTheme,
                icon = if (isDarkMode) Icons.Filled.CheckCircle else Icons.Filled.Info,
                gradient = if (isDarkMode) ClayDockButtons.themeToggleDark else ClayDockButtons.themeToggleLight,
                selected = isDarkMode,
                onClick = onToggleTheme,
            )
        }
    }
}

@Composable
private fun DashboardMetricRow(
    strings: AppStrings,
    savedCount: Int,
    ollamaState: OllamaState,
    batch: DesktopBatchProgress?,
    modifier: Modifier = Modifier,
) {
    val engineText = when (ollamaState) {
        OllamaState.Ready -> strings.engineReady
        is OllamaState.Error -> strings.engineError
        else -> strings.engineStarting
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetricCard(
            value = savedCount.toString(),
            label = strings.metricSaved,
            icon = Icons.AutoMirrored.Filled.List,
            background = Brush.linearGradient(
                listOf(Color(0xFF0284C7), Color(0xFF06B6D4)),
            ),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = engineText,
            label = strings.metricEngine,
            icon = if (ollamaState is OllamaState.Ready) Icons.Filled.CheckCircle else Icons.Filled.Info,
            background = Brush.linearGradient(
                listOf(Color(0xFF4F46E5), Color(0xFF6366F1)),
            ),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = batch?.let { "${it.completed} از ${it.total}" } ?: "—",
            label = strings.metricBatch,
            icon = Icons.Filled.Add,
            background = Brush.linearGradient(
                listOf(Color(0xFFDB2777), Color(0xFF9333EA)),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun MetricCard(
    value: String,
    label: String,
    icon: ImageVector,
    background: Brush,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(background, RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(METRIC_ICON_SIZE),
            )
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyBatchState(
    strings: AppStrings,
    isSelectionEnabled: Boolean,
    onPickMultipleFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(EMPTY_BATCH_ICON_SIZE),
        )
        Text(
            text = strings.emptyBatchTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = StatusColors.slateHeader,
            textAlign = TextAlign.Center,
        )
        Text(
            text = strings.emptyBatchSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.slateSubtitle,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onPickMultipleFiles,
            enabled = isSelectionEnabled,
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = strings.dropBatch, maxLines = 1)
        }
    }
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = Color(0x80FFFFFF),
            shape = RoundedCornerShape(22.dp),
        ),
        color = Color(0xF5FFFFFF),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
private fun OllamaProvisioningBanner(
    state: OllamaState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        OllamaState.Ready -> return
        OllamaState.Checking -> ProvisioningCard(
            message = "در حال بررسی وضعیت سرویس هوش مصنوعی...",
            progress = null,
            modifier = modifier,
        )
        is OllamaState.DownloadingInstaller -> ProvisioningCard(
            message = "در حال دانلود نصاب خودکار Ollama (${(state.progress * 100).toInt()}٪)...",
            progress = state.progress.coerceIn(0f, 1f),
            modifier = modifier,
        )
        OllamaState.Installing -> ProvisioningCard(
            message = "در حال نصب خودکار سرویس Ollama در ویندوز...",
            progress = null,
            modifier = modifier,
        )
        OllamaState.StartingService -> ProvisioningCard(
            message = "در حال راه‌اندازی پروسس پس‌زمینه...",
            progress = null,
            modifier = modifier,
        )
        is OllamaState.DownloadingModel -> ProvisioningCard(
            message = "در حال دریافت مدل هوش مصنوعی (${(state.progress * 100).toInt()}٪) - ${state.status}",
            progress = state.progress.coerceIn(0f, 1f),
            modifier = modifier,
        )
        is OllamaState.Error -> ElevatedCard(modifier = modifier) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.canRetry) {
                    OutlinedButton(onClick = onRetry) {
                        Text(text = "تلاش مجدد")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvisioningCard(
    message: String,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    strings: AppStrings,
    isDragOver: Boolean,
    onPickFile: () -> Unit,
    onPickMultipleFiles: () -> Unit,
    isSelectionEnabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DragAndDropDropZone(
                strings = strings,
                isDragOver = isDragOver,
                onPickFile = onPickFile,
                onPickMultipleFiles = onPickMultipleFiles,
                isEnabled = isSelectionEnabled,
            )
            if (!isSelectionEnabled) {
                Text(
                    text = "پردازش فاکتور پس از آماده‌سازی خودکار سرویس هوش مصنوعی فعال می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProcessingContent(fileName: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(PROGRESS_INDICATOR_SIZE))
        Text(
            text = "در حال پردازش سند $fileName با هوش مصنوعی محلی...",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ReadyContent(
    strings: AppStrings,
    invoice: Invoice,
    isSaved: Boolean,
    isDirty: Boolean,
    viewModel: DesktopViewModel,
    onPrintFormal: () -> Unit,
    onBack: () -> Unit,
      onExportExcel: () -> Unit,
      onExportCsv: () -> Unit,
      onExportSepidar: () -> Unit,
      onExportHoloo: () -> Unit,
      onExportQuickBooks: () -> Unit,
      onExportXero: () -> Unit,
      onSave: () -> Unit,
      onReset: () -> Unit,
      showGlobalExports: Boolean = false,
      isEnglish: Boolean = false,
  ) {
      val previewVisible by viewModel.isPreviewPaneVisible.collectAsState()
    val sourceFile = viewModel.currentSourceFile.collectAsState().value

    if (previewVisible && sourceFile != null) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val previewPageCount by viewModel.previewPageCount.collectAsState()
            val previewPageIndex by viewModel.previewPageIndex.collectAsState()
            val previewBitmap by viewModel.previewBitmap.collectAsState()
            val previewError by viewModel.previewError.collectAsState()

            DesktopDocumentPreviewPanel(
                sourceFile = sourceFile,
                pageCount = previewPageCount,
                pageIndex = previewPageIndex,
                bitmap = previewBitmap,
                errorMessage = previewError,
                onNextPage = viewModel::nextPreviewPage,
                onPrevPage = viewModel::prevPreviewPage,
                onClose = viewModel::togglePreviewPane,
                modifier = Modifier
                    .weight(PREVIEW_PANE_WEIGHT)
                    .fillMaxHeight(),
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                thickness = 1.dp,
                color = StatusColors.hairlineBorder,
            )
            InvoiceEditorContent(
                  strings = strings,
                  invoice = invoice,
                  isSaved = isSaved,
                  isDirty = isDirty,
                  viewModel = viewModel,
                  showPreviewToggle = false,
                  onPrintFormal = onPrintFormal,
                  onBack = onBack,
                  onTogglePreview = viewModel::togglePreviewPane,
                  onExportExcel = onExportExcel,
                  onExportCsv = onExportCsv,
                  onExportSepidar = onExportSepidar,
                  onExportHoloo = onExportHoloo,
                  onExportQuickBooks = onExportQuickBooks,
                  onExportXero = onExportXero,
                  onSave = onSave,
                  onReset = onReset,
                  showGlobalExports = showGlobalExports,
                  isEnglish = isEnglish,
                  modifier = Modifier.weight(EDITOR_PANE_WEIGHT),
              )
        }
    } else {
        InvoiceEditorContent(
              strings = strings,
              invoice = invoice,
              isSaved = isSaved,
              isDirty = isDirty,
              viewModel = viewModel,
              showPreviewToggle = sourceFile != null,
              onPrintFormal = onPrintFormal,
              onBack = onBack,
              onTogglePreview = viewModel::togglePreviewPane,
              onExportExcel = onExportExcel,
              onExportCsv = onExportCsv,
              onExportSepidar = onExportSepidar,
              onExportHoloo = onExportHoloo,
              onExportQuickBooks = onExportQuickBooks,
              onExportXero = onExportXero,
              onSave = onSave,
              onReset = onReset,
              showGlobalExports = showGlobalExports,
              isEnglish = isEnglish,
              modifier = Modifier.fillMaxWidth(),
          )
    }
}

@Composable
private fun InvoiceEditorContent(
    strings: AppStrings,
    invoice: Invoice,
    isSaved: Boolean,
    isDirty: Boolean,
    viewModel: DesktopViewModel,
    showPreviewToggle: Boolean,
    onPrintFormal: () -> Unit,
    onBack: () -> Unit,
    onTogglePreview: () -> Unit,
        onExportExcel: () -> Unit,
        onExportCsv: () -> Unit,
        onExportSepidar: () -> Unit,
        onExportHoloo: () -> Unit,
        onExportQuickBooks: () -> Unit,
        onExportXero: () -> Unit,
        onSave: () -> Unit,
        onReset: () -> Unit,
        showGlobalExports: Boolean = false,
        isEnglish: Boolean = false,
        modifier: Modifier = Modifier,
    ) {
        val duplicate = viewModel.duplicateMatch.collectAsState().value

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        // The editor's own way home, beside the Save/Export actions below:
        // closes the invoice and returns to the scanner drop-zone.
        item(key = "back-home") {
            OutlinedButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = strings.btnBack, maxLines = 1)
            }
        }
        if (showPreviewToggle) {
            item(key = "preview-toggle") {
                OutlinedButton(onClick = onTogglePreview) {
                    Text(text = SHOW_SOURCE_LABEL, maxLines = 1)
                }
            }
        }

        if (duplicate != null) {
            item(key = "duplicate") {
                DuplicateWarningBanner(
                    match = duplicate,
                    onViewPrevious = { viewModel.loadSavedInvoice(duplicate.existingInvoice) },
                    onDismiss = viewModel::dismissDuplicateWarning,
                )
            }
        }

        item(key = "banner") { ValidationBanner(status = invoice.validationStatus) }

        item(key = "metadata") {
            InvoiceMetadataCard(
                invoice = invoice,
                onMetadataChange = viewModel::updateMetadata,
                onCurrencyChange = viewModel::updateCurrency,
                onPaymentStatusChange = viewModel::updatePaymentStatus,
                onDueDateChange = viewModel::updateDueDate,
                paymentPaidLabel = strings.paymentPaid,
                paymentPendingLabel = strings.paymentPending,
                paymentOverdueLabel = strings.paymentOverdue,
                paymentSectionLabel = strings.paymentSection,
                dueDateLabel = strings.dueDateLabel,
                // Global mode implies the English workspace, whose manual-entry
                // default currency is USD; same flag drives the export menu.
                defaultCurrency = defaultCurrencyFor(showGlobalExports),
                isEnglish = isEnglish,
                taxEinValidLabel = strings.taxEinValid,
                taxVatValidLabel = strings.taxVatValid,
                taxIdInvalidLabel = strings.taxIdInvalid,
            )
        }

        item(key = "table") {
            EditableInvoiceTable(
                strings = strings,
                invoice = invoice,
                onItemChange = viewModel::updateItem,
                onProductCodeChange = viewModel::updateProductCode,
            )
        }

          item(key = "totals") {
              TotalsCard(
                  invoice = invoice,
                  onExportExcel = onExportExcel,
                  onExportCsv = onExportCsv,
                  onExportSepidar = onExportSepidar,
                  onExportHoloo = onExportHoloo,
                  onExportQuickBooks = onExportQuickBooks,
                  onExportXero = onExportXero,
                  showGlobalExports = showGlobalExports,
                  onSave = onSave,
                isSaved = isSaved,
                isDirty = isDirty,
                onReset = onReset,
                onPrintFormal = onPrintFormal,
                strings = strings,
            )
        }
    }
}

@Composable
private fun DuplicateWarningBanner(
    match: DuplicateMatch,
    onViewPrevious: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = when (match.reason) {
        DuplicateReason.EXACT_NUMBER ->
            "فاکتوری با همین شماره (${match.existingInvoice.invoiceNumber})" +
                " از فروشنده «${match.existingInvoice.sellerName}» قبلاً در سیستم ثبت شده است."
        DuplicateReason.FINGERPRINT_MATCH ->
            "فاکتوری با همین مبلغ و در تاریخ ${match.existingInvoice.date}" +
                " از «${match.existingInvoice.sellerName}» در سیستم موجود است."
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = ClayTheme.colors.badgeAmber.border,
                shape = RoundedCornerShape(16.dp),
            ),
        color = ClayTheme.colors.badgeAmber.background,
        contentColor = ClayTheme.colors.badgeAmber.text,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = ClayTheme.colors.badgeAmber.text,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "احتمال ثبت فاکتور تکراری!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ClayTheme.colors.badgeAmber.text,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = ClayTheme.colors.textPrimary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onViewPrevious) {
                    Text(text = "مشاهده فاکتور قبلی", maxLines = 1)
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "رد کردن هشدار", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ClayTheme.colors.badgeRed.background,
            contentColor = ClayTheme.colors.badgeRed.text,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(ERROR_ICON_SIZE),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onReset) {
                    Text(text = "تلاش مجدد / بازگشت")
                }
            }
        }
    }
}

@Composable
private fun ModelDownloadPanel(
    pullProgress: PullProgress?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = ClayTheme.colors.badgeAmber.background,
        contentColor = ClayTheme.colors.badgeAmber.text,
        shape = RoundedCornerShape(MODEL_PANEL_CORNER),
    ) {
        Column(
            modifier = Modifier.padding(MODEL_PANEL_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = MODEL_MISSING_TITLE,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = MODEL_MISSING_SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            if (pullProgress is PullProgress.Downloading) {
                LinearProgressIndicator(
                    progress = { pullProgress.percent },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "$DOWNLOADING_MESSAGE${(pullProgress.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            } else {
                (pullProgress as? PullProgress.Status)?.let { status ->
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }

                Button(onClick = onDownload) {
                    Text(text = DOWNLOAD_BUTTON_LABEL)
                }
            }
        }
    }
}

private const val EXCEL_EXTENSION = "xls"
private const val CSV_EXTENSION = "csv"
private const val HTML_EXTENSION = "html"
private const val DEFAULT_EXCEL_NAME = "invoice.xls"
private const val DEFAULT_CSV_NAME = "invoice.csv"
private const val SEPIDAR_EXCEL_NAME = "sepidar.xls"
private const val HOLOO_EXCEL_NAME = "holoo.xls"
private const val QUICKBOOKS_CSV_NAME = "quickbooks.csv"
private const val XERO_CSV_NAME = "xero.csv"
private const val BATCH_EXCEL_NAME = "ledger.xls"
private const val BATCH_CSV_NAME = "ledger.csv"
private const val BATCH_SEPIDAR_NAME = "ledger-sepidar.xls"
private const val BATCH_HOLOO_NAME = "ledger-holoo.xls"
private const val BATCH_QUICKBOOKS_NAME = "ledger-quickbooks.csv"
private const val BATCH_XERO_NAME = "ledger-xero.csv"

private const val MODEL_MISSING_TITLE = "مدل هوش مصنوعی فاکتورخوان آماده نیست"
private const val MODEL_MISSING_SUBTITLE =
    "برای استفاده ۱۰۰٪ آفلاین، نیاز به دریافت یک‌باره مدل (حدود ۱.۹ گیگابایت) است."
private const val DOWNLOADING_MESSAGE = "در حال دانلود مدل هوش مصنوعی: "
private const val DOWNLOAD_BUTTON_LABEL = "دانلود و نصب خودکار مدل (یک کلیک)"
private const val SHOW_SOURCE_LABEL = "نمایش سند اصلی"

private const val PREVIEW_PANE_WEIGHT = 0.42f
private const val EDITOR_PANE_WEIGHT = 0.58f

private val PROGRESS_INDICATOR_SIZE = 48.dp
private val ERROR_ICON_SIZE = 40.dp
private val METRIC_ICON_SIZE = 30.dp
private val EMPTY_BATCH_ICON_SIZE = 44.dp
private val MODEL_PANEL_CORNER = 12.dp
private val MODEL_PANEL_PADDING = 24.dp
