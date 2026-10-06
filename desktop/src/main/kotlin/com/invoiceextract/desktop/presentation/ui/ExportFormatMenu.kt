package com.invoiceextract.desktop.presentation.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * The export formats as a dropdown menu, shared by every export row.
 *
 * Each export row (invoice totals, batch actions, history cards) already owns its
 * trigger button and anchoring box — button styles differ per row, so those stay
 * local. What must never differ is the menu itself: the same items in the same
 * order, which is why they live here exactly once.
 *
 * The global QuickBooks/Xero entries ride the same menu behind [showGlobal]:
 * off by default, so every pre-existing caller renders byte-identical output
 * until it opts in. Labels arrive as parameters (defaulting to the Persian
 * originals) because this file owns no locale — the screens do.
 *
 * @param expanded Whether the menu is open; hoisted so the trigger owns it.
 * @param onDismissRequest Closes the menu without choosing.
 * @param onStandard / [onSepidar] / [onHoloo] Run on selection, after dismissal.
 * @param onQuickBooks / [onXero] Same contract for the global CSV imports;
 *   hidden unless [showGlobal].
 */
@Composable
fun ExportFormatMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onStandard: () -> Unit,
    onSepidar: () -> Unit,
    onHoloo: () -> Unit,
    showGlobal: Boolean = false,
    onQuickBooks: () -> Unit = {},
    onXero: () -> Unit = {},
    standardLabel: String = STANDARD_EXCEL_LABEL,
    quickBooksLabel: String = QUICKBOOKS_CSV_LABEL,
    xeroLabel: String = XERO_CSV_LABEL,
    sepidarLabel: String = SEPIDAR_EXCEL_LABEL,
    holooLabel: String = HOLOO_EXCEL_LABEL,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(text = standardLabel) },
            onClick = {
                onDismissRequest()
                onStandard()
            },
        )
        if (showGlobal) {
            DropdownMenuItem(
                text = { Text(text = quickBooksLabel) },
                onClick = {
                    onDismissRequest()
                    onQuickBooks()
                },
            )
            DropdownMenuItem(
                text = { Text(text = xeroLabel) },
                onClick = {
                    onDismissRequest()
                    onXero()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(text = sepidarLabel) },
            onClick = {
                onDismissRequest()
                onSepidar()
            },
        )
        DropdownMenuItem(
            text = { Text(text = holooLabel) },
            onClick = {
                onDismissRequest()
                onHoloo()
            },
        )
    }
}

private const val STANDARD_EXCEL_LABEL = "خروجی اکسل (.xls)"
private const val QUICKBOOKS_CSV_LABEL = "کوییک‌بوکس (.csv)"
private const val XERO_CSV_LABEL = "زیرو (.csv)"
private const val SEPIDAR_EXCEL_LABEL = "سپیدار سیستم (.xls)"
private const val HOLOO_EXCEL_LABEL = "هلو (.xls)"
