package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.data.export.ClientStatementConfig
import com.invoiceextract.desktop.data.export.DesktopClientStatementExportManager
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File

/**
 * The agency client-statement issuer (صدور صورت‌وضعیت پروژه با کارمزد آژانس).
 *
 * A claymorphic fixed-size window over the archive: the user names the client
 * and project (typed or picked from the archive's own tags), the dialog
 * auto-filters the invoice list to that pair, shows the direct-cost stats,
 * and live-previews the agency markup math while the fee percent is typed.
 * One click exports the filtered set as a standalone RTL statement sheet.
 *
 * Only core Material icons are used (close); nothing here needs the extended
 * set the build deliberately stays off.
 *
 * @param invoices The store's current content; filtered locally, never mutated.
 * @param parentFrame The underlying AWT window, used as the save dialog's parent.
 * @param onDismiss Called when the dialog is closed by any path.
 * @param onExport Called with the dialog-chosen target file, the statement
 *   config and the filtered invoices. Owns the whole save-dialog hand-off.
 */
@Composable
fun ClientStatementDialog(
    invoices: List<Invoice>,
    parentFrame: Frame,
    onDismiss: () -> Unit,
    onExport: (targetFile: File, config: ClientStatementConfig, invoices: List<Invoice>) -> Unit,
) {
    val manager = remember { DesktopClientStatementExportManager() }
    val scope = rememberCoroutineScope()

    var clientName by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf(DEFAULT_FEE_TEXT) }

    // The archive's own tags, newest attribution first, so the fields are one
    // tap away instead of retyped from memory.
    val knownClients = remember(invoices) {
        invoices.mapNotNull { it.clientName?.trim()?.takeIf { name -> name.isNotBlank() } }
            .distinct()
            .take(MAX_SUGGESTIONS)
    }
    val knownProjects = remember(invoices) {
        invoices.mapNotNull { it.projectName?.trim()?.takeIf { name -> name.isNotBlank() } }
            .distinct()
            .take(MAX_SUGGESTIONS)
    }

    // Blank means "all": the statement narrows only on what the user named.
    val filtered = remember(clientName, projectName, invoices) {
        invoices.filter { invoice ->
            (clientName.isBlank() || invoice.clientName?.contains(clientName.trim(), ignoreCase = true) == true) &&
                (projectName.isBlank() || invoice.projectName?.contains(projectName.trim(), ignoreCase = true) == true)
        }
    }

    val feePercent = parseFeePercent(feeText)
    val totals = remember(filtered, feePercent) {
        manager.computeTotals(filtered, feePercent ?: 0.0)
    }

    val canExport = filtered.isNotEmpty() &&
        clientName.trim().isNotBlank() &&
        projectName.trim().isNotBlank() &&
        feePercent != null

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = STATEMENT_TITLE,
            resizable = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = STATEMENT_TITLE,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    OutlinedTextField(
                        value = clientName,
                        onValueChange = { clientName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = CLIENT_LABEL) },
                        placeholder = { Text(text = CLIENT_PLACEHOLDER) },
                        singleLine = true,
                        colors = clayTextFieldColors(ClayTheme.colors),
                    )
                    SuggestionRow(
                        suggestions = knownClients,
                        onPick = { clientName = it },
                    )

                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = PROJECT_LABEL) },
                        placeholder = { Text(text = PROJECT_PLACEHOLDER) },
                        singleLine = true,
                        colors = clayTextFieldColors(ClayTheme.colors),
                    )
                    SuggestionRow(
                        suggestions = knownProjects,
                        onPick = { projectName = it },
                    )

                    StatementStatsCard(
                        invoiceCount = filtered.size,
                        directTotal = totals.subtotal,
                    )

                    OutlinedTextField(
                        value = feeText,
                        onValueChange = { feeText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = FEE_LABEL) },
                        placeholder = { Text(text = FEE_PLACEHOLDER) },
                        singleLine = true,
                        isError = feePercent == null,
                        colors = clayTextFieldColors(ClayTheme.colors),
                        supportingText = {
                            if (feePercent == null) {
                                Text(text = FEE_ERROR)
                            }
                        },
                    )

                    StatementPreview(
                        directTotal = totals.subtotal,
                        feeAmount = totals.feeAmount,
                        grandTotal = totals.grandTotal,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (!canExport) return@Button
                                val config = ClientStatementConfig(
                                    clientName = clientName.trim(),
                                    projectName = projectName.trim(),
                                    agencyFeePercent = feePercent ?: 0.0,
                                )
                                val snapshot = filtered.toList()
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, DEFAULT_STATEMENT_NAME, EXCEL_EXTENSION)
                                        ?.let { target -> onExport(target, config, snapshot) }
                                }
                                onDismiss()
                            },
                            enabled = canExport,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = EXPORT_LABEL, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = CANCEL_LABEL, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One-tap tag picks from the archive: a horizontal chip row, or nothing when
 * the archive carries no such tags yet.
 */
