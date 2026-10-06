package com.invoiceextract.desktop.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.invoiceextract.desktop.domain.validation.IdentifierValidationResult
import com.invoiceextract.desktop.domain.validation.IranianNationalIdValidator
import com.invoiceextract.desktop.domain.validation.GlobalTaxIdValidator
import com.invoiceextract.desktop.domain.validation.GlobalTaxIdType
import com.invoiceextract.desktop.domain.validation.GlobalTaxValidationResult
import com.invoiceextract.desktop.presentation.AmountFormatter
import com.invoiceextract.desktop.presentation.ui.theme.ClayTheme
import com.invoiceextract.desktop.presentation.ui.theme.clayTextFieldColors
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.PaymentStatus
import com.invoiceextract.domain.model.isForeignCurrency

/**
 * The editable invoice header: seller, invoice number, date and both national IDs.
 *
 * The extractor leaves these null more often than the line items — a smudged header or a
 * date printed over a stamp is a routine miss — so they need to be fixable by hand
 * exactly like a table cell. Every keystroke re-validates through the view model, so a
 * completed header can turn a red banner amber on its own.
 *
 * Each national-ID field carries a live checksum chip beneath it: green while the
 * Modulo-11 check holds, red while it does not, absent while the field is empty. The
 * chip judges the keystrokes only — persisting the audit verdict stays the pipeline's
 * job, so typing can never write a half-typed number into the invoice's status.
 *
 * Fields are keyed on the invoice id, so an edit survives its own recomposition while a
 * newly extracted invoice starts every field fresh.
 *
 * Client and project tags ride the same funnel: they are user-assigned attribution,
 * not extracted data, but they still re-validate on every keystroke so the invoice on
 * screen is always the validated one.
 *
 * @param onMetadataChange Called with the seven header fields as the user edits them.
 * @param onCurrencyChange Called with the picked currency and, for foreign money,
 *   the typed day rate into Toman (`null` while the field holds no valid number,
 *   or whenever the currency is domestic).
 * @param defaultCurrency The workspace currency default shown preselected while
 *   the invoice carries no detected currency yet; the stored value commits
 *   only when the user taps a chip.
 * @param isEnglish Whether the window runs in English; selects the verdict
 *   language of the western tax-ID chips.
 * @param taxEinValidLabel / taxVatValidLabel / taxIdInvalidLabel Captions of
 *   the western tax-ID chips in window language.
 * @param onPaymentStatusChange Called with the picked collection status.
 * @param onDueDateChange Called with the typed deadline; blank clears it.
 * @param paymentPaidLabel / paymentPendingLabel / paymentOverdueLabel The
 *   three status captions in window language.
 * @param paymentSectionLabel Caption of the status block in window language.
 * @param dueDateLabel Caption of the deadline field in window language.
 */
