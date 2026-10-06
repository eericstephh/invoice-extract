package com.invoiceextract.desktop.data.document

import com.invoiceextract.desktop.data.ocr.BlankImageException
import com.invoiceextract.desktop.data.ocr.DesktopImageOcrEngine
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The desktop document stage for invoice files, 100% offline.
 *
 * Two kinds of input reach this stage and they are handled on two different paths:
 *
 * - **PDF** — most desktop invoices are *exported* rather than scanned, so they carry a
 *   real embedded text layer. For those, [extractText] pulls the characters directly out
 *   of the PDF — no rasterization, no OCR pass, no model call — which turns the slowest
 *   stage of the mobile pipeline into a sub-10-millisecond read. A bitmap-only scan has
 *   no text layer, so each page is rendered at print resolution and read by the OCR
 *   engine instead; the fast path is always tried first because it is orders of
 *   magnitude cheaper than rendering.
 * - **Image (`png`/`jpg`/`jpeg`)** — a photograph or scan of an invoice has no text layer
 *   by construction, so it goes straight to the OCR engine, which validates, recognizes
 *   and sanitizes it in one call.
 *
 * Both paths converge on the same output contract — non-blank normalized text — so the
 * structure stage downstream cannot tell a photo from an export and never has to.
 *
 * **Resource safety.** [Loader.loadPDF] is opened inside `use { }`, so the document,
 * its page buffers and its scratch file are released on every path — success, a blank
 * result and every exception. Rendered page rasters are method-local and die with the
 * iteration that made them. No handle is left to the OS.
 *
 * **Errors.** Every failure — a corrupt file, an encrypted document, an unreadable
 * photo, an unsupported suffix — is folded into one [DocumentProcessingException]
 * inside [Result.failure]. Callers never have to catch; engineering detail stays on the
 * exception's cause for logging while the user sees a Persian sentence.
 *
 * @param normalizer Repairs the Arabic-script and zero-width artifacts the PDF text
 *   layer emits, so the model receives clean text and no invisible noise.
 * @param imageOcrEngine The offline recognizer behind photos, scans and raster PDFs.
 */
