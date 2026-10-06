package com.invoiceextract.app.data.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.invoiceextract.domain.extractor.OcrEngine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The Android implementation of [OcrEngine], built on ML Kit's bundled text recognizer
 * (`com.google.mlkit:text-recognition`).
 *
 * NOTE: ML Kit currently ships no Arabic/Persian recognizer — its script coverage is
 * Latin, Chinese, Devanagari, Japanese and Korean — so this engine runs the Latin
 * recognizer for now. [PersianTextNormalizer] is kept in the pipeline for the day a
 * Persian-capable engine replaces this client.
 *
 * ML Kit ships no dedicated Persian model, and it does not need one: Persian and
 * Arabic share every glyph the recognizer must detect. What the model *does* get wrong
 * for Persian is the code point of two letters and the placement of invisible joiners —
 * a purely textual defect that [PersianTextNormalizer] repairs after the fact, which is
 * far cheaper than retraining. Using the bundled (rather than downloaded) variant means
 * the model lives in the APK: recognition works with no network, no download dialog and
 * no `PLAY_SERVICES` availability check, which is what makes the pipeline usable offline.
 *
 * **Memory is the design constraint here.** Each page arrives as an ARGB_8888 bitmap
 * capped at 2048px on its longest side, i.e. up to ~16 MB of native pixel memory, and a
 * five-page invoice holds ~80 MB of them. The engine therefore processes pages strictly
 * one at a time and calls [Bitmap.recycle] the instant ML Kit hands back that page's
 * text, so the peak cost is one live bitmap plus its result string rather than the whole
 * document. The recognizer is also single-image by nature, so sequential processing costs
 * nothing in throughput. [Dispatchers.Default] is used because [InputImage] construction,
 * pixel conversion and normalization are all CPU-bound, and running them on the caller's
 * dispatcher would block an IO or UI thread for the duration of the document.
 *
 * Input is typed [Any] to satisfy the platform-agnostic domain contract; this
 * implementation accepts a [List] of [Bitmap] — exactly what [DocumentProcessor] produces
 * — and rejects anything else as a typed [OcrException] rather than a [ClassCastException].
 */