@Composable
fun InvoiceMetadataCard(
    invoice: Invoice,
    onMetadataChange: (
        seller: String,
        invoiceNumber: String,
        date: String,
        sellerNationalId: String,
        buyerNationalId: String,
        clientName: String,
        projectName: String,
    ) -> Unit,
    onCurrencyChange: (currency: CurrencyType, exchangeRate: Double?) -> Unit = { _, _ -> },
    onPaymentStatusChange: (PaymentStatus) -> Unit = {},
    onDueDateChange: (String) -> Unit = {},
    defaultCurrency: CurrencyType = CurrencyType.UNKNOWN,
    paymentPaidLabel: String = "پرداخت‌شده",
    paymentPendingLabel: String = "در انتظار پرداخت",
    paymentOverdueLabel: String = "سررسید گذشته",
    paymentSectionLabel: String = "وضعیت پرداخت",
    dueDateLabel: String = "تاریخ سررسید (اختیاری)",
    isEnglish: Boolean = false,
    taxEinValidLabel: String = "EIN معتبر ✅",
    taxVatValidLabel: String = "VAT معتبر ✅",
    taxIdInvalidLabel: String = "قالب نامعتبر ❌",
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "اطلاعات فاکتور",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = "فروشنده",
                    resetKey = invoice.id,
                    initial = invoice.sellerName.orEmpty(),
                    onValueChange = { seller ->
                        onMetadataChange(
                            seller,
                            invoice.invoiceNumber.orEmpty(),
                            invoice.date.orEmpty(),
                            invoice.sellerNationalId.orEmpty(),
                            invoice.buyerNationalId.orEmpty(),
                            invoice.clientName.orEmpty(),
                            invoice.projectName.orEmpty(),
                        )
                    },
                )

                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = "شماره فاکتور",
                    resetKey = invoice.id,
                    initial = invoice.invoiceNumber.orEmpty(),
                    onValueChange = { number ->
                        onMetadataChange(
                            invoice.sellerName.orEmpty(),
                            number,
                            invoice.date.orEmpty(),
                            invoice.sellerNationalId.orEmpty(),
                            invoice.buyerNationalId.orEmpty(),
                            invoice.clientName.orEmpty(),
                            invoice.projectName.orEmpty(),
                        )
                    },
                )
            }

            HeaderField(
                label = "تاریخ (شمسی)",
                resetKey = invoice.id,
                initial = invoice.date.orEmpty(),
                onValueChange = { date ->
                    onMetadataChange(
                        invoice.sellerName.orEmpty(),
                        invoice.invoiceNumber.orEmpty(),
                        date,
                        invoice.sellerNationalId.orEmpty(),
                        invoice.buyerNationalId.orEmpty(),
                        invoice.clientName.orEmpty(),
                        invoice.projectName.orEmpty(),
                    )
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = dueDateLabel,
                    resetKey = invoice.id + ":due",
                    initial = invoice.dueDate.orEmpty(),
                    onValueChange = onDueDateChange,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = paymentSectionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentChip(
                            label = paymentPaidLabel,
                            selected = invoice.paymentStatus == PaymentStatus.PAID,
                            selectedContainer = ClayTheme.colors.badgeGreen.background,
                            selectedContent = ClayTheme.colors.badgeGreen.text,
                            onClick = { onPaymentStatusChange(PaymentStatus.PAID) },
                            modifier = Modifier.weight(1f),
                        )
                        PaymentChip(
                            label = paymentPendingLabel,
                            selected = invoice.paymentStatus == PaymentStatus.PENDING,
                            selectedContainer = ClayTheme.colors.badgeAmber.background,
                            selectedContent = ClayTheme.colors.badgeAmber.text,
                            onClick = { onPaymentStatusChange(PaymentStatus.PENDING) },
                            modifier = Modifier.weight(1f),
                        )
                        PaymentChip(
                            label = paymentOverdueLabel,
                            selected = invoice.paymentStatus == PaymentStatus.OVERDUE,
                            selectedContainer = ClayTheme.colors.badgeRed.background,
                            selectedContent = ClayTheme.colors.badgeRed.text,
                            onClick = { onPaymentStatusChange(PaymentStatus.OVERDUE) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = "شناسه ملی فروشنده",
                    resetKey = invoice.id,
                    initial = invoice.sellerNationalId.orEmpty(),
                    onValueChange = { sellerNationalId ->
                        onMetadataChange(
                            invoice.sellerName.orEmpty(),
                            invoice.invoiceNumber.orEmpty(),
                            invoice.date.orEmpty(),
                            sellerNationalId,
                            invoice.buyerNationalId.orEmpty(),
                            invoice.clientName.orEmpty(),
                            invoice.projectName.orEmpty(),
                        )
                    },
                    statusContent = { text ->
                        TaxIdChip(
                            text = text,
                            isEnglish = isEnglish,
                            einValidLabel = taxEinValidLabel,
                            vatValidLabel = taxVatValidLabel,
                            invalidLabel = taxIdInvalidLabel,
                        )
                    },
                )

                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = "شناسه ملی خریدار",
                    resetKey = invoice.id,
                    initial = invoice.buyerNationalId.orEmpty(),
                    onValueChange = { buyerNationalId ->
                        onMetadataChange(
                            invoice.sellerName.orEmpty(),
                            invoice.invoiceNumber.orEmpty(),
                            invoice.date.orEmpty(),
                            invoice.sellerNationalId.orEmpty(),
                            buyerNationalId,
                            invoice.clientName.orEmpty(),
                            invoice.projectName.orEmpty(),
                        )
                    },
                    statusContent = { text ->
                        TaxIdChip(
                            text = text,
                            isEnglish = isEnglish,
                            einValidLabel = taxEinValidLabel,
                            vatValidLabel = taxVatValidLabel,
                            invalidLabel = taxIdInvalidLabel,
                        )
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = "نام کارفرما / مشتری",
                    resetKey = invoice.id,
                    initial = invoice.clientName.orEmpty(),
                    onValueChange = { clientName ->
                        onMetadataChange(
                            invoice.sellerName.orEmpty(),
                            invoice.invoiceNumber.orEmpty(),
                            invoice.date.orEmpty(),
                            invoice.sellerNationalId.orEmpty(),
                            invoice.buyerNationalId.orEmpty(),
                            clientName,
                            invoice.projectName.orEmpty(),
                        )
                    },
                )

                HeaderField(
                    modifier = Modifier.weight(1f),
                    label = "پروژه / کمپین",
                    resetKey = invoice.id,
                    initial = invoice.projectName.orEmpty(),
                    onValueChange = { projectName ->
                        onMetadataChange(
                            invoice.sellerName.orEmpty(),
                            invoice.invoiceNumber.orEmpty(),
                            invoice.date.orEmpty(),
                            invoice.sellerNationalId.orEmpty(),
                            invoice.buyerNationalId.orEmpty(),
                            invoice.clientName.orEmpty(),
                            projectName,
                        )
                    },
                )
            }

            CurrencySection(
                invoice = invoice,
                onCurrencyChange = onCurrencyChange,
                defaultCurrency = defaultCurrency,
            )
        }
    }
}

