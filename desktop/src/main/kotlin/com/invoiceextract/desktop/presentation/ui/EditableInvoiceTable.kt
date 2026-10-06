package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.AppLanguage
import com.invoiceextract.desktop.presentation.ui.theme.AppStrings
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.appStrings
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem

/**
 * The editable line-item grid of the extracted invoice.
 *
 * Every column the user can fix by hand is editable in place, because for a Persian
 * invoice the model's single most common failure is one misread digit in one cell — and
 * re-dropping the file to fix a `۱` that read as a `۷` would cost a whole model round
 * trip. Editing a cell recomputes that line's total and the invoice totals, then
 * re-validates, so the table can never show an invoice that disagrees with its banner.
 *
 * **Layout.** Header and rows share identical column weights inside a single column, so
 * the grid stays aligned without a real table layout manager, and the whole table scrolls
 * as one item of the window's list rather than nesting a scrollable list inside one.
 * Alternate rows carry a slate wash (zebra striping) so the eye can follow a dense
 * invoice across seven columns without a ruler.
 *
 * **Editing state.** Each cell keeps its own text and is keyed on the item's id, so a
 * typed value survives the recomposition the edit itself triggers, while a *newly*
 * extracted invoice (new ids) resets every field to the fresh values.
 *
 * **Suspicion.** A row the extractor or the validator flagged gets an amber outline, the
 * same color the banner uses for a warning, so a suspicious line is recognizable without
 * reading the issue list.
 *
 * @param onItemChange Called with the row's six fields whenever one of them parses. Fields
 *   that do not parse (a half-typed number) are ignored rather than applied.
 * @param onProductCodeChange Called with the row's warehouse code on every keystroke.
 *   The view model writes the field through and teaches the mapping table the pair,
 *   so typing a code once maps the good for every future invoice.
 */
