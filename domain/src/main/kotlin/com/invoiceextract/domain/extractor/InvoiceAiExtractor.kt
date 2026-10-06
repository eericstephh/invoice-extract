package com.invoiceextract.domain.extractor

import com.invoiceextract.domain.model.Invoice

/**
 * Contract for the AI extraction engine.
 *
 * Implementations turn the raw text produced by an [OcrEngine] into a structured
 * [Invoice]. Because the whole point of the pipeline is zero external API cost,
 * implementations are expected to run against a local or free-tier model and to
 * enforce the strict JSON Schema bundled at
 * `resources/invoice_extraction_schema.json` through the system prompt.
 */
interface InvoiceAiExtractor {

    /**
     * Converts OCR text into a structured [Invoice].
     *
     * The returned [Invoice] should carry [Invoice.rawOcrText] set to [ocrText] so the
     * extraction stays auditable and can be retried later.
     *
     * @param ocrText Verbatim text recognized by the OCR engine. May be empty or garbled.
     * @return [Result.success] with a populated [Invoice], or [Result.failure] when the
     *         text could not be parsed, the model returned an unrecoverable error, or the
     *         response violated the JSON Schema. Callers should never be forced to catch
     *         exceptions — hence `Result` instead of a throwing signature.
     */
    suspend fun extractFromText(ocrText: String): Result<Invoice>
}
