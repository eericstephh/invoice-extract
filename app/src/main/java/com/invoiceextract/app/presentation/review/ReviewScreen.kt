package com.invoiceextract.app.presentation.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invoiceextract.app.ui.theme.ConfidenceHigh
import com.invoiceextract.app.ui.theme.ConfidenceLow
import com.invoiceextract.app.ui.theme.ConfidenceMedium
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Interactive Review & Edit screen (Phase 7.2 / 8.2 / 9.2).
 *
 * Pure function of [ReviewUiState]: every keystroke / dialog confirm is delegated to
 * [ReviewViewModel], which re-derives totals, re-validates and re-emits. RTL is forced
 * globally by `InvoiceExtractTheme(forceRtl = true)`, so all Material 3 arrangements
 * here are direction-aware with no manual mirroring.
 *
 * Export goes through SAF's `CreateDocument`: the ViewModel supplies a suggested
 * filename, the system shows its own picker (which owns the permission grant and the
 * chosen location), and the returned Uri is handed straight back to the ViewModel. The
 * screen never touches a filesystem path.
 *
 * @param onNavigateBack Pops the back stack (TopAppBar arrow + Empty placeholder).
 * @param onSaveSuccess Invoked once the invoice is committed to Room; the nav host
 *   uses it to leave the Review destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onNavigateBack: () -> Unit,
    onSaveSuccess: () -> Unit,
    viewModel: ReviewViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // SAF pickers. The MIME type is what constrains the offered locations; the
    // extension in the suggested name is what the provider appends by default.
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let { viewModel.exportCsv(it) } }

    val excelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.ms-excel"),
    ) { uri -> uri?.let { viewModel.exportExcel(it) } }

    // One-shot save failures: emitted by the ViewModel, surfaced as a transient
    // snackbar. Never part of the recurring state, so it cannot linger.
    LaunchedEffect(Unit) {
        viewModel.saveErrors.collect { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // One-shot export results, same treatment.
    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { event ->
            scope.launch { snackbarHostState.showSnackbar(event.message) }
        }
    }

    var exportMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "بازبینی و ویرایش فاکتور") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "بازگشت",
                        )
                    }
                },
                actions = {
                    // Two export targets behind one overflow entry: an overflow keeps
                    // the bar readable on a narrow phone in either layout direction,
                    // and each item is labelled in Persian for the screen-reader user.
                    IconButton(onClick = { exportMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "خروجی فاکتور",
                        )
                    }
                    DropdownMenu(
                        expanded = exportMenuExpanded,
                        onDismissRequest = { exportMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = "خروجی اکسل") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Filled.TableChart, contentDescription = null)
                            },
                            onClick = {
                                exportMenuExpanded = false
                                excelLauncher.launch(viewModel.getSuggestedFileName("xls"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = "خروجی CSV") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Filled.Description, contentDescription = null)
                            },
                            onClick = {
                                exportMenuExpanded = false
                                csvLauncher.launch(viewModel.getSuggestedFileName("csv"))
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            val content = uiState as? ReviewUiState.Content
            SaveActionBar(
                enabled = content != null && !content.isSaving,
                showProgress = content?.isSaving == true,
                onClick = { viewModel.saveInvoice(onSuccess = onSaveSuccess) },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is ReviewUiState.Loading -> LoadingContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ReviewUiState.Empty -> EmptyContent(
                onBack = onNavigateBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ReviewUiState.Content -> ReviewContent(
                invoice = state.invoice,
                isSaving = state.isSaving,
                onMetadataChange = viewModel::updateMetadata,
                onItemChange = viewModel::updateItem,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "فاکتوری برای بازبینی موجود نیست",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "ابتدا یک فاکتور اسکن کنید",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onBack) {
                Text(text = "بازگشت")
            }
        }
    }
}

@Composable
private fun ReviewContent(
    invoice: Invoice,
    isSaving: Boolean,
    onMetadataChange: (sellerName: String, invoiceNumber: String, date: String) -> Unit,
    onItemChange: (index: Int, name: String, quantity: Double, unitPrice: Double, discount: Double, tax: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isSaving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        ValidationBanner(status = invoice.validationStatus)

        HeaderMetadataCard(invoice = invoice, onMetadataChange = onMetadataChange)

        Text(
            text = "اقلام فاکتور",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        invoice.items.forEachIndexed { index, item ->
            ItemCard(
                item = item,
                currency = CURRENCY_LABEL,
                onClick = { editingIndex = index },
            )
        }

        TotalsSummaryCard(invoice = invoice, currency = CURRENCY_LABEL)
    }

    val dialogIndex = editingIndex
    if (dialogIndex != null && dialogIndex in invoice.items.indices) {
        EditItemDialog(
            item = invoice.items[dialogIndex],
            onDismiss = { editingIndex = null },
            onConfirm = { name, quantity, unitPrice, discount, tax ->
                onItemChange(dialogIndex, name, quantity, unitPrice, discount, tax)
                editingIndex = null
            },
        )
    }
}

@Composable
private fun ValidationBanner(status: ValidationStatus, modifier: Modifier = Modifier) {
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
                    text = visual.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = visual.color,
                )
            }
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

private data class BannerVisual(
    val color: Color,
    val icon: ImageVector,
    val title: String,
)

