package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.data.backup.BackupResult
import com.invoiceextract.desktop.data.backup.RestoreResult
import com.invoiceextract.desktop.data.export.ClientStatementConfig
import com.invoiceextract.desktop.domain.analytics.PettyCashSettlement
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors
import com.invoiceextract.desktop.domain.reconciliation.ReconciliationMatch
import com.invoiceextract.desktop.domain.reconciliation.ReconciliationResult
import com.invoiceextract.desktop.presentation.ui.reminders.InvoiceFollowUpDialog
import com.invoiceextract.desktop.presentation.ui.reconciliation.BankReconciliationDialog
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.model.isForeignCurrency
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File

/**
 * The invoice archive: every record the user has kept, searchable and actionable.
 *
 * A fixed-size window over the persistent store — large enough to scan a history (850×650)
 * without covering the invoice underneath entirely. Search filters in real time across the
 * three fields a user remembers an invoice by (seller, number, date); each surviving row
 * is a card that can be reopened for editing, exported on its own, or deleted.
 *
 * **Why the dialog owns the export file picker.** A native save dialog needs the window's
 * AWT frame as parent and must run off the render thread, exactly like the main screen's
 * export flow. The dialog therefore runs [saveFileDialog] itself on IO and hands the
 * chosen target to [onExportExcel] together with the row's invoice — the screen and the
 * view model never see a dialog, only a file.
 *
 * Only core Material icons are used (close, search, delete); nothing here needs the
 * extended set the build deliberately stays off.
 *
 * @param invoices The store's current content, newest first; re-collected by the caller.
 * @param parentFrame The underlying AWT window, used as the save dialog's parent.
 * @param onDismiss Called when the dialog is closed by any path.
 * @param onSelect Called with the row's invoice when the user asks to view and edit it.
 * @param onDelete Called with the row's id when the user deletes it.
 * @param onUpdateInvoice Called with the paid copy when the follow-up dialog's
 *   "mark as paid" lands; the caller persists it and the archive re-emits.
 * @param onExportExcel Called with the row's invoice and the dialog-chosen target file.
 * @param onExportSepidar / [onExportHoloo] Same contract for the accounting import
 *   sheets, offered through the card's export dropdown menu.
 * @param onExportClientStatement Called with the dialog-chosen target file, the
 *   statement config and the filtered invoices when the client-statement flow
 *   exports. Owns the whole save-dialog hand-off from the statement dialog.
 * @param onExportPettyCashSettlement Called with the dialog-chosen target file,
 *   the client and project names, the previewed settlement and the filtered
 *   invoices when the petty-cash flow exports. Owns the whole save-dialog
 *   hand-off from the settlement dialog.
 */
