package com.invoiceextract.app.ui.screens.review

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invoiceextract.app.R
import com.invoiceextract.app.ui.theme.ConfidenceHigh
import com.invoiceextract.app.ui.theme.ConfidenceLow
import com.invoiceextract.app.ui.theme.ConfidenceMedium
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.validation.ValidationStatus
import org.koin.androidx.compose.koinViewModel
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private const val TAG = "ReviewScreen"

/**
 * The post-extraction review surface.
 *
 * Renders the validated invoice as an editable form: every keystroke is handed to
 * [ReviewViewModel], which re-derives the totals, re-validates and re-emits the whole
 * invoice, so the banner, the suspicious-item highlighting and the summary are always
 * three views of one consistent object. The screen holds no invoice logic of its own —
 * it is a pure function of [ReviewUiState].
 *
 * @param onBack Pops the screen back to Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.review_saved_message)

    // One-shot events: the save signal carries the final invoice for Phase 8 to persist.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ReviewEvent.SaveReady -> {
                    Log.d(
                        TAG,
                        "saveInvoice: ready for Phase 8 Room persistence " +
                            "(id=${event.invoice.id}, status=${event.invoice.validationStatus})",
                    )
                    snackbarHostState.showSnackbar(savedMessage)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.review_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.review_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            SaveBar(
                enabled = uiState is ReviewUiState.Active,
                onClick = viewModel::saveInvoice,
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is ReviewUiState.Active -> ReviewContent(
                invoice = state.invoice,
                onMetadataChange = viewModel::updateInvoiceMetadata,
                onItemChange = viewModel::updateItem,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ReviewUiState.Empty -> EmptyContent(
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

/**
 * The whole editable invoice: validation verdict on top, identity fields, then one card
 * per line item, then the derived totals. Laid out in a scrolling column because a
 * multi-item invoice plus the summary comfortably overflows a phone screen.
 */
