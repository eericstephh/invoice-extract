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
import com.invoiceextract.desktop.domain.analytics.PettyCashSettlement
import com.invoiceextract.desktop.domain.analytics.SettlementStatus
import com.invoiceextract.desktop.domain.analytics.computePettyCashSettlement
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Frame
import java.io.File
import kotlin.math.absoluteValue

/**
 * The campaign petty-cash settler (تسویه تنخواه پروژه و کمپین).
 *
 * A claymorphic fixed-size window over the archive: the user names the client
 * and project (typed or picked from the archive's own tags), types the advance
 * received, and watches the settlement reconcile live — advance, attached
 * expenses at their Toman equivalents, and the surplus/deficit balance in its
 * verdict color. One click exports the formal settlement sheet.
 *
 * Only core Material icons are used (close); nothing here needs the extended
 * set the build deliberately stays off.
 *
 * @param invoices The store's current content; filtered locally, never mutated.
 * @param parentFrame The underlying AWT window, used as the save dialog's parent.
 * @param onDismiss Called when the dialog is closed by any path.
 * @param onExport Called with the dialog-chosen target file, the client and
 *   project names, the previewed settlement and the filtered invoices. Owns
 *   the whole save-dialog hand-off.
 */
@Composable
fun PettyCashDialog(
    invoices: List<Invoice>,
    parentFrame: Frame,
    onDismiss: () -> Unit,
    onExport: (
        targetFile: File,
        clientName: String,
        projectName: String,
        settlement: PettyCashSettlement,
        invoices: List<Invoice>,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var clientName by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var advanceText by remember { mutableStateOf("") }

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

    // Blank means "all": the settlement narrows only on what the user named.
    val filtered = remember(clientName, projectName, invoices) {
        invoices.filter { invoice ->
            (clientName.isBlank() || invoice.clientName?.contains(clientName.trim(), ignoreCase = true) == true) &&
                (projectName.isBlank() || invoice.projectName?.contains(projectName.trim(), ignoreCase = true) == true)
        }
    }

    val advanceAmount = parseAdvance(advanceText)
    val settlement = remember(filtered, advanceAmount) {
        computePettyCashSettlement(advanceAmount ?: 0L, filtered)
    }

    val canExport = filtered.isNotEmpty() &&
        clientName.trim().isNotBlank() &&
        projectName.trim().isNotBlank() &&
        advanceAmount != null

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        DialogWindow(
            onCloseRequest = onDismiss,
            state = rememberDialogState(size = DpSize(DIALOG_WIDTH, DIALOG_HEIGHT)),
            title = SETTLEMENT_TITLE,
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
                            text = SETTLEMENT_TITLE,
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
                    SuggestionChips(
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
                    SuggestionChips(
                        suggestions = knownProjects,
                        onPick = { projectName = it },
                    )

                    OutlinedTextField(
                        value = advanceText,
                        onValueChange = { advanceText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = ADVANCE_LABEL) },
                        placeholder = { Text(text = ADVANCE_PLACEHOLDER) },
                        singleLine = true,
                        isError = advanceAmount == null,
                        colors = clayTextFieldColors(ClayTheme.colors),
                        supportingText = {
                            if (advanceAmount == null) {
                                Text(text = ADVANCE_ERROR)
                            }
                        },
                    )

                    SettlementStatusCard(settlement = settlement)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (!canExport) return@Button
                                val snapshot = filtered.toList()
                                val settled = settlement
                                val client = clientName.trim()
                                val project = projectName.trim()
                                scope.launch(Dispatchers.IO) {
                                    saveFileDialog(parentFrame, DEFAULT_SETTLEMENT_NAME, EXCEL_EXTENSION)
                                        ?.let { target -> onExport(target, client, project, settled, snapshot) }
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
private fun SuggestionChips(
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

/**
 * The live settlement verdict: advance beside expenses, the final balance in
 * its verdict color, and the status badge beneath — green surplus to return,
 * orange deficit owed to the holder, blue for the exact zero.
 */
@Composable
private fun SettlementStatusCard(
    settlement: PettyCashSettlement,
    modifier: Modifier = Modifier,
) {
    val containerColor: Color
    val contentColor: Color
    val badge: String
    when (settlement.status) {
        SettlementStatus.SURPLUS -> {
            containerColor = ClayTheme.colors.badgeGreen.background
            contentColor = ClayTheme.colors.badgeGreen.text
            badge = SURPLUS_BADGE
        }
        SettlementStatus.DEFICIT -> {
            containerColor = ClayTheme.colors.badgeAmber.background
            contentColor = ClayTheme.colors.badgeAmber.text
            badge = DEFICIT_BADGE
        }
        SettlementStatus.BALANCED -> {
            containerColor = ClayTheme.colors.badgeBlue.background
            contentColor = ClayTheme.colors.badgeBlue.text
            badge = BALANCED_BADGE
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SettlementRow(
                label = ADVANCE_ROW_LABEL,
                amount = settlement.advanceAmount,
            )
            SettlementRow(
                label = EXPENSES_ROW_LABEL,
                amount = settlement.totalExpenses,
            )
            HorizontalDivider(color = contentColor.copy(alpha = 0.3f))
            SettlementRow(
                label = BALANCE_ROW_LABEL,
                amount = settlement.balance.absoluteValue,
                bold = true,
            )
            Text(
                text = badge,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SettlementRow(
    label: String,
    amount: Long,
    bold: Boolean = false,
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
            text = "${AmountFormatter.formatToman(amount.toDouble())} تومان",
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Parses the advance field leniently: Persian/Arabic-Indic digits unify to
 * ASCII and grouping separators (`,`, `٬`, space) drop out, so "۲,۵۰۰,۰۰۰"
 * reads as `2500000`. Returns `null` when the field holds no finite,
 * non-negative number.
 */
private fun parseAdvance(raw: String): Long? {
    val normalized = buildString(raw.length) {
        for (c in raw.trim()) {
            when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                ',', '٬', ' ' -> append("")
                else -> append(c)
            }
        }
    }.trim()
    if (normalized.isEmpty()) return null
    // Whole Toman only: decimals have no meaning without a subunit, and a
    // fractional keystroke mid-typing must not silently truncate into money.
    if (normalized.any { it !in '0'..'9' }) return null
    return normalized.toLongOrNull()
}

private const val SETTLEMENT_TITLE = "تسویه تنخواه پروژه و کمپین"
private const val CLIENT_LABEL = "نام کارفرما"
private const val CLIENT_PLACEHOLDER = "مثلاً: شرکت آریا"
private const val PROJECT_LABEL = "نام پروژه / کمپین"
private const val PROJECT_PLACEHOLDER = "مثلاً: کمپین تابستان"
private const val ADVANCE_LABEL = "مبلغ تنخواه اولیه دریافتی (تومان)"
private const val ADVANCE_PLACEHOLDER = "مثلاً: 2,500,000"
private const val ADVANCE_ERROR = "یک مبلغ معتبر وارد کنید (مثلاً 2500000)"
private const val ADVANCE_ROW_LABEL = "تنخواه اولیه"
private const val EXPENSES_ROW_LABEL = "جمع فاکتورها"
private const val BALANCE_ROW_LABEL = "مانده نهایی"
private const val SURPLUS_BADGE = "مازاد تنخواه (باید عودت شود)"
private const val DEFICIT_BADGE = "کسری تنخواه (طلب شما)"
private const val BALANCED_BADGE = "متعادل — تسویه کامل، بدون مانده"
private const val EXPORT_LABEL = "خروجی فرم تسویه تنخواه (.xls)"
private const val CANCEL_LABEL = "انصراف / بستن"
private const val DEFAULT_SETTLEMENT_NAME = "petty-cash-settlement.xls"
private const val EXCEL_EXTENSION = "xls"

private const val MAX_SUGGESTIONS = 8

private val DIALOG_WIDTH = 700.dp
private val DIALOG_HEIGHT = 700.dp
