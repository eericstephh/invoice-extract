package com.invoiceextract.app.domain.usecase

import android.graphics.Bitmap
import com.invoiceextract.app.data.local.quota.DailyQuotaManager
import com.invoiceextract.app.data.processor.DocumentProcessor
import com.invoiceextract.domain.extractor.InvoiceAiExtractor
import com.invoiceextract.domain.extractor.OcrEngine
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.quota.QuotaExceededException
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * The single entry point of the extraction pipeline.
 *
 * Orchestrates the three stages that turn a staged document file into a structured
 * [Invoice], and reports progress as it goes. This is the only pipeline surface the
 * ViewModels are allowed to see: they know nothing about bitmaps, OCR engines or
 * extraction models, so the whole pipeline can be reworked behind this signature.
 *
 * The stages run strictly in order because each one consumes the previous one's output,
 * and because the memory profile demands it: a document can hold several megabytes of
 * bitmaps at once, and the stages are written so that only one stage's allocations are
 * alive at a time.
 *
 * Progress is delivered through [onProgress] rather than through the returned [Flow]
 * because the [Flow] carries exactly one value — the final outcome — while the stage
 * callback may fire several times before it. The whole pipeline is dispatched onto
 * [Dispatchers.Default]: document rasterization, OCR and parsing are all CPU-bound, and
 * running them on the caller's dispatcher would block a UI or IO thread for the whole
 * duration of the document.
 */
class ProcessInvoiceUseCase(
    private val documentProcessor: DocumentProcessor,
    private val ocrEngine: OcrEngine,
    private val aiExtractor: InvoiceAiExtractor,
    private val invoiceValidator: InvoiceValidator,
    private val quotaManager: DailyQuotaManager,
) {

    /**
     * Coarse-grained progress of the pipeline, coarse enough to be meaningful to the
     * user and fine enough to make a multi-second run feel responsive.
     */
    enum class ProcessingStage {
        /** Decoding, EXIF-correcting and rasterizing the document into page bitmaps. */
        PREPARING_IMAGE,

        /** Running ML Kit over every page and normalizing the Persian text. */
        RUNNING_OCR,

        /** Turning the recognized text into a structured [Invoice]. */
        PARSING_STRUCTURE,
    }

    /**
     * The pipeline's only failure type. Carries the [stage] that failed so the UI can
     * tell the user *where* it broke, which is far more actionable than a raw engine
     * error, and keeps the original [cause] for logging.
     */
    class ProcessingException(
        val stage: ProcessingStage,
        cause: Throwable? = null,
    ) : Exception("Invoice processing failed at stage ${stage.name}", cause)

    /**
     * Runs the full pipeline over [file].
     *
     * @param file        The staged document under the app's private cache.
     * @param isPdf       `true` when [file] is a PDF, `false` when it is an image.
     * @param onProgress  Invoked on [Dispatchers.Default] immediately before each stage
     *                    starts, with the [ProcessingStage] that is about to run. It is
     *                    safe to update UI state from it directly.
     * @return A cold [Flow] that emits exactly one [Result]: [Result.success] with the
     *         extracted [Invoice], or [Result.failure] carrying a [ProcessingException]
     *         naming the stage that failed, or a [QuotaExceededException] when the daily
     *         free-tier limit has been spent (checked before any work begins). Cancellation
     *         of the collecting coroutine cancels the pipeline and is never wrapped into a
     *         failure.
     */
    operator fun invoke(
        file: File,
        isPdf: Boolean,
        onProgress: (ProcessingStage) -> Unit,
    ): Flow<Result<Invoice>> = flow {
        emit(runPipeline(file, isPdf, onProgress))
    }.flowOn(Dispatchers.Default)

    /**
     * The pipeline itself, as a plain suspending function so that failures are ordinary
     * `return`s and the bitmap cleanup can lean on `try`/`finally`.
     */
    private suspend fun runPipeline(
        file: File,
        isPdf: Boolean,
        onProgress: (ProcessingStage) -> Unit,
    ): Result<Invoice> {
        // 0. Quota gate, before any CPU, memory or network is spent. Checked first so an
        //    exhausted free tier fails immediately and cheaply, and the user is told to
        //    come back tomorrow rather than waiting through two pipeline stages to be
        //    refused. A user with their own API key always passes this gate.
        if (!quotaManager.canPerformScan()) {
            return Result.failure(QuotaExceededException())
        }

        // 1. Rasterize the document into normalized page bitmaps.
        onProgress(ProcessingStage.PREPARING_IMAGE)
        val pages: List<Bitmap> = documentProcessor.processDocument(file, isPdf)
            .getOrElse { cause -> return failure(ProcessingStage.PREPARING_IMAGE, cause) }

        try {
            // 2. Read the text off every page. The engine consumes the bitmaps as it
            //    goes and recycles each page the instant it is done with it.
            onProgress(ProcessingStage.RUNNING_OCR)
            val ocrText: String = ocrEngine.extractText(pages)
                .getOrElse { cause -> return failure(ProcessingStage.RUNNING_OCR, cause) }

            // 3. Structure the recognized text into an invoice, then verify it. The
            //    validator is a pure function, so it can never fail the stage: a
            //    problematic invoice is still emitted, carrying its verdict in
            //    [Invoice.validationStatus] for the Review screen to act on.
            onProgress(ProcessingStage.PARSING_STRUCTURE)
            val invoice: Invoice = aiExtractor.extractFromText(ocrText)
                .getOrElse { cause -> return failure(ProcessingStage.PARSING_STRUCTURE, cause) }

            // Charge the extraction only now that it has actually produced a structured,
            // validated invoice. Charging earlier would burn a free extraction on a run
            // that failed in OCR or parsing; charging later would let a cancelled run slip
            // through uncounted. This is the single point a free-tier scan is consumed.
            val validated = invoiceValidator.validate(invoice)
            quotaManager.consumeScan()

            return Result.success(validated)
        } finally {
            // The OCR engine owns the page bitmaps and releases each one as soon as it
            // has read it — and, on failure, every page it never reached. By the time
            // control returns here nothing is left to free. This sweep is a deliberate
            // safety net: it keeps the use case's "no leaked pixel memory" guarantee
            // true even if a future engine forgets, and recycling an already-recycled
            // bitmap is a documented no-op, so it costs nothing in the common path.
            pages.forEach { page -> if (!page.isRecycled) page.recycle() }
        }
    }

    private fun failure(stage: ProcessingStage, cause: Throwable): Result<Invoice> =
        Result.failure(ProcessingException(stage, cause))
}
