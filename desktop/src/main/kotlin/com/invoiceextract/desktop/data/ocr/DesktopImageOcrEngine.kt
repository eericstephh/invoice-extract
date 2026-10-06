package com.invoiceextract.desktop.data.ocr

import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

/**
 * Offline OCR for invoice photos and raster-only scans, 100% on-device.
 *
 * Two halves with different failure profiles:
 *
 * - **Preprocessing ([preprocess])** is pure JVM with no native code: grayscale
 *   conversion, full-range contrast stretching, and Otsu-threshold binarization. It
 *   turns phone photos of invoices — grey paper, soft shadows, weak ink — into the
 *   crisp black-on-white bitmaps a recognizer wants, and it is deterministic enough
 *   to unit-test pixel by pixel.
 * - **Recognition** runs behind an [OcrRecognizer] backend resolved once, on first
 *   use. The production backend is Tesseract (Persian pack vendored into this
 *   module's resources), loaded reflectively so this module compiles and ships
 *   without the native bridge on its compile classpath; where the backend is absent
 *   the engine fails loudly in Persian instead of pretending to read. Recognizers
 *   are not thread-safe by contract, so every call serializes on [ocrLock].
 *
 * **Threading.** Everything blocking — file decode, preprocessing, the native
 * `doOCR` call — runs on [Dispatchers.IO]. Cancellation unwinds between calls, but a
 * recognition already inside native code runs to completion first: JNI cannot be
 * interrupted, so closing the window mid-recognition waits out that one page rather
 * than leaking its thread.
 *
 * **Errors.** Every failure is folded into one [ImageOcrException] inside
 * [Result.failure], with a Persian message for the window and the root cause kept for
 * logging — the same contract as the document stage that drives this engine.
 *
 * @param normalizer Repairs the Arabic-script artifacts of the recognized text, so the
 *   model receives clean text and no invisible noise — the same pass digital-PDF text
 *   goes through, which keeps both input paths indistinguishable downstream.
 */
