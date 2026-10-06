package com.invoiceextract.app.data.processor

import android.graphics.Bitmap
import java.io.File

/**
 * Transforms a staged invoice document into normalized bitmaps for OCR.
 *
 * Implementations must be safe to call from a background scope: decoding and
 * rendering are blocking, CPU-heavy operations.
 */
interface DocumentProcessor {

    /**
     * Processes [file] into one [android.graphics.Bitmap] per page.
     *
     * @param file The staged document under `cacheDir/invoices/`.
     * @param isPdf `true` when [file] is a PDF, `false` when it is an image.
     * @return [Result.success] with a non-empty list of valid bitmaps, or
     *   [Result.failure] carrying a [ProcessorException] with a user-ignorable
     *   description of what went wrong.
     */
    suspend fun processDocument(file: File, isPdf: Boolean): Result<List<Bitmap>>
}

/**
 * Single failure type for the whole preprocessing pipeline.
 *
 * Kept deliberately simple: callers need a message to display and, optionally,
 * the root cause for logging.
 */
class ProcessorException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
