package com.invoiceextract.app.data.batch

import android.graphics.Bitmap
import android.util.Log
import com.invoiceextract.app.data.batch.model.BatchItemResult
import com.invoiceextract.app.data.batch.model.BatchProgressState
import com.invoiceextract.app.data.batch.model.ItemStatus
import com.invoiceextract.app.data.processor.DocumentProcessor
import com.invoiceextract.domain.extractor.InvoiceAiExtractor
import com.invoiceextract.domain.extractor.OcrEngine
import com.invoiceextract.domain.model.Invoice as DomainInvoice
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.validation.InvoiceValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The batch extraction engine (Phase 10.1).
 *
 * Runs the single-invoice pipeline — [DocumentProcessor] → [OcrEngine] →
 * [InvoiceAiExtractor] → [InvoiceValidator] → [InvoiceRepository] — across a whole queue
 * of documents, reporting progress as a cold [Flow] of [BatchProgressState].
 *
 * ### Why strictly sequential
 *
 * The four stages each hold scarce, non-sharable resources, and running them in
 * parallel would exhaust all of them at once:
 * - **Heap.** Every page is an ARGB_8888 bitmap of up to ~16 MB of native memory. Three
 *   concurrent five-page PDFs can hold ~240 MB alive simultaneously, and on a low-RAM
 *   device that is an OOM. One run at a time keeps the peak at one document.
 * - **Provider rate limits.** The Cloudflare worker fronts an LLM with a per-minute
 *   request budget; a parallel fan-out would burn through it in the first second and
 *   429 the remainder of the batch for minutes.
 * - **ML Kit.** The recognizer is a single-image client and its own concurrency story is
 *   unclear; serializing keeps it in the state it was designed for.
 *
 * The throughput cost is real but acceptable: the wall-clock is dominated by OCR and LLM
 * latency per file, not by CPU parallelism, and a serial queue trades a little speed for
 * a guarantee that a 20-file batch cannot OOM or rate-limit itself to death.
 *
 * ### Fault isolation
 *
 * One file's failure never aborts the run. Each document is processed inside its own
 * `try`/`catch` boundary, and the catch is the broadest possible — a corrupt PDF
 * decoding into a [OutOfMemoryError], a `null` bitmap array or an unchecked provider
 * crash all land there — so a batch of 50 files with 5 bad ones still yields 45 saved
 * invoices. The error is logged with its file name for the developer and reduced to a
 * Persian message for the user; the run advances to the next item.
 *
 * Persistence failures are deliberately *not* swallowed into a silent success. If an
 * extracted invoice cannot be written, the item is `FAILED`: showing the user a
 * "success" for an invoice that exists nowhere on disk would lose a financial record.
 *
 * ### Bitmap ownership
 *
 * [DocumentProcessorImpl] allocates the page bitmaps and hands them to the caller, so
 * this class owns their release. [MlKitOcrEngine] recycles each page as soon as it has
 * read it, but it only walks the list on the happy path: on every failure branch after
 * `processDocument` succeeds the unreleased pages would otherwise leak for the rest of
 * the process. The `finally` block below sweeps them, and recycling an already-recycled
 * bitmap is a documented no-op, so overlapping ownership is harmless.
 *
 * ### Cancellation
 *
 * The run lives in the caller's scope (typically `viewModelScope`). Collecting in a
 * cancellable scope tears the whole thing down: the current file stops wherever it is
 * and no further item is started. Cancellation is never converted into an item failure
 * — the user leaving the screen is not a file error, and a batch that stops on
 * cancellation must not fill its rows with fake "FAILED" entries.
 */