@Composable
private fun ReviewContent(
    invoice: Invoice,
    onMetadataChange: (seller: String, invoiceNumber: String, date: String) -> Unit,
    onItemChange: (index: Int, name: String, quantity: Double, unitPrice: Double, discount: Double, tax: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_toman)
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ValidationBanner(status = invoice.validationStatus)

        GeneralInfoCard(invoice = invoice, onMetadataChange = onMetadataChange)

        Text(
            text = stringResource(R.string.review_items_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        invoice.items.forEachIndexed { index, item ->
            ItemCard(
                key = invoice.id + item.id,
                index = index,
                item = item,
                currency = currency,
                onItemChange = onItemChange,
            )
        }

        TotalsSummaryCard(invoice = invoice, currency = currency)
    }
}

/**
 * Colored verdict strip at the top of the screen: green when the invoice is clean, amber
 * when it needs a second look, red when it must not be persisted as-is. The palette's
 * dedicated semantic colors are used rather than ad-hoc hues, so light/dark tweaks stay
 * in one place.
 */
@Composable
private fun ValidationBanner(
    status: ValidationStatus,
    modifier: Modifier = Modifier,
) {
    val visual = bannerVisual(status)
    val issues = status.issueDescriptions()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = visual.color.copy(alpha = CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, visual.color.copy(alpha = BORDER_ALPHA)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.color,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(visual.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = visual.color,
                )
            }
            // Every problem, one line each: the user should not have to open a details
            // screen to learn what the extractor got wrong.
            issues.forEach { description ->
                Text(
                    text = "• $description",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Colors, icon and title for one verdict, resolved in one exhaustively-checked place. */
private fun bannerVisual(status: ValidationStatus): BannerVisual = when (status) {
    ValidationStatus.Valid -> BannerVisual(
        color = ConfidenceHigh,
        icon = Icons.Filled.CheckCircle,
        titleRes = R.string.review_banner_valid,
    )

    is ValidationStatus.Warning -> BannerVisual(
        color = ConfidenceMedium,
        icon = Icons.Filled.Warning,
        titleRes = R.string.review_banner_warning,
    )

    is ValidationStatus.Invalid -> BannerVisual(
        color = ConfidenceLow,
        icon = Icons.Filled.Error,
        titleRes = R.string.review_banner_invalid,
    )
}

private data class BannerVisual(
    val color: Color,
    val icon: ImageVector,
    val titleRes: Int,
)

/**
 * The descriptions worth showing for [status]: every reason when it is a
 * [ValidationStatus.Warning], the critical errors when it is [ValidationStatus.Invalid],
 * and nothing when it is clean.
 */
private fun ValidationStatus.issueDescriptions(): List<String> = when (this) {
    ValidationStatus.Valid -> emptyList()
    is ValidationStatus.Warning -> reasons.map { it.description }
    is ValidationStatus.Invalid -> criticalErrors.map { it.description }
}

/**
 * Seller, invoice number and date — the identity fields the completeness rules check.
 * Each is editable; all three are committed together on every keystroke so the invoice
 * stays a single coherent object.
 */
@Composable
private fun GeneralInfoCard(
    invoice: Invoice,
    onMetadataChange: (seller: String, invoiceNumber: String, date: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.review_general_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            EditableTextField(
                key = invoice.id + "seller",
                label = stringResource(R.string.label_seller),
                value = invoice.sellerName.orEmpty(),
                onValueChange = { seller ->
                    onMetadataChange(seller, invoice.invoiceNumber.orEmpty(), invoice.date.orEmpty())
                },
            )
            EditableTextField(
                key = invoice.id + "number",
                label = stringResource(R.string.label_invoice_number),
                value = invoice.invoiceNumber.orEmpty(),
                onValueChange = { number ->
                    onMetadataChange(invoice.sellerName.orEmpty(), number, invoice.date.orEmpty())
                },
            )
            EditableTextField(
                key = invoice.id + "date",
                label = stringResource(R.string.label_invoice_date),
                value = invoice.date.orEmpty(),
                onValueChange = { date ->
                    onMetadataChange(invoice.sellerName.orEmpty(), invoice.invoiceNumber.orEmpty(), date)
                },
            )
        }
    }
}

/**
 * One line item. The card picks up an amber border when the item was flagged
 * [InvoiceItem.isSuspicious], so the rows needing attention are visible while scrolling
 * without the user having to read every number.
 */
@Composable
private fun ItemCard(
    key: String,
    index: Int,
    item: InvoiceItem,
    currency: String,
    onItemChange: (index: Int, name: String, quantity: Double, unitPrice: Double, discount: Double, tax: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                // ElevatedCard has no `border` parameter in Material3 1.3.x, so the
                // suspicious-item highlight is drawn with the border modifier instead.
                if (item.isSuspicious) {
                    Modifier.border(
                        border = BorderStroke(width = SUSPICIOUS_BORDER_WIDTH, color = ConfidenceMedium),
                        shape = CardDefaults.elevatedShape,
                    )
                } else {
                    Modifier
                },
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.isSuspicious) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = ConfidenceMedium,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.review_suspicious_item),
                        style = MaterialTheme.typography.labelSmall,
                        color = ConfidenceMedium,
                    )
                }
            }

            EditableTextField(
                key = key + "name",
                label = stringResource(R.string.review_label_item_name),
                value = item.name,
                onValueChange = { name ->
                    onItemChange(index, name, item.quantity, item.unitPrice, item.discount, item.tax)
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditableAmountField(
                    key = key + "quantity",
                    label = stringResource(R.string.review_label_quantity),
                    amount = item.quantity,
                    onAmountChange = { quantity ->
                        onItemChange(index, item.name, quantity, item.unitPrice, item.discount, item.tax)
                    },
                    modifier = Modifier.weight(1f),
                )
                EditableAmountField(
                    key = key + "unitPrice",
                    label = stringResource(R.string.review_label_unit_price),
                    amount = item.unitPrice,
                    onAmountChange = { unitPrice ->
                        onItemChange(index, item.name, item.quantity, unitPrice, item.discount, item.tax)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()

            // Read-only: the line total is derived, so the user cannot type a value that
            // contradicts the quantity and unit price above it.
            LabeledAmount(
                label = stringResource(R.string.review_label_total_price),
                amount = item.totalPrice,
                currency = currency,
                emphasized = true,
            )
        }
    }
}

/**
 * Subtotal, tax, discount and the final payable amount. Every figure here is derived
 * from the line items by [ReviewViewModel], so the summary and the cards above it are
 * two renderings of the same arithmetic and can never disagree.
 */
@Composable
private fun TotalsSummaryCard(
    invoice: Invoice,
    currency: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.review_totals_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LabeledAmount(
                label = stringResource(R.string.review_label_subtotal),
                amount = invoice.subtotal,
                currency = currency,
            )
            LabeledAmount(
                label = stringResource(R.string.review_label_total_tax),
                amount = invoice.totalTax,
                currency = currency,
            )
            LabeledAmount(
                label = stringResource(R.string.review_label_total_discount),
                amount = invoice.totalDiscount,
                currency = currency,
            )
            HorizontalDivider()
            LabeledAmount(
                label = stringResource(R.string.label_grand_total),
                amount = invoice.grandTotal,
                currency = currency,
                emphasized = true,
            )
        }
    }
}

