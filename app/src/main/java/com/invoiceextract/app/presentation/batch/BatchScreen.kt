package com.invoiceextract.app.presentation.batch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invoiceextract.app.data.batch.model.BatchItemResult
import com.invoiceextract.app.data.batch.model.BatchProgressState
import com.invoiceextract.app.data.batch.model.ItemStatus
import com.invoiceextract.app.presentation.review.ExportEvent
import com.invoiceextract.app.ui.theme.ConfidenceHigh
import com.invoiceextract.app.ui.theme.ConfidenceLow
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Batch processing screen (Phase 10.2).
 *
 * A pure function of [BatchUiState]: pickers stage documents, the ViewModel runs the
 * sequential queue, and every state transition is a recomposition with no state held in
 * the composables themselves. RTL is forced globally by `InvoiceExtractTheme` in
 * [com.invoiceextract.app.MainActivity], so every Material 3 arrangement below is
 * direction-aware with no manual mirroring.
 *
 * **Pickers.** Four SAF contracts, each returning a list of Uris that the ViewModel
 * stages into the app cache before the grant can lapse. The photo picker caps at 15
 * images, matching the batch ceiling; the document picker is scoped to `application/pdf`.
 * Export uses `CreateDocument`, whose write grant is transient and tied to the activity,
 * so the export completes inside the ViewModel call rather than outliving the screen.
 *
 * **One-shot results.** Export outcomes arrive on [BatchViewModel.exportEvents] and are
 * surfaced as a snackbar, never as state — a success message that lingered across
 * recompositions would look like a permanent banner.
 *
 * @param onNavigateBack Pops the back stack; wired to the TopAppBar arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    onNavigateBack: () -> Unit,
    viewModel: BatchViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Multiple images: the photo picker keeps the user in a single selection session and
    // needs no runtime permission. Capped at 15 to bound the queue's memory footprint.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PICKED_IMAGES),
    ) { uris -> if (uris.isNotEmpty()) viewModel.onUrisSelected(uris) }

    // Multiple PDFs through SAF. The MIME filter is what scopes the offered documents.
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.onUrisSelected(uris) }

    // SAF "create file" for the two consolidated exports. The MIME type drives the
    // extension the provider appends, so the xls contract carries the legacy Excel MIME.
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.exportBatchCsv(it) } }

    val excelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.ms-excel"),
    ) { uri -> uri?.let { viewModel.exportBatchExcel(it) } }

    // One-shot export results, surfaced as a transient banner rather than recurring state.
    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { event ->
            showExportResult(snackbarHostState, event)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = BATCH_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = ACTION_BACK,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val state = uiState) {
                is BatchUiState.Idle -> IdleContent(
                    onPickImages = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickPdfs = { pdfPicker.launch(arrayOf(MIME_PDF)) },
                )

                is BatchUiState.Selected -> SelectedContent(
                    fileCount = state.files.size,
                    onClear = viewModel::reset,
                    onStart = viewModel::startProcessing,
                )

                is BatchUiState.Processing -> ProcessingContent(
                    progress = state.progress,
                    onCancel = viewModel::cancelProcessing,
                )

                is BatchUiState.Completed -> CompletedContent(
                    progress = state.progress,
                    isExporting = state.isExporting,
                    onExportExcel = { excelLauncher.launch(SUGGESTED_EXCEL_NAME) },
                    onExportCsv = { csvLauncher.launch(SUGGESTED_CSV_NAME) },
                    onReset = viewModel::reset,
                )
            }
        }
    }
}

/**
 * Shows an export result as a snackbar. Suspends for the duration of the display, so
 * consecutive results queue instead of overwriting each other mid-animation.
 */
private suspend fun showExportResult(
    snackbarHostState: SnackbarHostState,
    event: ExportEvent,
) {
    snackbarHostState.showSnackbar(event.message)
}

// ---------------------------------------------------------------------- Idle

/**
 * The two multi-select entry actions, styled to match the single-file Home screen so the
 * app reads as one product.
 */
@Composable
private fun IdleContent(
    onPickImages: () -> Unit,
    onPickPdfs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(CONTENT_PADDING)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = BATCH_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Button(
            onClick = onPickImages,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.size(ACTION_ICON_SIZE),
            )
            Spacer(Modifier.width(ACTION_ICON_SPACING))
            Text(text = ACTION_PICK_IMAGES)
        }

        OutlinedButton(
            onClick = onPickPdfs,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(ACTION_ICON_SIZE),
            )
            Spacer(Modifier.width(ACTION_ICON_SPACING))
            Text(text = ACTION_PICK_PDFS)
        }
    }
}

// ------------------------------------------------------------------ Selected

/**
 * Confirms what was staged and offers the go-ahead. The count is the headline number
 * because it is what the run's duration scales with.
 */