class BatchProcessingCoordinator(
    private val documentProcessor: DocumentProcessor,
    private val ocrEngine: OcrEngine,
    private val aiExtractor: InvoiceAiExtractor,
    private val invoiceValidator: InvoiceValidator,
    private val invoiceRepository: InvoiceRepository,
) {

    /**
     * Processes [files] one at a time and emits the state after every change.
     *
     * The [Flow] is cold: nothing runs until it is collected, and collecting it twice
     * re-runs the batch. Progress emissions are bounded — one per item transition, plus
     * the initial and final states — so the UI recomposes a handful of times per file
     * rather than on a hot stream.
     *
     * @param files The staged documents with their `isPdf` flag. Empty list is legal and
     *   yields a single finished state, so a UI can call this unconditionally.
     * @return A cold [Flow] of [BatchProgressState]. Collecting in a cancellable scope
     *   cancels the run; the terminal emission carries `isFinished = true`.
     */
    fun processBatch(files: List<Pair<File, Boolean>>): Flow<BatchProgressState> = flow {
        val queue = files.map { (file, isPdf) ->
            BatchItemResult(file = file, isPdf = isPdf)
        }

        // Snapshot at the boundary: the queue list is never aliased by the emitted
        // state below, so a UI that keeps a list reference cannot mutate the run.
        var state = BatchProgressState(
            totalCount = queue.size,
            items = queue,
        )
        emit(state)

        for (index in queue.indices) {
            val item = queue[index]

            // Pace the run: the worker fronts a free-tier LLM with a per-minute request
            // budget, so the loop waits between items instead of bursting. Skipped before
            // the first file (there is nothing to space it from) and after the last, so a
            // single-item batch pays no latency penalty. The delay is cancellable —
            // leaving the screen during the wait tears the run down immediately.
            if (index > 0) delay(EXTRACTION_PACE_MS)

            state = state.copy(
                currentItemName = item.file.name,
                items = state.items.toMutableList().apply {
                    this[index] = item.copy(status = ItemStatus.PROCESSING)
                },
            )
            emit(state)

            // Per-file isolation boundary. Everything from the decode through the
            // repository write is inside, so no failure in one document can escape
            // into the loop and abort the remaining files.
            val outcome = try {
                processSingle(item.file, item.isPdf)
            } catch (cancel: CancellationException) {
                // Structured concurrency: unwinding the scope is not a file failure.
                // Throwing propagates the cancellation out of the flow collector.
                throw cancel
            } catch (failure: Throwable) {
                Log.e(TAG, "Batch item failed: ${item.file.name}", failure)
                BatchOutcome.Error(persianMessageFor(failure))
            }

            state = when (outcome) {
                is BatchOutcome.Extraction -> {
                    val validated = invoiceValidator.validate(outcome.invoice)

                    // A database failure must not masquerade as success: an invoice
                    // that is not on disk is a lost financial record.
                    val saved = invoiceRepository.saveInvoice(validated)
                    if (saved.isFailure) {
                        val cause = saved.exceptionOrNull()
                        Log.e(TAG, "Could not persist invoice for ${item.file.name}", cause)
                        state.markItem(index) { item.failed(MESSAGE_PERSIST_FAILED) }
                    } else {
                        state.markItem(index) { item.succeeded(validated) }
                    }
                }

                is BatchOutcome.Error ->
                    state.markItem(index) { item.failed(outcome.message) }
            }

            emit(state)
        }

        // Terminal state. The counters already reflect the final tally; only the
        // "in flight" marker is cleared, and isFinished flips the UI to its summary.
        emit(state.copy(currentItemName = null, isFinished = true))
    }.flowOn(Dispatchers.Default)

    /**
     * The single-invoice pipeline for one file, with no failure handling of its own.
     *
     * Every stage returns a [Result], but the stages are chained with `getOrElse` so a
     * failure short-circuits to the caller's per-file catch instead of being unwound
     * stage by stage. An empty OCR result is treated as a hard failure rather than an
     * invoice with no items: extracting nothing from a document has no useful
     * downstream state, and reporting "0 invoices succeeded" for a readable file would
     * hide a real OCR defect behind a persisted shell invoice.
     *
     * @return The extracted [Invoice], or [BatchOutcome.Error] for a stage failure.
     */
    private suspend fun processSingle(file: File, isPdf: Boolean): BatchOutcome {
        // 1. Rasterize. Allocates the page bitmaps; released by the finally below.
        val pages: List<Bitmap> = documentProcessor.processDocument(file, isPdf)
            .getOrElse { cause ->
                Log.w(TAG, "Preprocessing failed for ${file.name}", cause)
                return BatchOutcome.Error(persianMessageFor(cause))
            }

        var cancelled = false

        try {
            // 2. OCR. Consumes and recycles each page as it reads it.
            val ocrText = ocrEngine.extractText(pages).getOrElse { cause ->
                Log.w(TAG, "OCR failed for ${file.name}", cause)
                return BatchOutcome.Error(persianMessageFor(cause))
            }

            if (ocrText.isBlank()) {
                Log.w(TAG, "OCR produced no text for ${file.name}")
                return BatchOutcome.Error(MESSAGE_OCR_EMPTY)
            }

            // 3. Structure. The remote LLM call; the longest hop in the pipeline.
            val invoice = aiExtractor.extractFromText(ocrText).getOrElse { cause ->
                Log.w(TAG, "AI extraction failed for ${file.name}", cause)
                return BatchOutcome.Error(persianMessageFor(cause))
            }

            return BatchOutcome.Extraction(invoice)
        } catch (cancel: CancellationException) {
            // A native OCR read may still be in flight for the page the engine handed to
            // ML Kit. Flagged so the sweep below is skipped — see the comment in finally.
            cancelled = true
            throw cancel
        } finally {
            // Release whatever the OCR engine never reached. On the happy path this is a
            // no-op over an already-recycled list, and on a stage failure it is the only
            // thing keeping ~80 MB from leaking per failed five-page document.
            //
            // Deliberately skipped on cancellation: [MlKitOcrEngine] leaves the page it
            // handed to the recognizer un-recycled when the coroutine is cancelled,
            // because ML Kit's task keeps running and may still be reading those pixels
            // natively. Recycling that bitmap concurrently can corrupt native memory and
            // kill the process with a signal no `catch` can intercept. The task releases
            // the bitmap itself when it settles, and every page after it was already
            // freed by the engine's own sweep.
            if (!cancelled) {
                pages.forEach { page -> if (!page.isRecycled) page.recycle() }
            }
        }
    }

    /**
     * Maps any throwable to a Persian message the UI can display verbatim.
     *
     * Deliberately coarse: the user cannot act on the difference between a `SocketTimeout`
     * and a `JsonDecodingException`, so every unexpected failure gets one message and the
     * real class name goes to the log. The known pipeline types carry their own
     * already-Persian message and are passed through.
     */
    private fun persianMessageFor(throwable: Throwable): String {
        val known = throwable.message?.takeIf { it.contains(PERSIAN_INDICATOR) }
        return known ?: MESSAGE_GENERIC_FAILURE
    }

    /**
     * Marks [index] as completed, using [transform] to build the terminal item.
     *
     * The counters are derived from the list rather than incremented, so they can never
     * disagree with the rows the UI is rendering — the state is always exactly what the
     * items say.
     */
    private fun BatchProgressState.markItem(
        index: Int,
        transform: (BatchItemResult) -> BatchItemResult,
    ): BatchProgressState {
        val updated = transform(items[index])
        val items = items.toMutableList().apply { this[index] = updated }
        return copy(
            completedCount = items.count { it.status.isTerminal() },
            successCount = items.count { it.status == ItemStatus.SUCCESS },
            failureCount = items.count { it.status == ItemStatus.FAILED },
            items = items,
        )
    }

    /** Terminal helper: `true` for the two states that end an item's lifecycle. */
    private fun ItemStatus.isTerminal(): Boolean =
        this == ItemStatus.SUCCESS || this == ItemStatus.FAILED

    private fun BatchItemResult.succeeded(invoice: DomainInvoice): BatchItemResult =
        copy(status = ItemStatus.SUCCESS, invoice = invoice, errorMessage = null)

    private fun BatchItemResult.failed(message: String): BatchItemResult =
        copy(status = ItemStatus.FAILED, invoice = null, errorMessage = message)

    /**
     * Internal result of one file's pipeline run: either a usable [Invoice] or a
     * Persian failure message. Sealed so the compiler proves the two cases are handled.
     */
    private sealed class BatchOutcome {
        class Extraction(val invoice: DomainInvoice) : BatchOutcome()
        class Error(val message: String) : BatchOutcome()
    }

    private companion object {
        private const val TAG = "BatchCoordinator"

        /**
         * Cheap heuristic for "this message is already Persian". The domain and data
         * layers put Persian text in their exception messages; a stack trace or an
         * OkHttp message does not contain it, so it falls back to the generic message.
         */
        private const val PERSIAN_INDICATOR = "ی"

        private const val MESSAGE_OCR_EMPTY =
            "متن قابل استخراجی از این فایل یافت نشد. ممکن است تصویر تار یا فاقد متن باشد."

        private const val MESSAGE_PERSIST_FAILED =
            "خطا هنگام ذخیره فاکتور استخراج‌شده در حافظه."

        private const val MESSAGE_GENERIC_FAILURE =
            "خطا هنگام پردازش این فایل. لطفاً دوباره تلاش کنید."

        /**
         * Pause between consecutive extractions, in ms. Keeps the batch under the free-tier
         * rate limit (15 requests/minute) so a large queue does not 429 itself.
         */
        private const val EXTRACTION_PACE_MS = 3000L
    }
}