@Composable
fun HistoryDialog(
    invoices: List<Invoice>,
    parentFrame: Frame,
    onDismiss: () -> Unit,
    onSelect: (Invoice) -> Unit,
    onDelete: (String) -> Unit,
    onUpdateInvoice: (Invoice) -> Unit = {},
    reconciliation: ReconciliationResult? = null,
    reconciliationFailed: Boolean = false,
    onReconcileFile: (File) -> Unit = {},
    onApplyMatched: (List<ReconciliationMatch>) -> Unit = {},
    onExportExcel: (Invoice, File) -> Unit,
    onExportSepidar: (Invoice, File) -> Unit,
    onExportHoloo: (Invoice, File) -> Unit,
    onExportQuickBooks: (Invoice, File) -> Unit = { _, _ -> },
    onExportXero: (Invoice, File) -> Unit = { _, _ -> },
    onExportClientStatement: (File, ClientStatementConfig, List<Invoice>) -> Unit,
    onExportPettyCashSettlement: (
        File,
        String,
        String,
        PettyCashSettlement,
        List<Invoice>,
    ) -> Unit,
    mappingCount: Int = 0,
    onCreateBackup: suspend (targetFile: File) -> Result<BackupResult>,
    onRestoreBackup: suspend (sourceFile: File) -> Result<RestoreResult>,
    onWatcherClick: () -> Unit = {},
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var showStatementDialog by remember { mutableStateOf(false) }
    var showPettyCashDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showReconcileDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Real-time filtering across the identifying fields — seller, number, date, and
    // the agency tags (client, project); a blank query shows everything.
    // `remember` on both inputs so typing filters without re-reading the store.
    val filtered = remember(query, invoices) {
        if (query.isBlank()) {
            invoices
        } else {
            invoices.filter { invoice ->
                invoice.sellerName?.contains(query, ignoreCase = true) == true ||
                    invoice.invoiceNumber?.contains(query, ignoreCase = true) == true ||
                    invoice.date?.contains(query, ignoreCase = true) == true ||
                    invoice.clientName?.contains(query, ignoreCase = true) == true ||
                    invoice.projectName?.contains(query, ignoreCase = true) == true
            }
        }
    }

    // A DialogWindow opens a separate window, so the app-wide direction provision
    // does not reach it; it is re-provided here explicitly, following the window
    // language like the main screen does.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.historyDialogTitle,
            resizable = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.historyDialogTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showStatementDialog = true }) {
                            Text(text = strings.btnClientStatement, maxLines = 1)
                        }
                        TextButton(onClick = { showPettyCashDialog = true }) {
                            Text(text = strings.btnPettyCash, maxLines = 1)
                        }
                        TextButton(onClick = { showBackupDialog = true }) {
                            Text(text = strings.btnBackupRestore, maxLines = 1)
                        }
                        TextButton(onClick = { showReconcileDialog = true }) {
                            Text(text = strings.reconcileBankButton, maxLines = 1)
                        }
                        TextButton(onClick = onWatcherClick) {
                            Text(text = strings.hotFolderTitle, maxLines = 1)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        placeholder = { Text(text = strings.searchPlaceholder) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                        },
                        singleLine = true,
                        colors = clayTextFieldColors(ClayTheme.colors),
                    )

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = strings.emptyHistory,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(filtered, key = { it.id }) { invoice ->
                                // Each format suggests its own default filename; the dialog
                                // still owns the native picker, the caller only sees files.
                                // CSV formats carry their own extension so the picker
                                // does not append `.xls` to them.
                                val exportWithName: (String, (Invoice, File) -> Unit) -> Unit =
                                    { defaultName, action ->
                                        scope.launch(Dispatchers.IO) {
                                            val extension = defaultName.substringAfterLast('.', "xls")
                                            saveFileDialog(parentFrame, defaultName, extension)
                                                ?.let { target -> action(invoice, target) }
                                        }
                                    }
                                HistoryInvoiceCard(
                                    invoice = invoice,
                                    strings = strings,
                                    isEnglish = isEnglish,
                                    onSelect = {
                                        // The fix: the record reaches the editor AND the
                                        // archive closes, so one tap always lands visible.
                                        onSelect(invoice)
                                        onDismiss()
                                    },
                                    onExportExcel = { exportWithName(DEFAULT_EXCEL_NAME, onExportExcel) },
                                    onExportSepidar = { exportWithName(SEPIDAR_EXCEL_NAME, onExportSepidar) },
                                    onExportHoloo = { exportWithName(HOLOO_EXCEL_NAME, onExportHoloo) },
                                    onExportQuickBooks = {
                                        exportWithName(QUICKBOOKS_CSV_NAME, onExportQuickBooks)
                                    },
                                    onExportXero = {
                                        exportWithName(XERO_CSV_NAME, onExportXero)
                                    },
                                    onDelete = { onDelete(invoice.id) },
                                    onUpdateInvoice = onUpdateInvoice,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sibling windows, not nested: the statement and settlement flows filter
        // the same archive without replacing the history the user is browsing.
        if (showStatementDialog) {
            ClientStatementDialog(
                invoices = invoices,
                parentFrame = parentFrame,
                onDismiss = { showStatementDialog = false },
                onExport = onExportClientStatement,
            )
        }
        if (showPettyCashDialog) {
            PettyCashDialog(
                invoices = invoices,
                parentFrame = parentFrame,
                onDismiss = { showPettyCashDialog = false },
                onExport = onExportPettyCashSettlement,
            )
        }
        if (showBackupDialog) {
            BackupRestoreDialog(
                invoiceCount = invoices.size,
                mappingCount = mappingCount,
                parentFrame = parentFrame,
                onDismiss = { showBackupDialog = false },
                onCreateBackup = onCreateBackup,
                onRestoreBackup = onRestoreBackup,
                strings = strings,
                isEnglish = isEnglish,
            )
        }
        if (showReconcileDialog) {
            BankReconciliationDialog(
                result = reconciliation,
                parseFailed = reconciliationFailed,
                parentFrame = parentFrame,
                onReconcileFile = onReconcileFile,
                onApplyMatched = { matches ->
                    onApplyMatched(matches)
                    showReconcileDialog = false
                },
                onDismiss = { showReconcileDialog = false },
                strings = strings,
                isEnglish = isEnglish,
            )
        }
    }
}