class DesktopImageOcrEngine(
    private val normalizer: DesktopPersianNormalizer,
) {

    /**
     * Reads [imageFile] and recognizes its text.
     *
     * An undecodable file (wrong suffix, truncated download, non-image bytes) fails
     * here with the unreadable-image message instead of reaching the recognizer.
     */
    suspend fun extractFromImage(imageFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            val image = try {
                ImageIO.read(imageFile)
            } catch (cause: Exception) {
                if (cause is CancellationException) throw cause
                return@withContext Result.failure(ImageOcrException(IMAGE_UNREADABLE_MESSAGE, cause))
            } ?: return@withContext Result.failure(ImageOcrException(IMAGE_UNREADABLE_MESSAGE))

            extractFromBufferedImage(image)
        }

    /**
     * Recognizes the text of an already-decoded [image] — a photo off disk or a page
     * the PDF renderer produced.
     *
     * @return [Result.success] with non-blank normalized text, or [Result.failure]
     *   carrying an [ImageOcrException].
     */
    suspend fun extractFromBufferedImage(image: BufferedImage): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prepared = preprocess(image)
                val raw = ocrLock.withLock { recognizer.recognize(prepared) }
                val cleaned = normalizer.normalize(raw)

                if (cleaned.isBlank()) {
                    throw BlankImageException()
                }

                cleaned
            }.recoverCatching { cause ->
                if (cause is CancellationException) throw cause

                throw when (cause) {
                    is ImageOcrException -> cause
                    // Tesseract surfaces its own typed failure; anything else the
                    // recognizer throws is an engine bug, reported as such.
                    else ->
                        if (cause.javaClass.name == TESSERACT_EXCEPTION_CLASS) {
                            ImageOcrException(OCR_FAILED_MESSAGE, cause)
                        } else {
                            ImageOcrException(UNEXPECTED_MESSAGE, cause)
                        }
                }
            }
        }

    /**
     * Prepares a raster for recognition: grayscale, contrast stretch, binarize.
     *
     * Each step is total and side-effect-free — the input image is never mutated — so
     * this doubles as the unit-testable core of the engine: synthetic rasters assert
     * exact pixel outcomes without paying for a Tesseract boot.
     */
    internal fun preprocess(image: BufferedImage): BufferedImage {
        val gray = toGrayscale(image)
        val stretched = stretchContrast(gray)
        return binarizeOtsu(stretched)
    }

    /** Draws [image] into a single-band gray raster; already-gray images pass through. */
    private fun toGrayscale(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_BYTE_GRAY) return image

        val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = gray.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return gray
    }

    /**
     * Linearly maps the observed luminance range onto the full 0..255 span.
     *
     * A flat image (one shade everywhere) has no range to stretch; dividing by zero
     * would only manufacture NaNs, so it passes through untouched.
     */
    private fun stretchContrast(gray: BufferedImage): BufferedImage {
        val width = gray.width
        val height = gray.height
        val pixels = gray.raster.getPixels(0, 0, width, height, null as IntArray?)

        var min = MAX_LUMINANCE
        var max = MIN_LUMINANCE
        for (value in pixels) {
            if (value < min) min = value
            if (value > max) max = value
        }
        if (max <= min) return gray

        val scale = MAX_LUMINANCE.toDouble() / (max - min)
        for (i in pixels.indices) {
            pixels[i] = ((pixels[i] - min) * scale + HALF_UP).toInt().coerceIn(MIN_LUMINANCE, MAX_LUMINANCE)
        }

        val stretched = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        stretched.raster.setPixels(0, 0, width, height, pixels)
        return stretched
    }

    /**
     * Otsu binarization: the threshold that best separates the histogram into two
     * classes becomes the ink/paper boundary, and every pixel snaps to 0 or 255.
     *
     * Kept as gray-with-two-values rather than a binary raster: Tesseract consumes
     * both, and the gray form keeps the pixel pipeline uniform for tests. All
     * arithmetic runs in [Double] — a 200-DPI A4 page holds ~4M pixels, whose class
     * weights overflow [Int] when multiplied.
     */
    private fun binarizeOtsu(gray: BufferedImage): BufferedImage {
        val width = gray.width
        val height = gray.height
        val pixels = gray.raster.getPixels(0, 0, width, height, null as IntArray?)

        val histogram = IntArray(LUMINANCE_LEVELS)
        for (value in pixels) histogram[value]++

        var sumAll = 0.0
        for (level in 0 until LUMINANCE_LEVELS) sumAll += level * histogram[level]

        var sumBackground = 0.0
        var weightBackground = 0.0
        var bestVariance = -1.0
        var threshold = MID_LUMINANCE
        val total = pixels.size.toDouble()
        for (level in 0 until LUMINANCE_LEVELS) {
            weightBackground += histogram[level]
            if (weightBackground == 0.0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0.0) break
            sumBackground += level * histogram[level]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val between = weightBackground * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > bestVariance) {
                bestVariance = between
                threshold = level
            }
        }

        for (i in pixels.indices) {
            pixels[i] = if (pixels[i] > threshold) MAX_LUMINANCE else MIN_LUMINANCE
        }

        val binary = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        binary.raster.setPixels(0, 0, width, height, pixels)
        return binary
    }

    /**
     * The recognizer, resolved once on first use.
     *
     * `by lazy` is synchronization-safe by default, and a failed build retries on the
     * next call instead of poisoning the engine — so a transient temp-dir failure does
     * not permanently disable OCR for the session.
     */
    private val recognizer: OcrRecognizer by lazy { resolveRecognizer() }

    /** Serializes recognition: recognizer instances must never be entered concurrently. */
    private val ocrLock = Mutex()

    /**
     * Picks the recognition backend.
     *
     * Tess4J present → the reflective bridge below. Absent → a backend that fails every
     * call with the packaging verdict, so a build without the native bridge still
     * compiles, installs and runs — it simply reports OCR as unavailable instead of
     * crashing. A present-but-broken bridge (version skew, unloadable natives) is a
     * packaging bug and throws loudly rather than masquerading as unavailable.
     */
    private fun resolveRecognizer(): OcrRecognizer {
        try {
            Class.forName(TESSERACT_CLASS)
        } catch (cause: ClassNotFoundException) {
            return UnavailableRecognizer
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            throw ImageOcrException(BACKEND_BROKEN_MESSAGE, cause)
        }

        return try {
            ReflectiveTesseractRecognizer(extractTrainedData())
        } catch (cause: ImageOcrException) {
            throw cause
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            throw ImageOcrException(BACKEND_BROKEN_MESSAGE, cause)
        }
    }

    /**
     * Materializes the bundled `fas.traineddata` as real files.
     *
     * Tesseract only reads its language pack off the filesystem, never from inside a
     * jar — so the resource the build vendors is copied once per engine into a temp
     * directory (best-effort removed on JVM exit). A missing resource means a broken
     * package, reported as such instead of a cryptic native error.
     */
    private fun extractTrainedData(): File {
        val resource = javaClass.getResourceAsStream("$TESSDATA_RESOURCE_DIR/$TRAINEDDATA_FILE")
            ?: throw ImageOcrException(TESSDATA_MISSING_MESSAGE)

        val dir = Files.createTempDirectory(TESSDATA_TEMP_PREFIX).toFile()
        dir.deleteOnExit()
        resource.use { input ->
            File(dir, TRAINEDDATA_FILE).outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return dir
    }

    private companion object {
        const val TESS_LANGUAGE = "fas"
        const val TESSDATA_RESOURCE_DIR = "/tessdata"
        const val TRAINEDDATA_FILE = "fas.traineddata"
        const val TESSDATA_TEMP_PREFIX = "invoiceextract-tessdata"

        // Reflective Tess4J surface — the only coupling to the native bridge, kept as
        // strings so this module compiles with or without the artifact on its classpath.
        const val TESSERACT_CLASS = "net.sourceforge.tess4j.Tesseract"
        const val TESSERACT_EXCEPTION_CLASS = "net.sourceforge.tess4j.TesseractException"

        const val MIN_LUMINANCE = 0
        const val MAX_LUMINANCE = 255
        const val MID_LUMINANCE = 128
        const val LUMINANCE_LEVELS = 256
        const val HALF_UP = 0.5
    }
}