private fun bannerVisual(status: ValidationStatus): BannerVisual = when (status) {
    ValidationStatus.Valid -> BannerVisual(
        color = ConfidenceHigh,
        icon = Icons.Filled.CheckCircle,
        title = "اطلاعات فاکتور معتبر است",
    )

    is ValidationStatus.Warning -> BannerVisual(
        color = ConfidenceMedium,
        icon = Icons.Filled.Warning,
        title = "نیازمند بررسی و اصلاح",
    )

    is ValidationStatus.Invalid -> BannerVisual(
        color = ConfidenceLow,
        icon = Icons.Filled.Error,
        title = "فاکتور نامعتبر است",
    )
}

private fun ValidationStatus.issueDescriptions(): List<String> = when (this) {
    ValidationStatus.Valid -> emptyList()
    is ValidationStatus.Warning -> reasons.map { it.description }
    is ValidationStatus.Invalid -> criticalErrors.map { it.description }
}

@Composable
private fun HeaderMetadataCard(
    invoice: Invoice,
    onMetadataChange: (sellerName: String, invoiceNumber: String, date: String) -> Unit,
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
                text = "اطلاعات کلی",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            EditableTextField(
                key = invoice.id + "seller",
                label = "نام فروشنده",
                value = invoice.sellerName.orEmpty(),
                onValueChange = { seller ->
                    onMetadataChange(seller, invoice.invoiceNumber.orEmpty(), invoice.date.orEmpty())
                },
            )
            EditableTextField(
                key = invoice.id + "number",
                label = "شماره فاکتور",
                value = invoice.invoiceNumber.orEmpty(),
                onValueChange = { number ->
                    onMetadataChange(invoice.sellerName.orEmpty(), number, invoice.date.orEmpty())
                },
            )
            EditableTextField(
                key = invoice.id + "date",
                label = "تاریخ",
                value = invoice.date.orEmpty(),
                onValueChange = { date ->
                    onMetadataChange(invoice.sellerName.orEmpty(), invoice.invoiceNumber.orEmpty(), date)
                },
            )
        }
    }
}

@Composable
private fun ItemCard(
    item: InvoiceItem,
    currency: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (item.isSuspicious) {
                    Modifier.border(
                        border = BorderStroke(width = SUSPICIOUS_BORDER_WIDTH, color = ConfidenceMedium),
                        shape = CardDefaults.elevatedShape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        text = "نیازمند بازبینی",
                        style = MaterialTheme.typography.labelSmall,
                        color = ConfidenceMedium,
                    )
                }
            }
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "تعداد: ${item.quantity.formatPlain()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "قیمت واحد: ${formatAmount(item.unitPrice, currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            LabeledAmount(
                label = "مبلغ کل",
                amount = item.totalPrice,
                currency = currency,
                emphasized = true,
            )
        }
    }
}

/**
 * Edit dialog for one line item. Quantity and unit price are the primary inputs per
 * spec; name / discount / tax ride along so [ReviewViewModel.updateItem] always
 * receives the full component set. Invalid numerics block confirm instead of
 * propagating NaN into validation.
 */
@Composable
private fun EditItemDialog(
    item: InvoiceItem,
    onDismiss: () -> Unit,
    onConfirm: (name: String, quantity: Double, unitPrice: Double, discount: Double, tax: Double) -> Unit,
) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var quantityText by remember(item.id) { mutableStateOf(item.quantity.formatPlain()) }
    var unitPriceText by remember(item.id) { mutableStateOf(item.unitPrice.formatPlain()) }
    var discountText by remember(item.id) { mutableStateOf(item.discount.formatPlain()) }
    var taxText by remember(item.id) { mutableStateOf(item.tax.formatPlain()) }

    val quantity = quantityText.toDoubleOrNull()
    val unitPrice = unitPriceText.toDoubleOrNull()
    val discount = discountText.toDoubleOrNull()
    val tax = taxText.toDoubleOrNull()
    val isValid = quantity != null && unitPrice != null && discount != null && tax != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "ویرایش قلم") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام کالا") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("تعداد") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unitPriceText,
                        onValueChange = { unitPriceText = it },
                        label = { Text("قیمت واحد") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it },
                        label = { Text("تخفیف") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = taxText,
                        onValueChange = { taxText = it },
                        label = { Text("مالیات") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    if (isValid) onConfirm(name.trim(), quantity!!, unitPrice!!, discount!!, tax!!)
                },
            ) {
                Text("تأیید")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف")
            }
        },
    )
}

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
                text = "جمع‌بندی مبالغ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LabeledAmount(label = "جمع جزء", amount = invoice.subtotal, currency = currency)
            LabeledAmount(label = "مالیات", amount = invoice.totalTax, currency = currency)
            LabeledAmount(label = "تخفیف", amount = invoice.totalDiscount, currency = currency)
            HorizontalDivider()
            LabeledAmount(
                label = "مبلغ نهایی",
                amount = invoice.grandTotal,
                currency = currency,
                emphasized = true,
            )
        }
    }
}

@Composable
private fun SaveActionBar(
    enabled: Boolean,
    showProgress: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showProgress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(text = "ذخیره نهایی فاکتور")
            }
        }
    }
}

/**
 * Text field keyed by identity rather than value: the flow re-emits a new invoice on
 * every keystroke, and keying on `value` would re-seed the field and fight the cursor.
 * Keyed this way the field keeps what the user typed and only re-seeds for a genuinely
 * different invoice.
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
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatAmount(amount, currency),
            style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatAmount(amount: Double, currency: String): String {
    val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    return "${formatter.format(amount)} $currency"
}

private fun Double.formatPlain(): String =
    if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()

private const val CURRENCY_LABEL = "تومان"
private const val CONTAINER_ALPHA = 0.12f
private const val BORDER_ALPHA = 0.5f
private val SUSPICIOUS_BORDER_WIDTH = 2.dp
