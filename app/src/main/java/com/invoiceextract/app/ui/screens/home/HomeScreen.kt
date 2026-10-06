package com.invoiceextract.app.ui.screens.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invoiceextract.app.R
import com.invoiceextract.app.domain.usecase.ProcessInvoiceUseCase.ProcessingStage
import com.invoiceextract.app.presentation.profile.ProfileBottomSheet
import com.invoiceextract.app.ui.theme.ConfidenceHigh
import com.invoiceextract.domain.model.Invoice
import org.koin.androidx.compose.koinViewModel
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Standard Material 3 icon metrics used inside the action buttons. */
private object ButtonMetrics {
    val IconSize = 18.dp
    val IconSpacing = 8.dp
}

/**
 * Landing screen: file import entry point.
 *
 * Holds two system-backed pickers (Photo Picker for images, SAF for PDFs), so no
 * runtime storage permission is ever requested. The selected document is staged
 * into the app cache by the ViewModel before the URI permission can expire, and
 * the resulting file is shown as a preview card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onReview: (Invoice) -> Unit,
    onNavigateToBatch: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // The Profile sheet is hosted here rather than navigated to: it is a modal overlay on
    // the file-import flow, and keeping its visibility in Home's state means the account is
    // reachable from anywhere the user happens to be on this screen.
    var showProfileSheet by remember { mutableStateOf(false) }

    // Photo Picker: JPG/PNG only. The system retains the selection, so no
    // READ_MEDIA_IMAGES permission is needed.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.onFileSelected(uri)
    }

    // Document picker (SAF) for PDFs: grants a transient, non-persistable URI grant.
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onFileSelected(uri)
    }

    // Errors surface as a transient banner rather than a permanent card.
    LaunchedEffect(uiState) {
        val error = (uiState as? HomeUiState.Error)?.message
        if (error != null) snackbarHostState.showSnackbar(error)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    // The single entry point into the account. An account icon is the
                    // conventional affordance, and it sits in the actions slot so it stays
                    // reachable regardless of which content state the body is showing.
                    IconButton(onClick = { showProfileSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.profile_content_description),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is HomeUiState.Idle -> IdleContent(
                    onPickImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onNavigateToBatch = onNavigateToBatch,
                )

                is HomeUiState.Selected -> SelectedFileCard(
                    fileInfo = state.fileInfo,
                    onClear = viewModel::resetToIdle,
                    onProceed = viewModel::startProcessing,
                )

                is HomeUiState.Processing -> ProcessingContent(
                    fileInfo = state.fileInfo,
                    stage = state.stage,
                )

                is HomeUiState.Success -> SuccessContent(
                    invoice = state.invoice,
                    onReview = { onReview(state.invoice) },
                    onScanNew = viewModel::resetToIdle,
                )

                // Also keep the actions reachable underneath the error banner.
                is HomeUiState.Error -> IdleContent(
                    onPickImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onPickPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onNavigateToBatch = onNavigateToBatch,
                )
            }
        }
    }

    // A ModalBottomSheet composes an overlay, so it sits beside the Scaffold rather than
    // inside the content Box. Dismissing it (swipe, scrim or back) flips the flag back off,
    // which removes it from the composition and drops its ViewModel's subscriptions.
    if (showProfileSheet) {
        ProfileBottomSheet(onDismissRequest = { showProfileSheet = false })
    }
}

/**
 * The two import actions shown when nothing is selected.
 */
@Composable
private fun IdleContent(
    onPickImage: () -> Unit,
    onPickPdf: () -> Unit,
    onNavigateToBatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onPickImage,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.size(ButtonMetrics.IconSize),
            )
            Spacer(Modifier.width(ButtonMetrics.IconSpacing))
            Text(text = stringResource(R.string.action_pick_image))
        }

        OutlinedButton(
            onClick = onPickPdf,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(ButtonMetrics.IconSize),
            )
            Spacer(Modifier.width(ButtonMetrics.IconSpacing))
            Text(text = stringResource(R.string.action_pick_pdf))
        }

        // Secondary entry to the batch flow: separated from the two primary actions so
        // the single-file path stays the default and the batch one reads as intentional.
        BatchEntryCard(onNavigateToBatch = onNavigateToBatch)
    }
}

/**
 * Promotes the batch screen as the multi-document alternative to the single-file flow.
 *
 * Kept visually distinct from the two filled buttons above on purpose: this is a
 * different workflow (sequential queue plus consolidated export), not a third import
 * target, and a plain third button would read as "another file kind to pick".
 */