class DesktopDocumentProcessor(
    private val normalizer: DesktopPersianNormalizer,
    private val imageOcrEngine: DesktopImageOcrEngine,
) {

    /**
     * Extracts and sanitizes the text of [file].
     *
     * Dispatches on the extension: `.pdf` tries the PDFBox text layer first and falls
     * back to rendering plus OCR for bitmap-only scans, `.png`/`.jpg`/`.jpeg` go
     * straight to the OCR engine, and anything else is rejected before any parser sees
     * it — so a renamed text file fails with "unsupported format" instead of a misleading
     * "unreadable PDF".
     *
     * @param file A PDF or image invoice on the local filesystem.
     * @return [Result.success] with non-blank normalized text, or [Result.failure]
     *   carrying a [DocumentProcessingException] whose message is user-facing Persian
     *   and whose cause is the original failure.
     */
    suspend fun extractText(file: File): Result<String> = withContext(Dispatchers.IO) {
        // Photos skip the document machinery entirely: the engine validates, recognizes
        // and sanitizes in one call. Its failure is re-wrapped only to keep this
        // stage's single-failure-type contract — the precise Persian verdict rides
        // along untouched, which is what the pipeline renders.
        if (InvoiceFileFormats.isImage(file.extension)) {
            return@withContext imageOcrEngine.extractFromImage(file).recoverCatching { cause ->
                if (cause is CancellationException) throw cause
                throw DocumentProcessingException(cause.message ?: UNEXPECTED_MESSAGE, cause)
            }
        }

        // The render fallback below calls the suspending engine, which a non-suspending
        // runCatching block cannot host — so this branch uses an explicit try/catch with
        // identical semantics: cancellation unwinds, everything else becomes a failure.
        try {
            when {
                file.extension.equals(InvoiceFileFormats.PDF, ignoreCase = true) ->
                    Result.success(extractPdfText(file))

                else -> Result.failure(DocumentProcessingException(UNSUPPORTED_FORMAT_MESSAGE))
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            Result.failure(
                when (cause) {
                    // Every verdict built above is already the right typed failure.
                    is DocumentProcessingException -> cause

                    // An encrypted PDF refuses to decrypt and a corrupt one makes the
                    // parser give up; the user cannot tell the two apart and does not
                    // care, so they share one message.
                    is IOException -> DocumentProcessingException(PDF_UNREADABLE_MESSAGE, cause)

                    else -> DocumentProcessingException(UNEXPECTED_MESSAGE, cause)
                },
            )
        }
    }

    /**
     * The PDFBox fast path, with a render-plus-OCR fallback for bitmap-only scans.
     */
    private suspend fun extractPdfText(file: File): String {
        // Loader.loadPDF owns the file handle; `use` guarantees it is closed on every
        // return from this block, including the fallthrough below.
        Loader.loadPDF(file).use { document ->
            val cleaned = normalizer.normalize(PDFTextStripper().getText(document))
            if (cleaned.isNotBlank()) return cleaned

            // Blank text layer: exported content would have shown up by now, so this is
            // a scan (or an empty document). Rendering answers which.
            return extractScannedPdfText(document)
        }
    }

    /**
     * Reads a raster-only PDF by rendering each page and OCR-ing it.
     *
     * Pages render at print resolution: Tesseract's accuracy collapses below ~150 DPI
     * and rendering costs grow quadratically above it, so 200 DPI is the working
     * compromise. Each page's text is delimited with its physical page number, because
     * a five-page invoice that arrives as one undifferentiated block is unauditable.
     *
     * Blank pages are skipped — one empty sheet in a five-page scan must not discard
     * the other four — but a page-level *error* fails the document: silently dropping a
     * page the recognizer choked on would hand the model an incomplete invoice dressed
     * as a complete one. No text on any page keeps the original scanned-document
     * verdict, which names the actual condition.
     */
    private suspend fun extractScannedPdfText(document: PDDocument): String {
        val renderer = PDFRenderer(document)
        val pages = StringBuilder()
        var recognized = 0
        for (pageIndex in 0 until document.numberOfPages) {
            val image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI)
            val text = imageOcrEngine.extractFromBufferedImage(image).getOrElse { cause ->
                // A blank page is not a failed document: the sheet simply carries no
                // text, so it contributes an empty string and the `isBlank` check below
                // skips it. Anything else the recognizer choked on fails the document —
                // the engine's message is already the precise Persian verdict and is
                // re-wrapped only to keep the stage's single-failure-type contract.
                if (cause is BlankImageException) return@getOrElse ""
                throw DocumentProcessingException(cause.message ?: UNEXPECTED_MESSAGE, cause)
            }
            if (text.isBlank()) continue

            if (recognized > 0) pages.appendLine()
            pages.appendLine("--- صفحه ${pageIndex + 1} ---")
            pages.append(text.trim())
            recognized++
        }

        if (recognized == 0) {
            throw DocumentProcessingException(BLANK_OR_SCANNED_MESSAGE)
        }

        return normalizer.normalize(pages.toString())
    }
}

/**
 * The single failure type of the desktop document stage.
 *
 * Kept deliberately simple, mirroring the mobile pipeline's `ProcessorException`:
 * callers need a message to display and, optionally, the root cause for logging.
 */
class DocumentProcessingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private const val BLANK_OR_SCANNED_MESSAGE =
    "متنی در این سند یافت نشد یا فاکتور به صورت عکس اسکن شده است."

private const val PDF_UNREADABLE_MESSAGE =
    "فایل PDF قابل باز کردن نیست، رمزگذاری شده یا خراب است."

private const val UNSUPPORTED_FORMAT_MESSAGE =
    "فرمت فایل پشتیبانی نمی‌شود؛ فقط PDF و تصویر (PNG یا JPG) پذیرفته می‌شود."

private const val UNEXPECTED_MESSAGE = "خطای ناشناخته هنگام پردازش سند."

private const val RENDER_DPI = 200f