@Composable
private fun SelectedContent(
    fileCount: Int,
    onClear: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(CONTENT_PADDING)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = CARD_ELEVATION),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CARD_PADDING),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = SELECTED_COUNT.format(fileCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = SELECTED_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = ACTION_CLEAR)
                    }
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(ACTION_ICON_SIZE),
                        )
                        Spacer(Modifier.width(ACTION_ICON_SPACING))
                        Text(text = ACTION_START)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- Processing

/**
 * The live queue: an overall progress line, a summary line, and one row per file whose
 * status describes exactly where the run is. The list is scrollable because a 15-file
 * batch does not fit a phone screen, and it is not lazy on purpose — the rows are cheap
 * and a lazy column would recycle composables that the progress indicator animates,
 * causing visible flicker as the run advances.
 */
@Composable
private fun ProcessingContent(
    progress: BatchProgressState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(CONTENT_PADDING)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProgressSummary(progress = progress)

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = null,
                modifier = Modifier.size(ACTION_ICON_SIZE),
            )
            Spacer(Modifier.width(ACTION_ICON_SPACING))
            Text(text = ACTION_CANCEL)
        }

        progress.items.forEach { item ->
            BatchItemRow(item = item)
        }
    }
}

/**
 * The deterministic part of the progress display: "X از Y فاکتور" over a linear bar whose
 * fraction is the completed share of the queue.
 *
 * The bar is determinate because the queue has a known length, and the fraction guards
 * against a divide-by-zero producing `NaN` progress when the batch is empty.
 */
@Composable
private fun ProgressSummary(progress: BatchProgressState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = PROGRESS_COUNT.format(progress.completedCount, progress.totalCount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        val fraction = if (progress.totalCount > 0) {
            progress.completedCount.toFloat() / progress.totalCount.toFloat()
        } else {
            0f
        }

        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(PROGRESS_BAR_HEIGHT),
        )
    }
}

/**
 * One file's row. The leading affordance is what carries the status, so the row reads
 * correctly at a glance before any text is parsed.
 *
 * - PENDING: a gray clock — queued, not started.
 * - PROCESSING: a spinner — the only animated element on the row.
 * - SUCCESS: a green check, plus the invoice's grand total when the model produced one.
 * - FAILED: a red alert, plus the localized reason.
 */
@Composable
private fun BatchItemRow(item: BatchItemResult, modifier: Modifier = Modifier) {
    val visual = item.visual()

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = CARD_ELEVATION),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CARD_PADDING),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.status) {
                ItemStatus.PENDING -> Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(STATUS_ICON_SIZE),
                )

                ItemStatus.PROCESSING -> CircularProgressIndicator(
                    modifier = Modifier.size(STATUS_ICON_SIZE),
                    strokeWidth = SPINNER_STROKE_WIDTH,
                )

                ItemStatus.SUCCESS, ItemStatus.FAILED -> Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(STATUS_ICON_SIZE),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )

                val detail = item.detailText()
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = visual.color,
                    )
                }
            }
        }
    }
}

/** Icon and colour for an item's status; the FAILED reason supplies its own text. */
private data class ItemVisual(
    val icon: ImageVector,
    val color: Color,
)

@Composable
private fun BatchItemResult.visual(): ItemVisual = when (status) {
    ItemStatus.PENDING -> ItemVisual(
        icon = Icons.Filled.Schedule,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // The colour is unused while the spinner is showing, so the theme read is skipped.
    ItemStatus.PROCESSING -> ItemVisual(
        icon = Icons.Filled.Schedule,
        color = Color.Unspecified,
    )
    ItemStatus.SUCCESS -> ItemVisual(
        icon = Icons.Filled.CheckCircle,
        color = ConfidenceHigh,
    )
    ItemStatus.FAILED -> ItemVisual(
        icon = Icons.Filled.Error,
        color = ConfidenceLow,
    )
}

/**
 * The secondary line under the file name: the invoice's grand total for a success, the
 * localized reason for a failure, and nothing at all while queued or in flight.
 */
@Composable
private fun BatchItemResult.detailText(): String? = when (status) {
    ItemStatus.SUCCESS -> invoice?.let { invoice -> formatToman(invoice.grandTotal) }
    ItemStatus.FAILED -> errorMessage
    else -> null
}

// --------------------------------------------------------------- Completed

/**
 * The run's verdict card and its export actions. Tinted green because the batch screen
 * is a ledger summary, and the tint reads as "done" even when some items failed — the
 * counts beneath carry the honest breakdown.
 */
@Composable
private fun CompletedContent(
    progress: BatchProgressState,
    isExporting: Boolean,
    onExportExcel: () -> Unit,
    onExportCsv: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(CONTENT_PADDING)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryCard(progress = progress)

        if (isExporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Excel first: it is the format the ledger opens in for an accountant.
            Button(
                onClick = onExportExcel,
                enabled = progress.successCount > 0 && !isExporting,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.TableChart,
                    contentDescription = null,
                    modifier = Modifier.size(ACTION_ICON_SIZE),
                )
                Spacer(Modifier.width(ACTION_ICON_SPACING))
                Text(text = ACTION_EXPORT_EXCEL)
            }
            OutlinedButton(
                onClick = onExportCsv,
                enabled = progress.successCount > 0 && !isExporting,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(ACTION_ICON_SIZE),
                )
                Spacer(Modifier.width(ACTION_ICON_SPACING))
                Text(text = ACTION_EXPORT_CSV)
            }
        }

        // The failed items stay visible below the summary, so the user can see which
        // documents need a re-scan instead of just a count.
        progress.items.filter { it.status == ItemStatus.FAILED }.forEach { item ->
            BatchItemRow(item = item)
        }

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = ACTION_RESET)
        }
    }
}