@Composable
fun EditableInvoiceTable(
    invoice: Invoice,
    onItemChange: (
        index: Int,
        name: String,
        quantity: Double,
        unitPrice: Double,
        discount: Double,
        tax: Double,
    ) -> Unit,
    onProductCodeChange: (index: Int, productCode: String) -> Unit,
    modifier: Modifier = Modifier,
    strings: AppStrings = appStrings(AppLanguage.FA),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = TABLE_ELEVATION,
    ) {
        Column {
            TableHeader(strings = strings)

            if (invoice.items.isEmpty()) {
                Text(
                    text = strings.tableEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
            } else {
                invoice.items.forEachIndexed { index, item ->
                    // Keyed so a replaced invoice starts every field from its own values.
                    key(item.id) {
                        InvoiceItemRow(
                            index = index,
                            item = item,
                            zebraStriped = index % 2 == 1,
                            onItemChange = onItemChange,
                            onProductCodeChange = onProductCodeChange,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TableHeader(strings: AppStrings) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell(weight = WEIGHT_INDEX, text = strings.tableRow)
            HeaderCell(weight = WEIGHT_CODE, text = strings.tableCode)
            HeaderCell(weight = WEIGHT_NAME, text = strings.tableProduct)
            HeaderCell(weight = WEIGHT_QUANTITY, text = strings.tableQty)
            HeaderCell(weight = WEIGHT_UNIT_PRICE, text = strings.tableUnitPrice)
            HeaderCell(weight = WEIGHT_DISCOUNT, text = strings.tableDiscount)
            HeaderCell(weight = WEIGHT_TAX, text = strings.tableTax)
            HeaderCell(weight = WEIGHT_TOTAL, text = strings.tableTotal)
        }
    }
}

@Composable
private fun RowScope.HeaderCell(weight: Float, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = ClayTheme.colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun InvoiceItemRow(
    index: Int,
    item: InvoiceItem,
    zebraStriped: Boolean,
    onItemChange: (Int, String, Double, Double, Double, Double) -> Unit,
    onProductCodeChange: (Int, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Zebra wash on alternate rows so long invoices stay readable; the
            // suspicious amber frame composes on top of it unchanged.
            .background(
                color = if (zebraStriped) ClayTheme.colors.tableRowZebra else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            // An amber frame around a line the pipeline flagged, matching the banner's
            // warning color so the two agree at a glance.
            .then(
                if (item.isSuspicious) {
                    Modifier.border(
                        width = 1.dp,
                        color = ClayTheme.colors.badgeAmber.text,
                        shape = RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Row number is read-only: it is a display convention, not data.
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = ClayTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(WEIGHT_INDEX),
        )

        // The warehouse code: free text, auto-synced into the mapping table on every
        // keystroke, so the merchant teaches each good exactly once.
        EditableTextCell(
            weight = WEIGHT_CODE,
            initial = item.productCode.orEmpty(),
            onValueChange = { newCode -> onProductCodeChange(index, newCode) },
        )

        EditableTextCell(
            weight = WEIGHT_NAME,
            initial = item.name,
            onValueChange = { newName ->
                onItemChange(index, newName, item.quantity, item.unitPrice, item.discount, item.tax)
            },
        )

        EditableNumberCell(
            weight = WEIGHT_QUANTITY,
            initial = item.quantity,
            onValueChange = { newQuantity ->
                onItemChange(index, item.name, newQuantity, item.unitPrice, item.discount, item.tax)
            },
        )

        EditableNumberCell(
            weight = WEIGHT_UNIT_PRICE,
            initial = item.unitPrice,
            onValueChange = { newUnitPrice ->
                onItemChange(index, item.name, item.quantity, newUnitPrice, item.discount, item.tax)
            },
        )

        EditableNumberCell(
            weight = WEIGHT_DISCOUNT,
            initial = item.discount,
            onValueChange = { newDiscount ->
                onItemChange(index, item.name, item.quantity, item.unitPrice, newDiscount, item.tax)
            },
        )

        EditableNumberCell(
            weight = WEIGHT_TAX,
            initial = item.tax,
            onValueChange = { newTax ->
                onItemChange(index, item.name, item.quantity, item.unitPrice, item.discount, newTax)
            },
        )

        // The line total is derived, never typed: editing a part recomputes it.
        Text(
            text = AmountFormatter.formatToman(item.totalPrice),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = ClayTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(WEIGHT_TOTAL),
        )
    }
}

/**
 * One free-text cell. Holds its own text so the user's typing survives the recomposition
 * the edit triggers; the surrounding `key` on the item id resets it for a new invoice.
 */
@Composable
private fun RowScope.EditableTextCell(weight: Float, initial: String, onValueChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }

    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            text = newValue
            onValueChange(newValue)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = clayTextFieldColors(ClayTheme.colors),
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 4.dp),
    )
}

/**
 * One numeric cell. A value that does not parse as a number is shown but *not* applied —
 * typing `-` or clearing the field must never zero a price, only fail to update the model
 * until the input becomes a number again.
 */
@Composable
private fun RowScope.EditableNumberCell(weight: Float, initial: Double, onValueChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(AmountFormatter.formatQuantity(initial)) }

    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            text = newValue
            // Only well-formed input reaches the model; anything else is a work in progress.
            newValue.toDoubleOrNull()?.let(onValueChange)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = clayTextFieldColors(ClayTheme.colors),
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 4.dp),
    )
}

// Column widths as fractions of the row, shared by the header and every data row so the
// grid stays aligned. The name column stays widest because Persian item names run long;
// the code column takes its share mostly from the shrunken index and name columns.
private const val WEIGHT_INDEX = 0.05f
private const val WEIGHT_CODE = 0.14f
private const val WEIGHT_NAME = 0.25f
private const val WEIGHT_QUANTITY = 0.09f
private const val WEIGHT_UNIT_PRICE = 0.14f
private const val WEIGHT_DISCOUNT = 0.10f
private const val WEIGHT_TAX = 0.10f
private const val WEIGHT_TOTAL = 0.13f

private val TABLE_ELEVATION = 1.dp