/**
 * One archived invoice: who it is from, what it totals, whether it reconciles — and the
 * three things the user can do with it.
 */
@Composable
private fun HistoryInvoiceCard(
    invoice: Invoice,
    strings: AppStrings,
    onSelect: () -> Unit,
    onExportExcel: () -> Unit,
    onExportSepidar: () -> Unit,
    onExportHoloo: () -> Unit,
    onExportQuickBooks: () -> Unit,
    onExportXero: () -> Unit,
    onDelete: () -> Unit,
    onUpdateInvoice: (Invoice) -> Unit,
    isEnglish: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var followUpInvoice by remember { mutableStateOf<Invoice?>(null) }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = invoice.sellerName?.ifBlank { null } ?: UNKNOWN_SELLER,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                HistoryFieldCaption(text = invoice.date?.ifBlank { null } ?: NOT_RECORDED)
                HistoryFieldCaption(
                    text = invoice.invoiceNumber?.ifBlank { null } ?: NOT_RECORDED,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = archiveAmountText(invoice, strings),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ItemCountBadge(count = invoice.items.size, strings = strings)
                ClientProjectTag(
                    clientName = invoice.clientName,
                    projectName = invoice.projectName,
                )
                Spacer(modifier = Modifier.weight(1f))
                PaymentStatusBadge(status = invoice.paymentStatus, strings = strings)
                ValidationChip(status = invoice.validationStatus, strings = strings)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onSelect) {
                    Text(text = strings.btnViewEdit)
                }
                // Unpaid rows carry their own follow-up entry: the copilot opens
                // over the archive and closes back onto it.
                if (invoice.paymentStatus != PaymentStatus.PAID) {
                    TextButton(onClick = { followUpInvoice = invoice }) {
                        Icon(
                            imageVector = Icons.Filled.Email,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(text = strings.remindPayment)
                    }
                }
                // The card's export slot is a format menu, like the main export rows:
                // standard sheet, the two accounting imports, and — in global
                // (English) mode — the QuickBooks/Xero CSVs.
                var exportMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { exportMenuExpanded = true }) {
                        Text(text = strings.btnExportExcel)
                    }
                    ExportFormatMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false },
                        onStandard = onExportExcel,
                        onSepidar = onExportSepidar,
                        onHoloo = onExportHoloo,
                        showGlobal = isEnglish,
                        onQuickBooks = onExportQuickBooks,
                        onXero = onExportXero,
                        quickBooksLabel = strings.exportQuickBooks,
                        xeroLabel = strings.exportXero,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = ClayTheme.colors.badgeRed.text,
                    )
                }
            }
        }
    }

    followUpInvoice?.let { target ->
        InvoiceFollowUpDialog(
            invoice = target,
            onDismiss = { followUpInvoice = null },
            onMarkPaid = { paid ->
                onUpdateInvoice(paid)
                followUpInvoice = null
            },
            strings = strings,
            isEnglish = isEnglish,
        )
    }
}

/**
 * The collection state as a pill: green paid, amber pending, red overdue —
 * the same semantic washes the metadata card's chips wear.
 */
