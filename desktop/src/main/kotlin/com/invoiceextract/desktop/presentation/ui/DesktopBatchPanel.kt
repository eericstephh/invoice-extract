package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.data.batch.BatchItemStatus
import com.invoiceextract.desktop.data.batch.DesktopBatchItem
import com.invoiceextract.desktop.data.batch.DesktopBatchProgress
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.toPersianLabel
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme

/**
 * The multi-file batch run, drawn as one scrolling dashboard column.
 *
 * Replaces the normal state machine while [progress] is non-null: the header names the
 * run and its live tally, the bar tracks `completed / total`, and every dropped file
 * gets a row that moves from waiting through working to its verdict. Once everything is
 * terminal, a summary card takes over with the consolidated ledger exports — offered
 * only then, because a half-written ledger would silently understate the batch.
 *
 * Only core Material icons are used (close, check, warning, refresh); nothing here
 * needs the extended set the build deliberately stays off.
 *
 * @param progress The coordinator's latest snapshot; re-emitted per file transition.
 * @param onCancel Stops the run where it stands. Shown only while running.
 * @param onExportExcel / [onExportCsv] Write the consolidated ledger of the successful
 *   files. Enabled only when at least one file succeeded.
 * @param onExportSepidar / [onExportHoloo] Same gate for the consolidated accounting
 *   sheets, each on its own button so the three destinations are visible at once.
 * @param onExportQuickBooks / [onExportXero] Same gate for the consolidated
 *   global CSV imports; hidden unless [showGlobalExports].
 * @param onClose Dismisses the panel (and restarts, by dropping again).
 */
@Composable
fun DesktopBatchPanel(
    progress: DesktopBatchProgress,
    onCancel: () -> Unit,
    onExportExcel: () -> Unit,
    onExportCsv: () -> Unit,
    onExportSepidar: () -> Unit,
    onExportHoloo: () -> Unit,
    onExportQuickBooks: () -> Unit = {},
    onExportXero: () -> Unit = {},
    showGlobalExports: Boolean = false,
    quickBooksLabel: String = "کوییک‌بوکس تجمیعی (.csv)",
    xeroLabel: String = "زیرو تجمیعی (.csv)",
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "header") {
            BatchHeaderCard(
                progress = progress,
                onCancel = onCancel,
            )
        }

        items(progress.items, key = { it.file.absolutePath }) { item ->
            BatchItemRow(item = item)
        }

        if (progress.isFinished) {
            item(key = "summary") {
                BatchSummaryCard(progress = progress)
            }

            item(key = "actions") {
                BatchActionsRow(
                    hasSuccesses = progress.successCount > 0,
                    onExportExcel = onExportExcel,
                    onExportCsv = onExportCsv,
                    onExportSepidar = onExportSepidar,
                    onExportHoloo = onExportHoloo,
                    onExportQuickBooks = onExportQuickBooks,
                    onExportXero = onExportXero,
                    showGlobalExports = showGlobalExports,
                    quickBooksLabel = quickBooksLabel,
                    xeroLabel = xeroLabel,
                    onClose = onClose,
                )
            }
        }
    }
}

/**
 * The run's dashboard: title with the live completion counter, the determinate bar,
 * and the cancel action while there is still anything to cancel.
 */
