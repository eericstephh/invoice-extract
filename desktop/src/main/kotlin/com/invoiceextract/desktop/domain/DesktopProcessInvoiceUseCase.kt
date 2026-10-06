package com.invoiceextract.desktop.domain

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.document.DesktopDocumentProcessor
import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.desktop.domain.validation.withGlobalTaxIdAudit
import com.invoiceextract.desktop.domain.validation.withNationalIdAudit
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The whole offline desktop pipeline behind one call, mirroring the mobile app's
 * `ProcessInvoiceUseCase` so the presentation layer looks identical on both fronts.
 *
 * Three stages run in strict sequence, each one able to fail independently:
 *
 *  1. **Extract** — [DesktopDocumentProcessor] reads the invoice document: the PDF text
 *     layer for digital PDFs, or image validation for photos and scans. Nothing
 *     downstream can proceed without text, so a failure here short-circuits the whole call.
 *  2. **Structure** — [LocalOllamaAiExtractor] turns that text into a domain
 *   [Invoice] via the locally running model. This is the slow stage: local LLM
 *   reasoning is measured in seconds, not milliseconds. Ghost subtotal rows the
 *   model echoes as line items ([InvoiceLineItemSanitizer]) are stripped here,
 *   before enrichment, so they never teach the mapping table either.
 *  3. **Enrich** — [ProductMappingRepository] resolves each extracted line item to
 *     the merchant's internal warehouse code. Unmapped items keep a `null` code and
 *     flow on untouched; enrichment never fails the pipeline — a store hiccup degrades
 *     to unmapped lines, because a mapping lookup must never lose an invoice.
 *  4. **Validate** — [InvoiceValidator] reconciles the arithmetic and completeness,
 *     returning a copy that carries the verdict on [Invoice.validationStatus].
 *
 * **No unwrapping exceptions.** Each stage returns [Result]; a failure is threaded
 * straight out as a failure rather than re-thrown, so the caller sees the *originating*
 * typed exception (a [com.invoiceextract.desktop.data.document.DocumentProcessingException]
 * or a [com.invoiceextract.desktop.data.ai.LocalExtractionException]) with its original
 * Persian message, not a generic wrapper that erases which stage broke.
 *
 * Validation never fails by contract — it is a pure function that reports problems on
 * the returned copy — so its output is wrapped in [Result.success] unconditionally.
 *
 * @param documentProcessor Reads and sanitizes the document.
 * @param ollamaExtractor The local AI engine behind the extraction stage.
 * @param mappingRepository Resolves warehouse codes for the enrichment stage.
 * @param invoiceValidator The financial-integrity gate of the final stage.
 */
class DesktopProcessInvoiceUseCase(
    private val documentProcessor: DesktopDocumentProcessor,
    private val ollamaExtractor: LocalOllamaAiExtractor,
    private val mappingRepository: ProductMappingRepository,
    private val invoiceValidator: InvoiceValidator,
) {

    /**
     * Runs the full pipeline over [file].
     *
     * @param file A local invoice document.
     * @return [Result.success] with the validated [Invoice], or [Result.failure]
     *   carrying the typed exception of whichever stage broke, with a Persian message.
     */
    suspend fun processInvoice(file: File): Result<Invoice> = withContext(Dispatchers.Default) {
        // Dispatchers.Default, not IO: stage 1 already moves itself onto IO, and stage 2
        // is a suspend call that never parks a thread while the model reasons. This
        // scope only orchestrates, so it belongs on the CPU-oriented dispatcher.

        // Stage 1: text. A failure here means there is nothing to structure, so it is
        // threaded out unchanged.
        val text = documentProcessor.extractText(file).getOrElse { cause ->
            return@withContext Result.failure(cause)
        }

        // Stage 2: structure. The model may refuse, time out or return unparsable JSON;
        // its typed failure is threaded out unchanged too.
        val rawInvoice = ollamaExtractor.extractFromText(text).getOrElse { cause ->
            return@withContext Result.failure(cause)
        }

        // Ghost subtotal rows the model echoed as line items («جمع کل»,
        // `subtotal`, …) are stripped before enrichment: they would corrupt
        // ledgers and the top-items leaderboard, and must never teach the
        // mapping table.
        val invoice = rawInvoice.copy(items = InvoiceLineItemSanitizer.sanitize(rawInvoice.items))

        // Stage 3: enrichment. Each line looks up its warehouse code; a lookup that
        // throws (a damaged mapping store) degrades to unmapped lines rather than
        // failing the invoice — the code column is convenience, the invoice is the job.
        // Blank names skip the lookup: without a name there is no good to map.
        val enrichedItems = invoice.items.map { item ->
            if (item.name.isBlank()) {
                item
            } else {
                val code = runCatching {
                    mappingRepository.findMapping(item.name, invoice.sellerName)
                }.getOrNull()?.internalProductCode?.ifBlank { null }
                if (code == null) item else item.copy(productCode = code)
            }
        }

        // Stage 4: integrity. Pure by contract — it reports on the returned copy rather
        // than throwing — so the validated invoice is always a success. `copy` carries
        // the enriched codes through validation untouched. The origin file is stamped
        // here too, once for every path through the pipeline (single and batch alike),
        // so saved invoices remember what to preview without a second write. The
        // national-ID audit rides on the validated copy, flagging bad identifiers as
        // review warnings before the invoice ever reaches the window.
        val stamped = invoice.copy(
            items = enrichedItems,
            sourceFilePath = file.absolutePath,
        )
        Result.success(invoiceValidator.validate(stamped).withNationalIdAudit().withGlobalTaxIdAudit())
    }
}
