package com.invoiceextract.app.data.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Turns a staged invoice *image* (JPG or PNG) into a single normalized [Bitmap].
 *
 * Memory safety is the whole point of this class: a 4000x3000 photo decoded
 * naively costs ~48 MB of ARGB_8888 and two of them fit nowhere on a low-end
 * device. The decode therefore runs in two passes:
 *
 *  1. [BitmapFactory.Options.inJustDecodeBounds] is set so the first decode
 *     allocates *zero* pixels and only reports the image bounds.
 *  2. From those bounds an [BitmapFactory.Options.inSampleSize] is computed that
 *     subsamples the image down to at most [TARGET_MAX_DIMENSION] pixels on its
 *     longest side. Only then is the real bitmap allocated.
 *
 * After decoding, the EXIF orientation tag is honored so a phone held in
 * landscape produces an upright page. The unrotated source bitmap is recycled
 * the moment the rotated copy exists, so at most one full-size bitmap is alive
 * at any time.
 */
class ImagePreprocessor {

    /**
     * Decodes and orients [file].
     *
     * @return [Result.success] with a valid [Bitmap], or [Result.failure] carrying
     *   a [ProcessorException] when the file cannot be read or decoded.
     */
    fun process(file: File): Result<Bitmap> {
        return runCatching {
            if (!file.exists() || !file.canRead()) {
                throw ProcessorException("Image file is missing or unreadable: ${file.absolutePath}")
            }

            val dimensions = decodeDimensions(file)
            if (dimensions == null) {
                Log.e(TAG, "decodeDimensions returned null for ${file.absolutePath}")
                throw ProcessorException("Could not read image dimensions: ${file.name}")
            }

            val sampleSize = calculateSampleSize(dimensions.first, dimensions.second)

            val decoded = decodeBitmap(file, sampleSize)
            if (decoded == null) {
                Log.e(TAG, "BitmapFactory.decodeFile returned null for ${file.absolutePath}")
                throw ProcessorException("Could not decode image data: ${file.name}")
            }

            applyOrientation(file, decoded)
        }.recoverCatching { cause ->
            // Wrap anything unexpected (e.g. OutOfMemoryError) into our typed
            // failure, so callers never see a raw VM error.
            if (cause is ProcessorException) throw cause
            else {
                Log.e(TAG, "Unexpected failure while processing ${file.absolutePath}", cause)
                throw ProcessorException("Image processing failed: ${file.name}", cause)
            }
        }
    }

    /**
     * First decode pass: bounds only, no pixel allocation.
     */
    private fun decodeDimensions(file: File): Pair<Int, Int>? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            // When the decoder cannot parse the file it leaves -1 in the out* fields.
            if (options.outWidth < 1 || options.outHeight < 1) {
                Log.e(TAG, "Image bounds invalid: outWidth=${options.outWidth}, outHeight=${options.outHeight}")
                return null
            }
            options.outWidth to options.outHeight
        }.getOrElse {
            Log.e(TAG, "Failed to decode image bounds from ${file.absolutePath}", it)
            null
        }
    }

    /**
     * Largest power-of-two subsample that keeps the longest edge at or below
     * [TARGET_MAX_DIMENSION]. The decoder only honors powers of two, so the
     * result may be somewhat smaller than the target — that is intentional and
     * is what keeps peak memory bounded.
     */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val longest = maxOf(width, height)

        while (longest / sampleSize > TARGET_MAX_DIMENSION) {
            sampleSize *= 2
        }

        return sampleSize.also {
            Log.d(TAG, "Image ${width}x$height -> sampleSize=$it (target $TARGET_MAX_DIMENSION px)")
        }
    }

    /**
     * Second decode pass: the real pixels, in a config that preserves every
     * grey level the OCR engine needs.
     */
    private fun decodeBitmap(file: File, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // The scaled bitmap stays mutable-free; the caller only reads it.
            inMutable = false
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /**
     * Rotates/flips [decoded] according to the EXIF orientation tag and returns
     * the upright result. The original is recycled as soon as the transform
     * produced a distinct bitmap.
     */
    private fun applyOrientation(file: File, decoded: Bitmap): Bitmap {
        val orientation = readOrientation(file)

        // NORMAL (and UNDEFINED) need no transform, and `createBitmap` would
        // hand back the same instance anyway — skip the allocation entirely.
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return decoded
        }

        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> preRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    preRotate(90f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> preRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    preRotate(270f)
                    preScale(-1f, 1f)
                }
                else -> {
                    // Any unrecognized value is treated as identity.
                    return decoded
                }
            }
        }

        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrElse { cause ->
            Log.e(TAG, "Orientation transform failed for ${file.absolutePath}, using unrotated bitmap", cause)
            decoded
        }.also { rotated ->
            // Never recycle the bitmap we are handing back to the caller.
            if (rotated !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }

    /**
     * Reads the orientation tag defensively: a corrupt or absent EXIF block must
     * not abort the whole pipeline.
     */
    private fun readOrientation(file: File): Int {
        return runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        }.getOrElse { cause ->
            Log.w(TAG, "Could not read EXIF from ${file.absolutePath}, assuming no rotation", cause)
            ExifInterface.ORIENTATION_UNDEFINED
        }
    }

    private companion object {
        private const val TAG = "ImagePreprocessor"

        /**
         * Longest edge the OCR pipeline is willing to accept. Above this the
         * text is already legible and the memory cost is not justified.
         */
        private const val TARGET_MAX_DIMENSION = 2048
    }
}