/**
 * One recognition call. Implementations are not required to be thread-safe — the engine
 * serializes entry — but they must throw (never return null or blank for "no backend").
 */
internal fun interface OcrRecognizer {

    /** Recognizes [image] (already preprocessed) or throws the reason it cannot. */
    fun recognize(image: BufferedImage): String
}

/**
 * The production backend: Tess4J's `Tesseract`, driven reflectively.
 *
 * Reflection is the whole point — it keeps this module compiling on machines that
 * never see the native bridge, while calling the exact same four methods a direct
 * dependency would. [InvocationTargetException] is unwrapped so the engine maps the
 * real failure (e.g. `TesseractException`) instead of the reflective wrapper.
 *
 * @param trainedDataDir Filesystem directory holding `fas.traineddata`; Tesseract
 *   cannot read its language pack from inside a jar.
 */
private class ReflectiveTesseractRecognizer(trainedDataDir: File) : OcrRecognizer {

    private val instance: Any
    private val doOcr: Method

    init {
        val tessClass = Class.forName(TESSERACT_CLASS)
        instance = tessClass.getDeclaredConstructor().newInstance()
        tessClass.getMethod("setDatapath", String::class.java)
            .invoke(instance, trainedDataDir.absolutePath)
        tessClass.getMethod("setLanguage", String::class.java)
            .invoke(instance, TESS_LANGUAGE)
        doOcr = tessClass.getMethod("doOCR", BufferedImage::class.java)
    }

    override fun recognize(image: BufferedImage): String {
        try {
            return doOcr.invoke(instance, image) as String
        } catch (cause: InvocationTargetException) {
            throw cause.targetException
        }
    }

    private companion object {
        const val TESSERACT_CLASS = "net.sourceforge.tess4j.Tesseract"
        const val TESS_LANGUAGE = "fas"
    }
}

/** The no-backend verdict: every call fails with the packaging message. */
private object UnavailableRecognizer : OcrRecognizer {

    override fun recognize(image: BufferedImage): String =
        throw ImageOcrException(ENGINE_UNAVAILABLE_MESSAGE)
}

/**
 * True when the Tess4J backend is on the runtime classpath. Recognition tests gate on
 * this: without the native bridge there is nothing to recognize *with*, so they skip
 * instead of failing over a missing optional component.
 */
internal fun isTesseractBackendAvailable(): Boolean =
    runCatching { Class.forName("net.sourceforge.tess4j.Tesseract") }.isSuccess

/**
 * The single failure type of the offline OCR engine.
 *
 * Mirrors the document stage's `DocumentProcessingException`: the message is Persian
 * because the window may show it verbatim, while the technical cause rides along for
 * logging.
 */
open class ImageOcrException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The blank-page verdict: the recognizer ran fine but found no text to read.
 *
 * A subtype of [ImageOcrException] rather than a sibling, so every existing
 * `is ImageOcrException` check keeps matching. The multi-page document stage switches
 * on this type to skip an empty sheet while still failing the document on a genuine
 * recognizer error — the two must never share one code path, or a choked page would
 * silently vanish from the invoice.
 */
class BlankImageException : ImageOcrException(BLANK_IMAGE_MESSAGE)

private const val IMAGE_UNREADABLE_MESSAGE = "فایل تصویر قابل خواندن نیست."
private const val BLANK_IMAGE_MESSAGE = "متنی در تصویر یافت نشد."
private const val OCR_FAILED_MESSAGE = "خطا در خواندن متن تصویر."
private const val TESSDATA_MISSING_MESSAGE =
    "موتور OCR آماده نیست؛ فایل زبان فارسی یافت نشد."
private const val ENGINE_UNAVAILABLE_MESSAGE =
    "موتور OCR آماده نیست؛ کتابخانه Tess4J در دسترس نیست."
private const val BACKEND_BROKEN_MESSAGE =
    "موتور OCR به درستی بارگذاری نشد."
private const val UNEXPECTED_MESSAGE = "خطای ناشناخته هنگام خواندن تصویر."