class MlKitOcrEngine(
    private val normalizer: PersianTextNormalizer = PersianTextNormalizer(),
) : OcrEngine {

    /**
     * Runs OCR over [inputSource], one page at a time.
     *
     * Every page is demarcated with a header so downstream parsing can tell where a page
     * ends — a wrapped invoice line looks identical to a continuation of the previous
     * page otherwise. The header is emitted even for pages that produced no text, so the
     * page numbering of the output always matches the page numbering of the source.
     *
     * @param inputSource A [List]<[Bitmap]> of upright page images.
     * @return [Result.success] with the page-delimited, Persian-normalized text of every
     *   page (possibly mostly empty when the pages were unreadable), or
     *   [Result.failure] carrying an [OcrException] when the input is unsupported, a page
     *   is unusable, or the engine reports an error.
     */
    override suspend fun extractText(inputSource: Any): Result<String> =
        withContext(Dispatchers.Default) {
            val pages = parsePages(inputSource) ?: run {
                Log.e(TAG, "Unsupported input: ${inputSource.javaClass.name}")
                return@withContext Result.failure(
                    OcrException("OCR requires a List<Bitmap>, got ${inputSource.javaClass.name}"),
                )
            }

            if (pages.isEmpty()) {
                Log.w(TAG, "Document produced no pages to read")
                return@withContext Result.failure(OcrException("Document has no pages to read"))
            }

            // Reject unusable bitmaps up front: handing a recycled bitmap to ML Kit
            // surfaces as an opaque native failure, which is hard to act on.
            pages.forEachIndexed { index, bitmap ->
                if (bitmap.isRecycled || bitmap.width < MIN_DIMENSION || bitmap.height < MIN_DIMENSION) {
                    Log.e(
                        TAG,
                        "Page ${index + 1} is unusable " +
                            "(recycled=${bitmap.isRecycled}, ${bitmap.width}x${bitmap.height})",
                    )
                    return@withContext Result.failure(
                        OcrException("Page ${index + 1} of ${pages.size} is not a readable image"),
                    )
                }
            }

            Log.d(TAG, "Reading ${pages.size} page(s)")

            val recognizer = try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (cause: Throwable) {
                // Reaching here almost certainly means the ML Kit artifact is missing
                // from the build; report it as an ordinary failure rather than letting a
                // NoClassDefFoundError kill the calling scope.
                Log.e(TAG, "ML Kit text recognizer is unavailable", cause)
                return@withContext Result.failure(OcrException("OCR engine is unavailable", cause))
            }

            // The index of the page currently in the hands of ML Kit. The finally block
            // uses it to identify the pages that never reached the engine at all, so a
            // cancelled run still frees every bitmap it owns.
            var handedOff = -1

            try {
                val output = StringBuilder(pages.size * ESTIMATED_CHARS_PER_PAGE)

                for (index in pages.indices) {
                    handedOff = index
                    val pageNumber = index + 1

                    // Suspends until ML Kit's callback fires, so the bitmap below stays
                    // referenced for exactly as long as the recognizer needs it.
                    val outcome = recognizePage(recognizer, pages[index])

                    // CRITICAL: by the time the coroutine resumes, the recognizer has
                    // finished with this bitmap and will never touch its pixels again.
                    // Release the native memory before the next page is even started, or
                    // a long document accumulates every page's ~16 MB until it OOMs.
                    pages[index].recycleIfNotRecycled()

                    val rawText = outcome.getOrElse { cause ->
                        Log.e(TAG, "Failed to read page $pageNumber", cause)
                        return@withContext Result.failure(
                            OcrException("Could not read page $pageNumber of ${pages.size}", cause),
                        )
                    }

                    val pageText = normalizer.normalize(rawText.text)
                    Log.d(TAG, "Page $pageNumber produced ${pageText.length} character(s)")

                    // Plain interpolation, deliberately not String.format("%d"): with a
                    // Persian/Farsi device locale, %d renders the page number in
                    // Persian-Indic digits, which would break the exact delimiter format
                    // the parser relies on. Int.toString() is locale-independent.
                    output.append("\n--- صفحه $pageNumber ---\n")
                    output.append(pageText)
                }

                Result.success(output.toString().trim())
            } finally {
                recognizer.close()

                // A cancellation mid-page lands here with the in-flight bitmap still
                // owned by an ML Kit task; recycling that one can corrupt a native pixel
                // read, so it is left alone (the task releases it when it settles).
                // Everything after it never reached the engine and is freed immediately.
                for (index in (handedOff + 1) until pages.size) {
                    pages[index].recycleIfNotRecycled()
                }
            }
        }

    /**
     * Converts one [bitmap] into recognized text via ML Kit's callback API, bridged into
     * a suspending call with [suspendCancellableCoroutine] so the caller stays free and
     * the whole run is cancellable.
     *
     * The continuation is only resumed while still active, so a page whose callbacks
     * fire after the scope was cancelled is simply discarded instead of crashing the ML
     * Kit executor thread with an already-resumed continuation.
     */
    private suspend fun recognizePage(recognizer: TextRecognizer, bitmap: Bitmap): Result<Text> {
        return suspendCancellableCoroutine { continuation ->
            // Zero degrees is correct because DocumentProcessor has already applied the
            // EXIF rotation, so every page bitmap is upright on arrival.
            val image = try {
                InputImage.fromBitmap(bitmap, ROTATION_DEGREES)
            } catch (cause: Throwable) {
                if (continuation.isActive) continuation.resume(Result.failure(cause))
                return@suspendCancellableCoroutine
            }

            try {
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        if (continuation.isActive) continuation.resume(Result.success(text))
                    }
                    .addOnFailureListener { cause ->
                        if (continuation.isActive) continuation.resume(Result.failure(cause))
                    }
            } catch (cause: Throwable) {
                // process() can throw synchronously when the recognizer was already
                // closed; turn that into a failure like any other.
                if (continuation.isActive) continuation.resume(Result.failure(cause))
            }
        }
    }

    /**
     * Validates [inputSource] against this implementation's supported type, returning the
     * page list or `null` when the input is not a homogeneous list of bitmaps.
     */
    private fun parsePages(inputSource: Any): List<Bitmap>? {
        if (inputSource !is List<*>) return null
        if (inputSource.any { it !is Bitmap }) return null
        // Every element is a Bitmap by now, so this filter is total and merely gives the
        // loop a properly typed list.
        return inputSource.filterIsInstance<Bitmap>()
    }

    private fun Bitmap.recycleIfNotRecycled() {
        if (!isRecycled) recycle()
    }

    private companion object {
        private const val TAG = "MlKitOcrEngine"

        /** Pages are already upright when they arrive; no rotation is applied here. */
        private const val ROTATION_DEGREES = 0

        /** Smallest bitmap dimension that can carry legible text. */
        private const val MIN_DIMENSION = 1

        /** Pre-sized to a typical page's worth of characters, to avoid a mid-loop grow. */
        private const val ESTIMATED_CHARS_PER_PAGE = 2048
    }
}

/**
 * Single failure type for the OCR stage.
 *
 * Mirrors [ProcessorException] of the pre-OCR pipeline: callers get a message they can
 * show and, when logging is enabled, the root cause. Keeping it here rather than in the
 * domain module preserves the rule that the domain layer knows nothing about Android or
 * ML Kit — its contract speaks only of [Result].
 *
 * @see com.invoiceextract.app.data.processor.ProcessorException
 */
class OcrException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