/**
 * The currency picker: a row of clay filter chips for Toman, Rial, Dollar,
 * Euro and Tether. Foreign money opens a day-rate field (Persian/ASCII digits,
 * grouping separators tolerated) and a live Toman-equivalent pill, so an
 * international receipt joins the Toman ledgers without currency mixing.
 *
 * The rate text is keyed on the invoice id only, so flipping between
 * currencies never wipes a typed rate, while a newly extracted invoice starts
 * from its own stored rate.
 *
 * @param defaultCurrency The mode default ([defaultCurrencyFor]): when the
 *   invoice carries no detected currency yet, its chip reads selected so a
 *   fresh manual entry starts from the workspace default. Nothing is written
 *   until the user taps — selection is a default, not a verdict.
 */
@Composable
private fun CurrencySection(
    invoice: Invoice,
    onCurrencyChange: (CurrencyType, Double?) -> Unit,
    defaultCurrency: CurrencyType = CurrencyType.UNKNOWN,
    modifier: Modifier = Modifier,
) {
    var rateText by remember(invoice.id) { mutableStateOf(formatRateField(invoice.exchangeRate)) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "واحد پول",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CURRENCY_OPTIONS.forEach { currency ->
                CurrencyChip(
                    label = CURRENCY_LABELS.getValue(currency),
                    selected = invoice.currency == currency ||
                        (invoice.currency == CurrencyType.UNKNOWN && currency == defaultCurrency),
                    onClick = {
                        onCurrencyChange(currency, parseRate(rateText))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (invoice.currency.isForeignCurrency) {
            OutlinedTextField(
                value = rateText,
                onValueChange = { newValue ->
                    rateText = newValue
                    onCurrencyChange(invoice.currency, parseRate(newValue))
                },
                label = { Text(text = "نرخ تبدیل روز به تومان") },
                placeholder = { Text(text = "مثلاً: 98,000") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = clayTextFieldColors(ClayTheme.colors),
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ClayTheme.colors.badgeGreen.background,
                contentColor = ClayTheme.colors.badgeGreen.text,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = "معادل تومانی: ${AmountFormatter.formatToman(invoice.effectiveTomanTotal.toDouble())} تومان",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * One collection-status option: the picked status wears its semantic badge
 * wash (green/amber/red), the rest sit on the surface variant — the same
 * filter-row language as [CurrencyChip], so the card reads as one family.
 */
@Composable
private fun PaymentChip(
    label: String,
    selected: Boolean,
    selectedContainer: Color,
    selectedContent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) selectedContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) selectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/**
 * One currency option: the selected chip wears the primary container, the rest
 * sit on the surface variant — a filter row, not a menu, so all five options
 * stay visible without a tap.
 */
@Composable
private fun CurrencyChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/** The stored rate as field text: whole rates without decimals or grouping. */
private fun formatRateField(rate: Double?): String {
    if (rate == null || !rate.isFinite() || rate <= 0.0) return ""
    return if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()
}

/**
 * Parses the rate field leniently: Persian/Arabic-Indic digits unify to ASCII,
 * grouping separators (`,`, `٬`, space) drop out, and the Arabic decimal
 * separator reads as `.`. Returns `null` when the field holds no positive
 * finite number.
 */
private fun parseRate(raw: String): Double? {
    val normalized = buildString(raw.length) {
        for (c in raw.trim()) {
            when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                '٫' -> append('.')
                ',', '٬', ' ' -> append("")
                else -> append(c)
            }
        }
    }.trim()
    if (normalized.isEmpty()) return null
    val parsed = normalized.toDoubleOrNull() ?: return null
    return if (parsed.isFinite() && parsed > 0.0) parsed else null
}

private val CURRENCY_OPTIONS = listOf(
    CurrencyType.TOMAN,
    CurrencyType.RIAL,
    CurrencyType.USD,
    CurrencyType.EUR,
    CurrencyType.USDT,
)

private val CURRENCY_LABELS = mapOf(
    CurrencyType.TOMAN to "تومان",
    CurrencyType.RIAL to "ریال",
    CurrencyType.USD to "دلار ($)",
    CurrencyType.EUR to "یورو (€)",
    CurrencyType.USDT to "تتر (USDT)",
)

/**
 * One header field, with an optional live status slot beneath it.
 *
 * The slot reads the field's *current* keystrokes (not the last committed value), so a
 * checksum chip reacts while the user is still typing. An absent slot renders nothing —
 * the pre-existing fields pass none and look exactly as before.
 *
 * @param resetKey The invoice id: when a *different* invoice is extracted, the remembered
 *   text is discarded and the field starts from the new [initial]. While the same invoice
 *   is on screen the key is stable, so the user's typing survives recomposition.
 */
@Composable
private fun HeaderField(
    label: String,
    resetKey: String,
    initial: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    statusContent: @Composable (currentText: String) -> Unit = {},
) {
    var text by remember(resetKey) { mutableStateOf(initial) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { newValue ->
                text = newValue
                onValueChange(newValue)
            },
            label = { Text(text = label) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = clayTextFieldColors(ClayTheme.colors),
            modifier = Modifier.fillMaxWidth(),
        )
        statusContent(text)
    }
}

/**
 * The live checksum verdict for a tax-identifier field: routes western shapes
 * to the global validator and digit strings to the Iranian one — the same
 * exclusive split the audit pipeline uses, so the chip and the banner can
 * never disagree about the same keystrokes.
 */
@Composable
private fun TaxIdChip(
    text: String,
    isEnglish: Boolean,
    einValidLabel: String,
    vatValidLabel: String,
    invalidLabel: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    if (!GlobalTaxIdValidator.looksWesternTaxId(text)) {
        NationalIdChip(
            result = remember(text) {
                IranianNationalIdValidator.validateIdentifier(text)
            },
            modifier = modifier,
        )
        return
    }

    val result = remember(text, isEnglish) {
        GlobalTaxIdValidator.validateTaxId(text, isEnglish)
    }
    val containerColor: Color
    val contentColor: Color
    val label: String
    when (result) {
        is GlobalTaxValidationResult.Valid -> {
            containerColor = ClayTheme.colors.badgeGreen.background
            contentColor = ClayTheme.colors.badgeGreen.text
            label = if (result.type == GlobalTaxIdType.US_EIN) einValidLabel else vatValidLabel
        }
        is GlobalTaxValidationResult.Invalid -> {
            containerColor = ClayTheme.colors.badgeRed.background
            contentColor = ClayTheme.colors.badgeRed.text
            label = invalidLabel
        }
        GlobalTaxValidationResult.Empty -> return
    }

    Surface(
        modifier = modifier.padding(top = 6.dp),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * The live checksum verdict for a national-ID field: green while the Modulo-11 check
 * holds, red while it does not, absent while the field is empty. Judges keystrokes
 * only — writing the audit verdict stays the pipeline's job.
 */
@Composable
private fun NationalIdChip(
    result: IdentifierValidationResult,
    modifier: Modifier = Modifier,
) {
    if (result is IdentifierValidationResult.Empty) return

    val containerColor: Color
    val contentColor: Color
    val label: String
    when (result) {
        is IdentifierValidationResult.Valid -> {
            containerColor = ClayTheme.colors.badgeGreen.background
            contentColor = ClayTheme.colors.badgeGreen.text
            label = "معتبر ✅"
        }
        is IdentifierValidationResult.Invalid -> {
            containerColor = ClayTheme.colors.badgeRed.background
            contentColor = ClayTheme.colors.badgeRed.text
            label = "نامعتبر ❌"
        }
        IdentifierValidationResult.Empty -> return
    }

    Surface(
        modifier = modifier.padding(top = 6.dp),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
