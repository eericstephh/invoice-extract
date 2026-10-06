package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.toPersianLabel
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.isForeignCurrency

/**
 * The summary block of the invoice and its export actions.
 *
 * Totals are shown grouped in the invoice's own currency, never converted: the model
 * reports Toman or Rial as printed, and a desktop app that silently switched currency
 * would change the meaning of every number on screen. Foreign money additionally
 * states its Toman equivalent outright, since every ledger downstream settles in Toman.
 *
 * **Why exports are callbacks.** The card does not open the save dialog itself: a native
 * dialog needs the window's AWT frame as parent and must be shown off the render thread,
 * both of which the screen decides. Each button hands the click back through
 * [onExportExcel] / [onExportCsv], and the screen runs [saveFileDialog] on a background
 * coroutine and forwards the chosen target to the view model. [onReset] clears the screen
 * for the next scan.
 *
 * @param onExportExcel / [onExportCsv] Called when the user asks for that output; the view
 *   model owns the stream and reports the outcome separately, so these never throw.
 * @param onExportSepidar / [onExportHoloo] Same contract for the accounting import
 *   sheets; offered through the Excel button's dropdown menu.
 * @param onExportQuickBooks / [onExportXero] Same contract for the global CSV
 *   imports; hidden unless [showGlobalExports].
 * @param onSave Called to persist the invoice into the local history.
 * @param isSaved / [isDirty] Decide the save button's state: it is offered while the
 *   on-screen record differs from the store and confirms once they match.
 * @param onReset Called to discard the invoice and return to the drop zone.
 * @param onPrintFormal Called to render the formal A4 tax sheet and open it
 *   print-ready; the screen owns the save dialog and the browser hand-off.
 */
@Composable
fun TotalsCard(
    invoice: Invoice,
    onExportExcel: () -> Unit,
    onExportCsv: () -> Unit,
    onExportSepidar: () -> Unit,
    onExportHoloo: () -> Unit,
    onExportQuickBooks: () -> Unit = {},
    onExportXero: () -> Unit = {},
    showGlobalExports: Boolean = false,
    onSave: () -> Unit,
    isSaved: Boolean,
    isDirty: Boolean,
    onReset: () -> Unit,
    onPrintFormal: () -> Unit = {},
    strings: AppStrings = appStrings(AppLanguage.FA),
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "جمع‌بندی مبالغ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SummaryRow(label = "جمع جزء", amount = invoice.subtotal)
            SummaryRow(label = "کل مالیات", amount = invoice.totalTax)
            SummaryRow(label = "کل تخفیف", amount = invoice.totalDiscount)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // The payable amount: a soft badge tile with display-scale type, so the
            // conclusion of the card reads as the conclusion — not as one more row.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ClayTheme.colors.badgeGreen.background,
                contentColor = ClayTheme.colors.badgeGreen.text,
                shape = RoundedCornerShape(12.dp),
            ) {
                SummaryRow(
                    label = "مبلغ نهایی",
                    amount = invoice.grandTotal,
                    currency = invoice.currency.toPersianLabel(),
                    emphasized = true,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // Foreign money settles in Toman everywhere downstream, so the card
            // states the converted figure outright — the same pill the header
            // shows beside the rate field.
            if (invoice.currency.isForeignCurrency) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ClayTheme.colors.badgeBlue.background,
                    contentColor = ClayTheme.colors.badgeBlue.text,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "معادل تومانی: ${AmountFormatter.formatToman(invoice.effectiveTomanTotal.toDouble())} تومان",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // One trigger, three formats: the standard sheet plus the two
                // accounting imports share the primary slot through a menu, so the
                // row keeps its three-button shape instead of growing a fourth.
                var exportMenuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = { exportMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = "خروجی اکسل (.xls)", maxLines = 1)
                    }
                    ExportFormatMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false },
                        onStandard = onExportExcel,
                        onSepidar = onExportSepidar,
                        onHoloo = onExportHoloo,
                        showGlobal = showGlobalExports,
                        onQuickBooks = onExportQuickBooks,
                        onXero = onExportXero,
                        quickBooksLabel = strings.exportQuickBooks,
                        xeroLabel = strings.exportXero,
                    )
                }

                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "خروجی CSV", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "اسکن فاکتور جدید", maxLines = 1)
                }
            }

            // History: one explicit gesture keeps the invoice. The button doubles as the
            // verdict — offered while the screen differs from the store, confirming once
            // they match — so "kept" versus "seen" is visible without opening the archive.
            val saveEnabled = !isSaved || isDirty
            OutlinedButton(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saveEnabled) {
                    Text(text = "ذخیره در سوابق", maxLines = 1)
                } else {
                    Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "در سوابق ذخیره شده است", maxLines = 1)
                }
            }

            // Print: renders the formal A4 tax sheet and opens it print-ready in
            // the default browser, beside the export and save actions.
            Button(
                onClick = onPrintFormal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = PrintGlyph, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                // The sheet follows the window language with the print flow, so
                // the button names what it will actually open.
                Text(
                    text = if (strings.language == AppLanguage.EN) {
                        strings.btnPrintCommercialInvoice
                    } else {
                        strings.btnPrintFormalInvoice
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    amount: Double,
    currency: String? = null,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Emphasized rows inherit the ambient content color (the emerald tile), plain rows
    // use the surface default — one row type, two homes, no hardcoded mismatch.
    val rowColor = if (emphasized) LocalContentColor.current else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = rowColor,
        )

        Spacer(modifier = Modifier.weight(1f))

        // The amount and its unit stay together so a long grouped number can never push
        // the currency label off the edge of the card.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = AmountFormatter.formatToman(amount),
                style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = rowColor,
            )
            if (currency != null) {
                Text(
                    text = currency,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (emphasized) rowColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A printer glyph drawn by hand: `Icons.Filled.Print` ships only in the
 * extended icon artifact, which the build deliberately stays off (large extra
 * download, offline builds), so the print button carries its own
 * dependency-free vector instead. Rendered through [Icon] without an explicit
 * tint, it inherits the ambient content color like any core icon.
 */
private val PrintGlyph: ImageVector = ImageVector.Builder(
    name = "Print",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // Paper tray.
        moveTo(6f, 2f)
        lineTo(18f, 2f)
        lineTo(18f, 6f)
        lineTo(6f, 6f)
        close()
        // Body with the page slot.
        moveTo(3f, 7f)
        lineTo(21f, 7f)
        lineTo(21f, 14f)
        lineTo(17.5f, 14f)
        lineTo(17.5f, 20f)
        lineTo(6.5f, 20f)
        lineTo(6.5f, 14f)
        lineTo(3f, 14f)
        close()
        // Emerging page.
        moveTo(7.5f, 15f)
        lineTo(16.5f, 15f)
        lineTo(16.5f, 21.5f)
        lineTo(7.5f, 21.5f)
        close()
    }
}.build()
