package com.invoiceextract.desktop.presentation.ui.reconciliation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.invoiceextract.desktop.domain.reconciliation.ReconciliationMatch
import com.invoiceextract.desktop.domain.reconciliation.ReconciliationResult
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.openStatementFileDialog
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File

/**
 * Bank statement reconciliation: pick a CSV export, review the links the
 * matcher found, and settle them in one batch.
 *
 * A `DialogWindow` like the history archive (a separate window needs its own
 * direction provision), dressed in the clay surface: 28.dp corners, inner
 * highlight border and soft shadow, following the window language.
 *
 * Three states, all explicit: no file yet (picker + hint), unreadable file
 * (guidance, [parseFailed]), and a [result] with its summary banner, the
 * matched-deposit section and the still-open invoice section. Applying
 * settles through [onApplyMatched] and closes; the caller persists and its
 * archive re-emits, so the dialog never touches the store itself.
 *
 * Only core Compose icons are used (paid, close).
 *
 * @param result The latest matcher answer, or `null` before the first run.
 * @param parseFailed Whether the last chosen file refused to parse.
 * @param parentFrame The owning AWT window for the native file picker.
 * @param onReconcileFile Called with the picked statement file; the caller
 *   runs the parse off the render thread.
 * @param onApplyMatched Called with the matched links to settle, then the
 *   dialog closes onto the settled archive.
 */
@Composable
fun BankReconciliationDialog(
    result: ReconciliationResult?,
    parseFailed: Boolean,
    parentFrame: Frame,
    onReconcileFile: (File) -> Unit,
    onApplyMatched: (List<ReconciliationMatch>) -> Unit,
    onDismiss: () -> Unit,
    strings: AppStrings = appStrings(AppLanguage.FA),
    isEnglish: Boolean = false,
) {
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl,
    ) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = strings.reconcileTitle,
            resizable = false,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.reconcileTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                openStatementFileDialog(parentFrame)?.let(onReconcileFile)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = strings.reconcilePickFile)
                    }

                    when {
                        result != null -> ReconciliationReport(
                            result = result,
                            strings = strings,
                            onApplyMatched = {
                                onApplyMatched(result.matched)
                                onDismiss()
                            },
                        )
                        parseFailed -> Text(
                            text = strings.reconcileParseError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ClayTheme.colors.badgeRed.text,
                        )
                        else -> Text(
                            text = strings.reconcileChooseHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The matcher answer: a three-stat banner, the linked deposits with their
 * full-confidence badges and tracking numbers, the still-open invoices, and
 * the one-tap settle action. Sections scroll as one column so the apply
 * button stays reachable without tab juggling.
 */
@Composable
private fun ReconciliationReport(
    result: ReconciliationResult,
    strings: AppStrings,
    onApplyMatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(28.dp), clip = false)
                .border(
                    width = ClayTheme.colors.clayBorderWidth,
                    color = ClayTheme.colors.clayBorderHighlight,
                    shape = RoundedCornerShape(28.dp),
                ),
            color = ClayTheme.colors.claySurface,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SummaryStat(
                    value = result.matched.size.toString(),
                    label = strings.reconcileMatchedCount,
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    value = AmountFormatter.formatToman(result.totalMatchedAmount.toDouble()),
                    label = strings.reconcileTotalMatched,
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    value = result.unmatchedInvoices.size.toString(),
                    label = strings.reconcileUnmatchedCount,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "matched-header") {
                Text(
                    text = strings.reconcileMatchedTab,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            items(result.matched, key = { it.invoice.id }) { match ->
                MatchedRow(match = match, strings = strings)
            }
            item(key = "unmatched-header") {
                Text(
                    text = strings.reconcileUnmatchedTab,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(result.unmatchedInvoices, key = { it.id }) { invoice ->
                UnmatchedRow(invoice = invoice, strings = strings)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onApplyMatched,
                enabled = result.matched.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ClayTheme.colors.badgeGreen.background,
                    contentColor = ClayTheme.colors.badgeGreen.text,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null)
                Text(text = strings.reconcileApplyMatched)
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One linked deposit: who paid, how much, the bank trail, full confidence. */
@Composable
private fun MatchedRow(match: ReconciliationMatch, strings: AppStrings) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.invoice.clientName?.trim()?.takeIf { it.isNotBlank() }
                        ?: match.invoice.sellerName?.trim()?.takeIf { it.isNotBlank() }
                        ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        "${AmountFormatter.formatToman(match.invoice.effectiveTomanTotal.toDouble())} " +
                            strings.currencyToman,
                        match.transaction.trackingNumber?.takeIf { it.isNotBlank() }
                            ?.let { "${strings.trackingNumberLabel}: $it" },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                color = ClayTheme.colors.badgeGreen.background,
                contentColor = ClayTheme.colors.badgeGreen.text,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = strings.matchBadge100,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** One still-open invoice: who owes, how much, and how urgently flagged. */
@Composable
private fun UnmatchedRow(invoice: Invoice, strings: AppStrings) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = invoice.clientName?.trim()?.takeIf { it.isNotBlank() }
                        ?: invoice.sellerName?.trim()?.takeIf { it.isNotBlank() }
                        ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${AmountFormatter.formatToman(invoice.effectiveTomanTotal.toDouble())} " +
                        strings.currencyToman,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val overdue = invoice.paymentStatus == PaymentStatus.OVERDUE
            Surface(
                color = if (overdue) {
                    ClayTheme.colors.badgeRed.background
                } else {
                    ClayTheme.colors.badgeAmber.background
                },
                contentColor = if (overdue) {
                    ClayTheme.colors.badgeRed.text
                } else {
                    ClayTheme.colors.badgeAmber.text
                },
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = if (overdue) strings.paymentOverdue else strings.paymentPending,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private val DIALOG_WIDTH = 750.dp
private val DIALOG_HEIGHT = 700.dp