@Composable
private fun BatchHeaderCard(
    progress: DesktopBatchProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "پردازش گروهی فاکتورها",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "پردازش ${progress.completed} از ${progress.total} فاکتور",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!progress.isFinished) {
                    OutlinedButton(onClick = onCancel) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = CANCEL_LABEL, maxLines = 1)
                    }
                }
            }

            // Guarded: an empty batch reports 0/0, and the bar cannot draw NaN.
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) {
                        progress.completed / progress.total.toFloat()
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One dropped file: its name, where it stands, and what came of it.
 *
 * The verdict line is the whole point of the row — the invoice number on success, the
 * localized reason on failure — so a finished batch reads as its own report without
 * opening anything else.
 */
@Composable
private fun BatchItemRow(
    item: DesktopBatchItem,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BatchStatusBadge(status = item.status)
            }

            when (item.status) {
                BatchItemStatus.SUCCESS -> {
                    Text(
                        text = successCaption(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BatchItemStatus.FAILED -> {
                    Text(
                        text = item.errorMessage ?: UNKNOWN_ERROR_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BatchItemStatus.PENDING,
                BatchItemStatus.PROCESSING -> {
                    // Nothing to report yet: the badge above already says where it stands.
                }
            }
        }
    }
}

/**
 * The per-state pill: gray for the queue, blue for work in flight, emerald for a
 * landed invoice, red for a failure. Exhaustive over the enum, so a fifth state is a
 * compile error.
 */
@Composable
private fun BatchStatusBadge(
    status: BatchItemStatus,
    modifier: Modifier = Modifier,
) {
    val containerColor: Color
    val contentColor: Color
    val borderColor: Color
    val label: String
    when (status) {
        BatchItemStatus.PENDING -> {
            containerColor = StatusColors.slateContainer
            contentColor = StatusColors.onSlateContainer
            borderColor = StatusColors.hairlineBorder
            label = PENDING_LABEL
        }
        BatchItemStatus.PROCESSING -> {
            containerColor = ClayTheme.colors.badgeBlue.background
            contentColor = ClayTheme.colors.badgeBlue.text
            borderColor = ClayTheme.colors.badgeBlue.border
            label = PROCESSING_LABEL
        }
        BatchItemStatus.SUCCESS -> {
            containerColor = ClayTheme.colors.badgeGreen.background
            contentColor = ClayTheme.colors.badgeGreen.text
            borderColor = ClayTheme.colors.badgeGreen.border
            label = SUCCESS_LABEL
        }
        BatchItemStatus.FAILED -> {
            containerColor = ClayTheme.colors.badgeRed.background
            contentColor = ClayTheme.colors.badgeRed.text
            borderColor = ClayTheme.colors.badgeRed.border
            label = FAILED_LABEL
        }
    }

    Surface(
        modifier = modifier.border(
            width = 1.dp,
            color = borderColor,
            shape = RoundedCornerShape(50),
        ),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The finished run's verdict: green when everything extracted, red when anything did
 * not — because a summary that cannot be told apart from success is how partial
 * ledgers get trusted as complete ones.
 */
@Composable
private fun BatchSummaryCard(
    progress: DesktopBatchProgress,
    modifier: Modifier = Modifier,
) {
    val allSucceeded = progress.failureCount == 0
    val badge = if (allSucceeded) ClayTheme.colors.badgeGreen else ClayTheme.colors.badgeRed
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = badge.border,
                shape = RoundedCornerShape(16.dp),
            ),
        color = badge.background,
        contentColor = badge.text,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (allSucceeded) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = null,
            )
            Text(
                text = "${progress.successCount} موفق، ${progress.failureCount} ناموفق",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The finished run's exits: a modern bottom action bar with one visible button per
 * destination — the consolidated Excel ledger, the CSV ledger, and the two accounting
 * imports — then close-and-start-over.
 *
 * Exports stay disabled until at least one file succeeded — a zero-row ledger is a
 * confusing artifact, not a result.
 */
@Composable
private fun BatchActionsRow(
    hasSuccesses: Boolean,
    onExportExcel: () -> Unit,
    onExportCsv: () -> Unit,
    onExportSepidar: () -> Unit,
    onExportHoloo: () -> Unit,
    onExportQuickBooks: () -> Unit,
    onExportXero: () -> Unit,
    showGlobalExports: Boolean,
    quickBooksLabel: String,
    xeroLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onExportExcel,
                    enabled = hasSuccesses,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "خروجی اکسل تجمیعی (.xls)", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onExportSepidar,
                    enabled = hasSuccesses,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "خروجی سپیدار", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onExportHoloo,
                    enabled = hasSuccesses,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "خروجی هلو", maxLines = 1)
                }
            }
            if (showGlobalExports) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onExportQuickBooks,
                        enabled = hasSuccesses,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = quickBooksLabel, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onExportXero,
                        enabled = hasSuccesses,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = xeroLabel, maxLines = 1)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onExportCsv,
                    enabled = hasSuccesses,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "خروجی CSV تجمیعی", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = CLOSE_LABEL, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The success caption names the invoice and its landed amount, falling back gracefully
 * on missing fields — so a finished batch reads as its own ledger without opening
 * anything else.
 */
private fun successCaption(item: DesktopBatchItem): String {
    val invoice = item.invoice ?: return item.file.name
    val number = invoice.invoiceNumber?.ifBlank { null } ?: "—"
    val amount = "${AmountFormatter.formatToman(invoice.grandTotal)} ${invoice.currency.toPersianLabel()}"
    return "شماره فاکتور: $number • مبلغ: $amount"
}

private const val CANCEL_LABEL = "لغو"
private const val CLOSE_LABEL = "بستن / شروع دوباره"
private const val PENDING_LABEL = "در صف"
private const val PROCESSING_LABEL = "در حال استخراج..."
private const val SUCCESS_LABEL = "موفق"
private const val FAILED_LABEL = "خطا"
private const val UNKNOWN_ERROR_MESSAGE = "خطای ناشناخته هنگام پردازش فاکتور."