/**
 * Green-tinted summary of the run: how many invoices were extracted and how many failed.
 * Both counts are shown even when one is zero, so the card's structure is predictable.
 */
@Composable
private fun SummaryCard(progress: BatchProgressState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ConfidenceHigh.copy(alpha = CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CARD_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = ConfidenceHigh,
                    modifier = Modifier.size(SUMMARY_ICON_SIZE),
                )
                Text(
                    text = SUMMARY_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ConfidenceHigh,
                )
            }

            HorizontalSummaryLine(
                label = SUMMARY_TOTAL,
                value = progress.successCount.toString(),
            )
            HorizontalSummaryLine(
                label = SUMMARY_FAILED,
                value = progress.failureCount.toString(),
                valueColor = if (progress.failureCount > 0) ConfidenceLow else null,
            )
        }
    }
}

@Composable
private fun HorizontalSummaryLine(
    label: String,
    value: String,
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Formats an amount as grouped Toman digits. Grouping uses [Locale.US] on purpose: the
 * digits are Latin and the separator a plain comma, which is how the rest of the app
 * formats numbers, while the currency word stays Persian and renders correctly under RTL.
 */
private fun formatToman(amount: Double): String {
    val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    return formatter.format(amount) + " " + CURRENCY
}

// Persian user-facing strings. Kept as top-level constants rather than string resources
// so the batch feature ships as one self-contained package; the rest of the app's
// screens (see ReviewScreen) follow the same convention.

private const val BATCH_TITLE = "پردازش دسته‌ای فاکتورها"
private const val BATCH_SUBTITLE = "چند فاکتور را انتخاب کنید تا به صورت نوبتی پردازش شوند."
private const val ACTION_BACK = "بازگشت"
private const val ACTION_PICK_IMAGES = "انتخاب چند تصویر"
private const val ACTION_PICK_PDFS = "انتخاب چند فایل PDF"
private const val ACTION_CLEAR = "پاکسازی"
private const val ACTION_START = "شروع پردازش دسته‌ای"
private const val ACTION_CANCEL = "لغو عملیات"
private const val ACTION_EXPORT_EXCEL = "خروجی اکسل تجمیعی"
private const val ACTION_EXPORT_CSV = "خروجی CSV تجمیعی"
private const val ACTION_RESET = "شروع دوباره / پاکسازی"

private const val SELECTED_HINT =
    "فاکتورها یکی‌یکی و به ترتیب پردازش می‌شوند. فایل‌های نامعتبر رد می‌شوند اما پردازش ادامه می‌یابد."

private const val PROGRESS_COUNT = "در حال پردازش %1\$d از %2\$d فاکتور"
private const val SELECTED_COUNT = "%1\$d فاکتور انتخاب شد"

private const val SUMMARY_TITLE = "پردازش دسته‌ای به پایان رسید"
private const val SUMMARY_TOTAL = "فاکتورهای استخراج‌شده"
private const val SUMMARY_FAILED = "فاکتورهای ناموفق"

private const val CURRENCY = "تومان"
private const val MIME_PDF = "application/pdf"
private const val SUGGESTED_CSV_NAME = "batch_invoices.csv"
private const val SUGGESTED_EXCEL_NAME = "batch_invoices.xls"

/** Photo picker ceiling, matching the batch's memory budget. */
private const val MAX_PICKED_IMAGES = 15

// Layout metrics reused across the states.

private val CONTENT_PADDING = 16.dp
private val CARD_PADDING = 16.dp
private val CARD_ELEVATION = 3.dp
private val ACTION_ICON_SIZE = 18.dp
private val ACTION_ICON_SPACING = 8.dp
private val STATUS_ICON_SIZE = 24.dp
private val SUMMARY_ICON_SIZE = 28.dp
private val SPINNER_STROKE_WIDTH = 2.dp
private val PROGRESS_BAR_HEIGHT = 8.dp

private const val CONTAINER_ALPHA = 0.12f
