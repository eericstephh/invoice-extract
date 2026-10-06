package com.invoiceextract.domain.extractor

/**
 * Platform-agnostic OCR contract.
 *
 * The domain layer deliberately knows nothing about bitmaps, camera frames or PDF
 * renderers, so the input is typed as [Any] and each platform implementation is free to
 * accept whatever it supports — typically `ByteArray` for a shared buffer,
 * `java.io.File` on JVM, `android.graphics.Bitmap` on Android or
 * `androidx.compose.ui.graphics.ImageBitmap` on desktop. Keeping the contract in a pure
 * Kotlin module is what allows the same domain code to be shared across targets.
 */
interface OcrEngine {

    /**
     * Runs optical character recognition over [inputSource].
     *
     * @param inputSource The image to read. Its runtime type is defined by the
     *                    implementation; see the class documentation for the expected set.
     * @return [Result.success] with the recognized text (empty when nothing could be
     *         read), or [Result.failure] when the source is unsupported, unreadable, or
     *         the engine reports an error.
     */
    suspend fun extractText(inputSource: Any): Result<String>
}