@Composable
private fun PaymentStatusBadge(
    status: PaymentStatus,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    val containerColor: Color
    val contentColor: Color
    val label: String
    when (status) {
        PaymentStatus.PAID -> {
            containerColor = ClayTheme.colors.badgeGreen.background
            contentColor = ClayTheme.colors.badgeGreen.text
            label = strings.paymentPaid
        }
        PaymentStatus.PENDING -> {
            containerColor = ClayTheme.colors.badgeAmber.background
            contentColor = ClayTheme.colors.badgeAmber.text
            label = strings.paymentPending
        }
        PaymentStatus.OVERDUE -> {
            containerColor = ClayTheme.colors.badgeRed.background
            contentColor = ClayTheme.colors.badgeRed.text
            label = strings.paymentOverdue
        }
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** A dimmed secondary caption (date, number) beside the seller name. */
@Composable
private fun HistoryFieldCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The line count as a pill, in the window language. */
@Composable
private fun ItemCountBadge(
    count: Int,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = strings.itemsCount(count),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The agency attribution as a pill: client and project joined with a dash, each side
 * dropped when blank so a half-tagged invoice never shows a dangling separator.
 * Renders nothing when the invoice carries neither tag.
 */
@Composable
private fun ClientProjectTag(
    clientName: String?,
    projectName: String?,
    modifier: Modifier = Modifier,
) {
    val parts = listOfNotNull(
        clientName?.trim()?.takeIf { it.isNotBlank() },
        projectName?.trim()?.takeIf { it.isNotBlank() },
    )
    if (parts.isEmpty()) return

    Surface(
        modifier = modifier,
        color = StatusColors.indigoContainer,
        contentColor = StatusColors.onIndigoContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = "📁 " + parts.joinToString(" - "),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The stored verdict as a pill. Exhaustive over the sealed status, so a fourth verdict
 * is a compile error here until it gets a color — the same rule the banner follows.
 */
@Composable
private fun ValidationChip(
    status: ValidationStatus,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    val containerColor: Color
    val contentColor: Color
    val label: String
    when (status) {
        ValidationStatus.Valid -> {
            containerColor = ClayTheme.colors.badgeGreen.background
            contentColor = ClayTheme.colors.badgeGreen.text
            label = strings.statusValid
        }
        is ValidationStatus.Warning -> {
            containerColor = ClayTheme.colors.badgeAmber.background
            contentColor = ClayTheme.colors.badgeAmber.text
            label = strings.statusNeedsReview
        }
        is ValidationStatus.Invalid -> {
            containerColor = ClayTheme.colors.badgeRed.background
            contentColor = ClayTheme.colors.badgeRed.text
            label = strings.statusInvalid
        }
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The archive row's amount: local money as printed with its unit, foreign money
 * as its Toman equivalent (the unit every ledger settles in), so a dollar
 * receipt never masquerades as Toman.
 */
private fun archiveAmountText(invoice: Invoice, strings: AppStrings): String =
    if (invoice.currency.isForeignCurrency) {
        "${AmountFormatter.formatToman(invoice.effectiveTomanTotal.toDouble())} ${strings.currencyToman}"
    } else if (invoice.currency == CurrencyType.RIAL) {
        "${AmountFormatter.formatToman(invoice.grandTotal)} ${strings.currencyRial}"
    } else {
        "${AmountFormatter.formatToman(invoice.grandTotal)} ${strings.currencyToman}"
    }

private const val UNKNOWN_SELLER = "نامشخص"
private const val NOT_RECORDED = "—"

private const val DEFAULT_EXCEL_NAME = "invoice.xls"
private const val SEPIDAR_EXCEL_NAME = "sepidar.xls"
private const val HOLOO_EXCEL_NAME = "holoo.xls"
private const val QUICKBOOKS_CSV_NAME = "quickbooks.csv"
private const val XERO_CSV_NAME = "xero.csv"

private val DIALOG_WIDTH = 850.dp
private val DIALOG_HEIGHT = 650.dp