/**
 * The pinned save action. Disabled until an invoice is loaded, so the empty state can
 * never offer a save that has nothing to save.
 */
@Composable
private fun SaveBar(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.review_action_save))
        }
    }
}

/**
 * Shown when Review is reached with nothing staged: a deep link, or a back-navigation
 * race that cleared the session. Sends the user back rather than presenting a dead form.
 */
@Composable
private fun EmptyContent(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.review_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.review_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBack) {
                Text(text = stringResource(R.string.review_back))
            }
        }
    }
}

/**
 * A text field whose editing state is keyed to [key] rather than to [value].
 *
 * Keying on identity (invoice id, item id, field) instead of the current value is what
 * keeps typing smooth: the flow re-emits a new invoice on every keystroke, and keying on
 * `value` would re-initialize the field from the reformatted model on each one, fighting
 * the user's cursor. Keyed this way, the field keeps whatever the user typed and only
 * re-seeds when a genuinely different invoice or item is displayed.
 */
@Composable
private fun EditableTextField(
    key: Any?,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(key) { mutableStateOf(value) }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            onValueChange(typed)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A numeric [EditableTextField]. Invalid input is kept on screen so the user can finish
 * typing (e.g. mid-number or a trailing separator) but is not propagated: the model only
 * ever receives well-formed amounts, so validation runs on real numbers every time.
 */
@Composable
private fun EditableAmountField(
    key: Any?,
    label: String,
    amount: Double,
    onAmountChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(key) { mutableStateOf(amount.formatPlain()) }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            typed.toDoubleOrNull()?.let(onAmountChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A label with a currency amount set against it, right-aligned by the RTL layout. */
@Composable
private fun LabeledAmount(
    label: String,
    amount: Double,
    currency: String,
    emphasized: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatAmount(amount, currency),
            style = if (emphasized) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.bodyLarge
            },
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Groups an amount and appends the currency unit, e.g. `"6,593,410 تومان"`. Grouping
 * uses [Locale.US] deliberately — the digits are Latin and the separator is a plain
 * comma, matching the rest of the app — while the currency word stays Persian and renders
 * correctly under RTL.
 */
private fun formatAmount(amount: Double, currency: String): String {
    val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    return "${formatter.format(amount)} $currency"
}

/** Plain digits with no grouping, so the editable fields parse back losslessly. */
private fun Double.formatPlain(): String =
    if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()

private const val CONTAINER_ALPHA = 0.12f
private const val BORDER_ALPHA = 0.5f
private val SUSPICIOUS_BORDER_WIDTH = 2.dp
