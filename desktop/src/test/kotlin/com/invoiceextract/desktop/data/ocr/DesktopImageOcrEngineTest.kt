package com.invoiceextract.desktop.data.ocr

import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Hermetic JVM tests for [DesktopImageOcrEngine], in two layers.
 *
 * The preprocessing core is pure pixel math, so it asserts exact outcomes on synthetic
 * rasters with no native code involved — fast, deterministic, and precise to the pixel.
 * Recognition boots the real Tesseract with the real Persian pack on text rendered by
 * AWT itself, so a green run proves the engine reads Persian off actual bitmaps rather
 * than merely not crashing on them. Those cases gate on [isTesseractBackendAvailable]:
 * the native bridge is an optional runtime component, so without it they skip instead
 * of failing over something the test never meant to exercise.
 *
 * The shared engine boots Tesseract once per test class: recognition tests run
 * sequentially under JUnit 4, so one warm instance serves them all.
 */
class DesktopImageOcrEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `preprocess converts color to grayscale`() {
        val color = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val graphics = color.createGraphics()
        try {
            graphics.color = Color(200, 30, 30)
            graphics.fillRect(0, 0, 4, 4)
        } finally {
            graphics.dispose()
        }

        val prepared = ENGINE.preprocess(color)

        assertEquals(BufferedImage.TYPE_BYTE_GRAY, prepared.type)
        assertEquals(4, prepared.width)
        assertEquals(4, prepared.height)
    }

    @Test
    fun `contrast stretching spans the full range`() {
        // A washed-out raster living entirely between 100 and 150.
        val gray = uniformGrayShades(intArrayOf(100, 120, 150, 130))

        val prepared = ENGINE.preprocess(gray)
        val pixels = prepared.raster.getPixels(0, 0, 4, 1, null as IntArray?)

        // Stretching maps observed min/max onto 0/255; binarization then snaps both
        // extremes into place, so the full span must be present.
        assertEquals(0, pixels.min())
        assertEquals(255, pixels.max())
    }

    @Test
    fun `binarization snaps every pixel to an extreme`() {
        val gradient = BufferedImage(16, 1, BufferedImage.TYPE_BYTE_GRAY)
        val raster = gradient.raster
        for (x in 0 until 16) {
            raster.setSample(x, 0, 0, x * 17 % 256)
        }

        val prepared = ENGINE.preprocess(gradient)
        val pixels = prepared.raster.getPixels(0, 0, 16, 1, null as IntArray?)

        assertTrue(
            "every pixel must be ink or paper, was: ${pixels.toList()}",
            pixels.all { it == 0 || it == 255 },
        )
    }

    @Test
    fun `flat image survives preprocessing without dividing by zero`() {
        val gray = BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = gray.createGraphics()
        try {
            graphics.color = Color(128, 128, 128)
            graphics.fillRect(0, 0, 4, 4)
        } finally {
            graphics.dispose()
        }

        val prepared = ENGINE.preprocess(gray)
        val pixels = prepared.raster.getPixels(0, 0, 4, 4, null as IntArray?)

        // No range to stretch and nothing to separate: uniform in, uniform out.
        assertEquals(1, pixels.toSet().size)
    }

    @Test
    fun `undecodable image file fails with the typed exception`() = runBlocking {
        val broken = tempFolder.newFile("broken.png")
        broken.writeBytes("this is not an image at all".toByteArray())

        val result = ENGINE.extractFromImage(broken)

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(cause is ImageOcrException)
        assertEquals("فایل تصویر قابل خواندن نیست.", cause?.message)
    }

    @Test
    fun `missing image file fails with the typed exception`() = runBlocking {
        val missing = File(tempFolder.root, "does-not-exist.png")

        val result = ENGINE.extractFromImage(missing)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ImageOcrException)
    }

    @Test
    fun `rendered Persian text is recognized`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; nothing to recognize with",
            isTesseractBackendAvailable(),
        )
        val image = renderPersianText("فاکتور فروش", "مبلغ کل")

        val result = ENGINE.extractFromBufferedImage(image)

        assertTrue("a clean rendering must recognize successfully", result.isSuccess)
        val text = result.getOrThrow()
        assertTrue(
            "recognized text must carry the rendered word, was: $text",
            text.contains("فاکتور"),
        )
    }

    @Test
    fun `blank raster reports no text found`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; the blank verdict needs a recognizer",
            isTesseractBackendAvailable(),
        )
        val blank = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        val graphics = blank.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, 200, 100)
        } finally {
            graphics.dispose()
        }

        val result = ENGINE.extractFromBufferedImage(blank)

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(cause is ImageOcrException)
        assertEquals("متنی در تصویر یافت نشد.", cause?.message)
    }

    /**
     * Renders [lines] as large black-on-white Persian text with antialiasing, the way a
     * clean scan looks after preprocessing. Tahoma ships with Windows and covers the
     * Arabic block; AWT shapes the script correctly offscreen.
     */
    private fun renderPersianText(vararg lines: String): BufferedImage {
        val font = Font(FONT_NAME, Font.BOLD, FONT_SIZE)
        val measurer = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val metrics = measurer.createGraphics().let { graphics ->
            try {
                graphics.font = font
                graphics.fontMetrics
            } finally {
                graphics.dispose()
            }
        }

        val width = lines.maxOf { metrics.stringWidth(it) } + 2 * PADDING
        val height = lines.size * metrics.height + 2 * PADDING

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLACK
            graphics.font = font
            lines.forEachIndexed { index, line ->
                graphics.drawString(line, PADDING, PADDING + metrics.ascent + index * metrics.height)
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    /**
     * A 4x1 gray strip cycling through [shades], for deterministic stretch assertions.
     */
    private fun uniformGrayShades(shades: IntArray): BufferedImage {
        val gray = BufferedImage(shades.size, 1, BufferedImage.TYPE_BYTE_GRAY)
        val raster = gray.raster
        shades.forEachIndexed { x, shade -> raster.setSample(x, 0, 0, shade) }
        return gray
    }

    private companion object {
        val ENGINE = DesktopImageOcrEngine(DesktopPersianNormalizer())

        const val FONT_NAME = "Tahoma"
        const val FONT_SIZE = 72
        const val PADDING = 24
    }
}