@Composable
private fun BatchEntryCard(
    onNavigateToBatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onNavigateToBatch,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = null,
                modifier = Modifier.size(ButtonMetrics.IconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_batch_processing),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.action_batch_processing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Preview of the staged file with the actions to replace/remove it or proceed
 * to extraction.
 */
@Composable
private fun SelectedFileCard(
    fileInfo: SelectedInvoiceFile,
    onClear: () -> Unit,
    onProceed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = fileInfo.fileIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = fileInfo.originalName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                    )
                    Text(
                        text = stringResource(
                            id = R.string.file_size_label,
                            fileInfo.sizeFormatted,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.action_change_or_remove))
                }
                Button(
                    // Hands the staged file to ProcessInvoiceUseCase; the UI then
                    // switches to HomeUiState.Processing for the duration of the run.
                    onClick = onProceed,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.action_proceed))
                }
            }
        }
    }
}

@Composable
private fun SelectedInvoiceFile.fileIcon(): ImageVector =
    if (isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Image

/**
 * The pipeline is running: an animated, stage-aware progress bar above the Persian
 * label of the stage currently in flight, with the file being worked on for context.
 *
 * The bar is determinate rather than indeterminate because the pipeline has a known,
 * small number of stages: each of the three [ProcessingStage]s maps to a third of the
 * track, so the user can see the run advancing instead of watching an endless spinner
 * with no sense of how far in it is.
 */
@Composable
private fun ProcessingContent(
    fileInfo: SelectedInvoiceFile,
    stage: ProcessingStage,
    modifier: Modifier = Modifier,
) {
    val totalStages = ProcessingStage.entries.size
    // PARSING_STRUCTURE is the last stage, so the bar finishes exactly when the
    // pipeline is about to hand back a result.
    val progress = (stage.ordinal + 1f) / totalStages

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = fileInfo.originalName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stage.asString(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The localized Persian status text for a stage, resolved through string resources so
 * every user-facing string stays in [R.string] and the UI remains translatable.
 */
@Composable
private fun ProcessingStage.asString(): String = stringResource(
    when (this) {
        ProcessingStage.PREPARING_IMAGE -> R.string.status_preparing_image
        ProcessingStage.RUNNING_OCR -> R.string.status_running_ocr
        ProcessingStage.PARSING_STRUCTURE -> R.string.status_parsing_structure
    },
)

/**
 * Summary of a successfully extracted invoice: identifiers, seller, the grand total in
 * Toman, and the actions to review the result or start over.
 *
 * Every field is rendered as label-above-value rather than a two-column row, so long
 * values (company names, invoice numbers) can wrap without ever clipping in either
 * layout direction.
 */
@Composable
private fun SuccessContent(
    invoice: Invoice,
    onReview: (Invoice) -> Unit,
    onScanNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_toman)

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    // The palette's dedicated success green, so the outcome reads at a
                    // glance instead of blending into the teal brand primary.
                    tint = ConfidenceHigh,
                )
                Text(
                    text = stringResource(R.string.extraction_success_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            HorizontalDivider()

            LabeledValue(
                label = stringResource(R.string.label_seller),
                value = invoice.sellerName,
            )
            LabeledValue(
                label = stringResource(R.string.label_invoice_date),
                value = invoice.date,
            )
            LabeledValue(
                label = stringResource(R.string.label_invoice_number),
                value = invoice.invoiceNumber,
            )

            HorizontalDivider()

            // The amount the user actually cares about, given the most visual weight.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_grand_total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatToman(invoice.grandTotal, currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Secondary action first: discards the result and goes back to scanning.
            OutlinedButton(
                onClick = onScanNew,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.action_scan_new))
            }
            // Primary CTA: hands the extracted invoice to the Review screen, where the
            // validation report is shown before persistence.
            Button(
                onClick = { onReview(invoice) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.action_review_and_validate))
            }
        }
    }
}

/**
 * A label with its value underneath; a null value degrades to a dash rather than
 * vanishing, so the card's structure stays predictable for the user.
 */
@Composable
private fun LabeledValue(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Formats an amount as grouped digits followed by the currency unit, e.g.
 * `"6,593,410 تومان"`. Grouping uses [Locale.US] on purpose — the digits are Latin and
 * the separator is a plain comma, matching how the rest of the app formats numbers —
 * while the currency word stays Persian and renders correctly under RTL.
 */
private fun formatToman(amount: Double, currency: String): String {
    val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    return formatter.format(amount) + " " + currency
}
