package com.invoiceextract.app.data.processor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Renders an invoice *PDF* into one [Bitmap] per page using the platform's own
 * [PdfRenderer] — no third-party PDF engine, no native cross-compilation.
 *
 * Fidelity vs. memory is the trade-off this class manages. A PDF page is laid
 * out in points at 72 DPI, which is far too coarse for Persian OCR: the dots and
 * diacritics merge into smears. Every page is therefore re-rendered at
 * [DPI_SCALE] times its point size, which approximates 300 DPI and is capped so
 * that no bitmap edge can exceed [MAX_DIMENSION] pixels.
 *
 * Every [PdfRenderer.Page], [PdfRenderer] and [ParcelFileDescriptor] opened here
 * is released through a `use { }` block, so a rendering failure mid-document can
 * never leak a file descriptor to a since-deleted cache file.
 */
class PdfBitmapRenderer {

    /**
     * Renders up to [MAX_PAGES] pages of [file] into bitmaps.
     *
     * @return [Result.success] with one [Bitmap] per rendered page (possibly
     *   empty when the PDF contains no pages), or [Result.failure] carrying a
     *   [ProcessorException] when the file is missing, not a real PDF, or cannot
     *   be rendered.
     */
    fun render(file: File): Result<List<Bitmap>> {
        return runCatching {
            if (!file.exists() || !file.canRead()) {
                throw ProcessorException("PDF file is missing or unreadable: ${file.absolutePath}")
            }

            // MODE_READ_ONLY must be used: the renderer refuses a writable pfd.
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount.coerceAtMost(MAX_PAGES)
                    if (pageCount == 0) {
                        Log.w(TAG, "PDF contains no pages: ${file.absolutePath}")
                        return@use emptyList<Bitmap>()
                    }

                    Log.d(TAG, "Rendering $pageCount page(s) of ${file.name} (document has ${renderer.pageCount})")

                    // Built imperatively rather than with `map` so that a failure on page
                    // N can recycle the pages already rendered for 0..N-1. Those bitmaps
                    // never reach the caller — the Result becomes a failure — and would
                    // otherwise leak up to ~64 MB of native memory on a five-page PDF.
                    val pages = ArrayList<Bitmap>(pageCount)
                    try {
                        (0 until pageCount).forEach { index ->
                            renderer.openPage(index).use { page ->
                                pages += renderPage(file, index, page)
                            }
                        }
                        pages
                    } catch (cause: Throwable) {
                        pages.forEach { page -> if (!page.isRecycled) page.recycle() }
                        throw cause
                    }
                }
            }
        }.recoverCatching { cause ->
            if (cause is ProcessorException) throw cause
            else {
                // PdfRenderer throws IllegalStateException for a corrupt file and
                // OutOfMemoryError when a page is pathologically large; both are
                // reported as a single typed failure.
                Log.e(TAG, "Failed to render PDF ${file.absolutePath}", cause)
                throw ProcessorException("PDF could not be rendered: ${file.name}", cause)
            }
        }
    }

    /**
     * Renders a single [page] into an ARGB_8888 bitmap.
     *
     * The destination bitmap is created at the *scaled* page size and pre-filled
     * with solid white. PDFs commonly carry a transparent background; without
     * this fill, transparent areas composite to black under OCR binarization and
     * destroy the page.
     */
    private fun renderPage(file: File, index: Int, page: PdfRenderer.Page): Bitmap {
        val pageWidth = page.width
        val pageHeight = page.height
        if (pageWidth < 1 || pageHeight < 1) {
            throw ProcessorException("Page $index of ${file.name} has an invalid size: $pageWidth x $pageHeight")
        }

        val scale = computeScale(pageWidth, pageHeight)
        val bitmapWidth = (pageWidth * scale).toInt()
        val bitmapHeight = (pageHeight * scale).toInt()

        if (bitmapWidth < 1 || bitmapHeight < 1) {
            throw ProcessorException("Page $index of ${file.name} scaled to an invalid size: $bitmapWidth x $bitmapHeight")
        }

        val bitmap = try {
            Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        } catch (cause: OutOfMemoryError) {
            Log.e(TAG, "OOM allocating ${bitmapWidth}x$bitmapHeight for page $index of ${file.name}", cause)
            throw ProcessorException("Not enough memory to render page ${index + 1} of ${file.name}", cause)
        }

        // Solid white background: prevents transparent PDF regions from rendering
        // as black, which would make the page unreadable for OCR.
        bitmap.eraseColor(Color.WHITE)

        // Map the 72 DPI PDF coordinate space onto the larger bitmap.
        val matrix = Matrix().apply { postScale(scale, scale) }

        try {
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } catch (cause: RuntimeException) {
            if (!bitmap.isRecycled) bitmap.recycle()
            Log.e(TAG, "Page $index of ${file.name} failed to render", cause)
            throw ProcessorException("Page ${index + 1} of ${file.name} could not be rendered", cause)
        }

        return bitmap
    }

    /**
     * The render scale, derived from the 72 DPI -> ~300 DPI target and then
     * clamped so that no edge of the resulting bitmap can exceed
     * [MAX_DIMENSION].
     */
    private fun computeScale(pageWidth: Int, pageHeight: Int): Float {
        val longest = maxOf(pageWidth, pageHeight)
        val scaleForDpi = TARGET_DPI / PDF_DPI

        // If the fidelity target would blow past the pixel cap, fall back to the
        // largest scale that still fits.
        return if (longest * scaleForDpi > MAX_DIMENSION) {
            (MAX_DIMENSION / longest.toFloat()).coerceAtLeast(MIN_SCALE)
        } else {
            scaleForDpi
        }.also {
            Log.d(TAG, "Page ${pageWidth}x$pageHeight -> render scale $it (~${(it * PDF_DPI).toInt()} DPI)")
        }
    }

    private companion object {
        private const val TAG = "PdfBitmapRenderer"

        /** Native PDF coordinate space, in dots per inch. */
        private const val PDF_DPI = 72f

        /** Fidelity target for the rendered raster. */
        private const val TARGET_DPI = 300f

        /**
         * Hard cap on any rendered edge. Matches the image pipeline so a page
         * can never cost more than ~16 MB of ARGB_8888.
         */
        private const val MAX_DIMENSION = 2048

        /** Never render worse than this, even for an oversized page. */
        private const val MIN_SCALE = 2.0f

        /** MVP cap: the extractor only reads the first few pages of an invoice. */
        private const val MAX_PAGES = 5
    }
}
