package com.invoiceextract.app.data.processor

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Routes a staged document to the right renderer and hands back normalized
 * bitmaps.
 *
 * All pixel work is pushed onto [Dispatchers.Default]: bitmap decoding and PDF
 * rasterization are CPU-bound, and running them on the caller's dispatcher would
 * block a UI or IO thread for seconds at a time.
 */
class DocumentProcessorImpl(
    private val imagePreprocessor: ImagePreprocessor,
    private val pdfBitmapRenderer: PdfBitmapRenderer,
) : DocumentProcessor {

    override suspend fun processDocument(file: File, isPdf: Boolean): Result<List<Bitmap>> {
        return withContext(Dispatchers.Default) {
            Log.d(TAG, "processDocument: ${file.name} (isPdf=$isPdf)")

            val result = if (isPdf) {
                pdfBitmapRenderer.render(file)
            } else {
                imagePreprocessor.process(file).map { listOf(it) }
            }

            // The pipeline contract: callers always receive bitmaps that are
            // allocated, un-recycled and usable.
            result.onSuccess { bitmaps ->
                if (bitmaps.isEmpty()) {
                    Log.w(TAG, "Document produced no pages: ${file.name}")
                }
                bitmaps.forEach { bitmap ->
                    check(bitmap.width > 0 && bitmap.height > 0 && !bitmap.isRecycled) {
                        "Invalid bitmap produced for ${file.name}"
                    }
                }
            }

            result
        }
    }

    private companion object {
        private const val TAG = "DocumentProcessor"
    }
}
