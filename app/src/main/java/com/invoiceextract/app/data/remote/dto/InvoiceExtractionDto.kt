package com.invoiceextract.app.data.remote.dto

import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wire request body for `POST /api/v1/extract` (Phase 11.2).
 *
 * Mirrors the worker's `ExtractionRequest` exactly: one field, the raw OCR text.
 */
@Serializable
data class ExtractionRequestDto(
    @SerialName("ocrText") val ocrText: String,
)

/**
 * One line item on the wire.
 *
 * Every numeric field has a default, because a model can legitimately omit a
 * discount or a tax, and `coerceInputValues` on the [kotlinx.serialization.json.Json]
 * instance turns an explicit JSON `null` into that default rather than crashing.
 * [confidence] is [Float] on the wire and in the domain, so no precision is lost
 * crossing the boundary.
 */
@Serializable
data class ExtractionItemDto(
    val name: String,
    val quantity: Double = 1.0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    @SerialName("total_price") val totalPrice: Double = 0.0,
    val confidence: Float = 1.0f,
)

/**
 * Wire response for the extraction endpoint.
 *
 * Field names are snake_case to match the worker's JSON Schema verbatim;
 * [SerialName] is only needed where the Kotlin name differs. Header fields are
 * nullable — the model leaves unknown fields `null` rather than inventing them,
 * and the domain validator reports every one of them explicitly.
 */
@Serializable
data class ExtractionResponseDto(
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    val date: String? = null,
    @SerialName("seller_name") val sellerName: String? = null,
    @SerialName("seller_tax_id") val sellerTaxId: String? = null,
    @SerialName("buyer_name") val buyerName: String? = null,
    @SerialName("buyer_tax_id") val buyerTaxId: String? = null,
    val items: List<ExtractionItemDto> = emptyList(),
    val subtotal: Double = 0.0,
    @SerialName("total_tax") val totalTax: Double = 0.0,
    @SerialName("total_discount") val totalDiscount: Double = 0.0,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
    val currency: String = "TOMAN",
)

/**
 * Converts the wire model into the domain aggregate.
 *
 * This is the only place the network speaks to the domain, so it is the only
 * place that has to be defensive:
 * - Item ids are generated here, not by the model: an LLM is not a reliable
 *   source of unique keys, and a duplicate id would collide as a Room primary
 *   key and silently drop a line on save.
 * - Confidence is clamped, because [InvoiceItem]'s contract requires `0f..1f`
 *   and throws otherwise — a model emitting `1.05` must not crash the pipeline.
 * - Currency degrades to [CurrencyType.UNKNOWN], never to an exception.
 *
 * The invoice id is generated per call, matching the stub's behaviour, so an
 * extraction always produces a fresh aggregate.
 *
 * @param rawOcrText The verbatim OCR text that produced this response, kept on
 *   the invoice for auditing and re-extraction.
 */
fun ExtractionResponseDto.toDomain(rawOcrText: String): Invoice = Invoice(
    id = UUID.randomUUID().toString(),
    invoiceNumber = invoiceNumber,
    date = date,
    sellerName = sellerName,
    sellerTaxId = sellerTaxId,
    buyerName = buyerName,
    buyerTaxId = buyerTaxId,
    items = items.map { it.toDomain() },
    subtotal = subtotal,
    totalTax = totalTax,
    totalDiscount = totalDiscount,
    grandTotal = grandTotal,
    currency = parseCurrency(currency),
    rawOcrText = rawOcrText,
    validationStatus = ValidationStatus.Valid,
)

private fun ExtractionItemDto.toDomain(): InvoiceItem = InvoiceItem(
    id = UUID.randomUUID().toString(),
    name = name.trim().ifBlank { DEFAULT_ITEM_NAME },
    quantity = quantity,
    unitPrice = unitPrice,
    discount = discount,
    tax = tax,
    totalPrice = totalPrice,
    confidence = confidence.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE),
)

/** Case-insensitive currency parse; anything unrecognised becomes [CurrencyType.UNKNOWN]. */
private fun parseCurrency(value: String?): CurrencyType = when (value?.trim()?.uppercase()) {
    "TOMAN" -> CurrencyType.TOMAN
    "RIAL" -> CurrencyType.RIAL
    "USD", "$", "DOLLAR", "DOLLARS" -> CurrencyType.USD
    "EUR", "€", "EURO", "EUROS" -> CurrencyType.EUR
    "USDT", "TETHER", "₮" -> CurrencyType.USDT
    else -> CurrencyType.UNKNOWN
}

private const val DEFAULT_ITEM_NAME = "نامشخص"
private const val MIN_CONFIDENCE = 0f
private const val MAX_CONFIDENCE = 1f