@Composable
private fun SuggestionRow(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(suggestions, key = { it }) { suggestion ->
            Surface(
                onClick = { onPick(suggestion) },
                shape = RoundedCornerShape(50),
                tonalElevation = 1.dp,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(50),
                ),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** The filtered-set stats: invoice count beside the direct-cost sum. */
@Composable
private fun StatementStatsCard(
    invoiceCount: Int,
    directTotal: Double,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = COUNT_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = invoiceCount.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = DIRECT_TOTAL_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${AmountFormatter.formatToman(directTotal)} تومان",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** The live markup preview: direct cost, agency fee, and settlement total. */
@Composable
private fun StatementPreview(
    directTotal: Double,
    feeAmount: Double,
    grandTotal: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PreviewRow(label = PREVIEW_DIRECT_LABEL, amount = directTotal, bold = false)
        PreviewRow(label = PREVIEW_FEE_LABEL, amount = feeAmount, bold = false)
        HorizontalDivider()
        PreviewRow(label = PREVIEW_GRAND_LABEL, amount = grandTotal, bold = true)
    }
}

@Composable
private fun PreviewRow(
    label: String,
    amount: Double,
    bold: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = "${AmountFormatter.formatToman(amount)} تومان",
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Parses the fee field leniently: Persian/Arabic-Indic digits and the Arabic
 * decimal separator are normalized first, so "۱۵٫۵" reads as `15.5`.
 * Returns `null` when the field holds no parseable number.
 */
private fun parseFeePercent(raw: String): Double? {
    val normalized = buildString(raw.length) {
        for (c in raw.trim()) {
            when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                '٫' -> append('.')
                ',', '٬' -> append("")
                else -> append(c)
            }
        }
    }.trim()
    if (normalized.isEmpty()) return null
    val parsed = normalized.toDoubleOrNull() ?: return null
    return if (parsed.isFinite() && parsed >= 0.0) parsed else null
}

private const val STATEMENT_TITLE = "صدور صورت‌وضعیت پروژه (کارفرما)"
private const val CLIENT_LABEL = "نام کارفرما"
private const val CLIENT_PLACEHOLDER = "مثلاً: شرکت آریا"
private const val PROJECT_LABEL = "نام پروژه"
private const val PROJECT_PLACEHOLDER = "مثلاً: کمپین تابستان"
private const val FEE_LABEL = "درصد کارمزد آژانس (٪)"
private const val FEE_PLACEHOLDER = "مثلاً: 15"
private const val FEE_ERROR = "یک عدد معتبر وارد کنید (مثلاً 15)"
private const val COUNT_LABEL = "تعداد فاکتورها"
private const val DIRECT_TOTAL_LABEL = "جمع کل مخارج مستقیم"
private const val PREVIEW_DIRECT_LABEL = "هزینه مستقیم"
private const val PREVIEW_FEE_LABEL = "مبلغ کارمزد آژانس"
private const val PREVIEW_GRAND_LABEL = "جمع کل نهایی تسویه"
private const val EXPORT_LABEL = "خروجی اکسل صورت‌وضعیت (.xls)"
private const val CANCEL_LABEL = "انصراف / بستن"
private const val DEFAULT_FEE_TEXT = "15"
private const val DEFAULT_STATEMENT_NAME = "client-statement.xls"
private const val EXCEL_EXTENSION = "xls"

private const val MAX_SUGGESTIONS = 8

private val DIALOG_WIDTH = 700.dp
private val DIALOG_HEIGHT = 680.dp
