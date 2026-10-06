package com.invoiceextract.desktop.data.document

import com.invoiceextract.desktop.data.ocr.DesktopImageOcrEngine
import com.invoiceextract.desktop.data.ocr.isTesseractBackendAvailable
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Hermetic JVM tests for [DesktopDocumentProcessor].
 *
 * Digital PDFs are built with PDFBox rather than mocked, because the failure modes that
 * matter — a blank text layer, a corrupt header — are properties of the document
 * container, not of any code path a mock could reproduce. Scanned content is built by
 * rendering real Persian text into rasters (embedded into PDFs or written as PNG/JPG),
 * so a green run proves the render-plus-OCR fallback reads actual pages.
 *
 * Persian script cannot be embedded in the *digital* PDFs here: the standard-14 fonts
 * carry no Persian glyphs, so embedding it would need a bundled TTF asset. The Persian
 * repair of text-layer output is covered exhaustively by
 * [DesktopPersianNormalizerTest]; Persian *recognition* is covered below through
 * rendered rasters, which is exactly what the OCR path consumes. Recognition cases
 * gate on the Tess4J backend: without the native bridge they skip instead of failing
 * over an optional runtime component.
 *
 * The shared OCR engine boots Tesseract once per test class: recognition tests run
 * sequentially under JUnit 4, so one warm instance serves them all.
 */
class DesktopDocumentProcessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val processor = DesktopDocumentProcessor(
        normalizer = DesktopPersianNormalizer(),
        imageOcrEngine = SHARED_ENGINE,
    )

    @Test
    fun `extracts text from a digital PDF`() = runBlocking {
        val pdf = writePdf(
            "Invoice Number: 10234567890",
            "Total: 4800",
        )

        val result = processor.extractText(pdf)

        assertTrue("a digital PDF must extract successfully", result.isSuccess)
        val text = result.getOrThrow()
        assertTrue(text.contains("10234567890"))
        assertTrue(text.contains("4800"))
        // Line structure is what separates invoice rows, so it must survive the stage.
        assertTrue("newlines must be preserved", text.lines().size >= 2)
    }

    @Test
    fun `extracted text is already normalized`() = runBlocking {
        val pdf = writePdf("Seller   Name", "Value") // double space between words

        val text = processor.extractText(pdf).getOrThrow()

        // Whitespace collapsing happens inside the processor, before the model sees it.
        assertTrue(text.contains("Seller Name"))
        assertFalse("raw double spaces must not reach the model", text.contains("  "))
    }

    @Test
    fun `image-only or blank PDF reports the typed scan failure`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; the blank verdict needs a recognizer",
            isTesseractBackendAvailable(),
        )
        val pdf = writePdf() // a page with no content stream at all: nothing to extract

        val result = processor.extractText(pdf)

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue("failure must be the typed exception", cause is DocumentProcessingException)
        // The exact user-facing message is part of the contract.
        assertEquals(
            "متنی در این سند یافت نشد یا فاکتور به صورت عکس اسکن شده است.",
            cause?.message,
        )
    }

    @Test
    fun `corrupt PDF fails gracefully with the typed exception`() = runBlocking {
        val pdf = tempFolder.newFile("corrupt.pdf")
        // A plausible header followed by garbage: the parser starts, then gives up.
        pdf.writeBytes("%PDF-1.4\nthis is not a real pdf body".toByteArray())

        val result = processor.extractText(pdf)

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(cause is DocumentProcessingException)
        // The original PDFBox failure stays attached for logging.
        assertTrue("root cause must be retained for diagnostics", cause?.cause != null)
    }

    @Test
    fun `missing file fails gracefully with the typed exception`() = runBlocking {
        val missing = File(tempFolder.root, "does-not-exist.pdf")

        val result = processor.extractText(missing)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DocumentProcessingException)
    }

    @Test
    fun `non-PDF file fails gracefully with the typed exception`() = runBlocking {
        val notPdf = tempFolder.newFile("invoice.txt")
        notPdf.writeBytes("Invoice 10234567890".toByteArray())

        val result = processor.extractText(notPdf)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DocumentProcessingException)
    }

    @Test
    fun `unsupported extension reports the typed format failure`() = runBlocking {
        val notSupported = tempFolder.newFile("invoice.txt")
        notSupported.writeBytes("Invoice 10234567890".toByteArray())

        val result = processor.extractText(notSupported)

        assertTrue(result.isFailure)
        // A renamed text file must say "unsupported format", never "unreadable PDF".
        assertEquals(
            "فرمت فایل پشتیبانی نمی‌شود؛ فقط PDF و تصویر (PNG یا JPG) پذیرفته می‌شود.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `text-bearing PNG is recognized end to end`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; nothing to recognize with",
            isTesseractBackendAvailable(),
        )
        val png = writeTextImage(format = "png", "فاکتور فروش")

        val result = processor.extractText(png)

        assertTrue("a photo of an invoice must extract successfully", result.isSuccess)
        assertTrue(
            "recognized text must carry the rendered word, was: ${result.getOrNull()}",
            result.getOrThrow().contains("فاکتور"),
        )
    }

    @Test
    fun `text-bearing JPG is recognized end to end`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; nothing to recognize with",
            isTesseractBackendAvailable(),
        )
        val jpg = writeTextImage(format = "jpg", "مبلغ کل")

        val result = processor.extractText(jpg)

        assertTrue("a photo of an invoice must extract successfully", result.isSuccess)
        assertTrue(
            "recognized text must carry the rendered word, was: ${result.getOrNull()}",
            result.getOrThrow().contains("مبلغ"),
        )
    }

    @Test
    fun `scanned PDF renders each page and delimits the text`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; the render fallback needs a recognizer",
            isTesseractBackendAvailable(),
        )
        val pdf = writeScannedPdf(
            renderPersianText("فاکتور فروش"),
            renderPersianText("مبلغ کل"),
        )

        val result = processor.extractText(pdf)

        assertTrue("a raster-only scan must extract successfully", result.isSuccess)
        val text = result.getOrThrow()
        assertTrue("first page word missing, was: $text", text.contains("فاکتور"))
        assertTrue("second page word missing, was: $text", text.contains("مبلغ"))
        // Physical page numbers delimit the accumulation, so multi-page output stays
        // auditable instead of arriving as one undifferentiated block.
        assertTrue(text.contains("--- صفحه 1 ---"))
        assertTrue(text.contains("--- صفحه 2 ---"))
    }

    @Test
    fun `garbage bytes with an image suffix fail as unreadable`() = runBlocking {
        val broken = tempFolder.newFile("broken.png")
        broken.writeBytes("this is not an image at all".toByteArray())

        val result = processor.extractText(broken)

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(cause is DocumentProcessingException)
        // A corrupt upload is "your file is broken" — the OCR engine owns this verdict.
        assertEquals(
            "فایل تصویر قابل خواندن نیست.",
            cause?.message,
        )
    }

    @Test
    fun `truncated image fails as unreadable`() = runBlocking {
        val full = writeImage(width = 64, height = 48, format = "png")
        val bytes = full.readBytes()
        val truncated = tempFolder.newFile("truncated.png")
        // A valid header with the body cut in half: decoders start, then give up.
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))

        val result = processor.extractText(truncated)

        assertTrue(result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(cause is DocumentProcessingException)
        assertEquals(
            "فایل تصویر قابل خواندن نیست.",
            cause?.message,
        )
    }

    /**
     * Writes a minimal one-page PDF whose content stream is [lines], top to bottom.
     * Helvetica only carries Latin glyphs, so callers must pass Latin text.
     */
    private fun writePdf(vararg lines: String): File {
        val pdf = tempFolder.newFile("document-${System.nanoTime()}.pdf")

        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)

            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)

            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, FONT_SIZE)
                content.setLeading(LEADING)
                content.newLineAtOffset(MARGIN_X, MARGIN_Y)
                lines.forEach { line ->
                    content.showText(line)
                    content.newLine()
                }
                content.endText()
            }

            document.save(pdf)
        }

        return pdf
    }

    /**
     * Renders a real [width]×[height] raster in [format] with a diagonal gradient, so the
     * bytes are a genuinely decodable image rather than a blank canvas some readers
     * could shortcut.
     */
    private fun writeImage(width: Int, height: Int, format: String): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            for (y in 0 until height) {
                val shade = (255 * y / height).coerceIn(0, 255)
                graphics.color = Color(shade, shade, shade)
                graphics.drawLine(0, y, width, y)
            }
        } finally {
            graphics.dispose()
        }

        val file = tempFolder.newFile("invoice-${System.nanoTime()}.$format")
        ImageIO.write(image, format, file)
        return file
    }

    /**
     * Renders [lines] as large black-on-white Persian text with antialiasing, the way a
     * clean scan looks after preprocessing. Tahoma ships with Windows and covers the
     * Arabic block; AWT shapes the script correctly offscreen.
     */
    private fun renderPersianText(vararg lines: String): BufferedImage {
        val font = Font(TEXT_FONT_NAME, Font.BOLD, TEXT_FONT_SIZE)
        val measurer = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val metrics = measurer.createGraphics().let { graphics ->
            try {
                graphics.font = font
                graphics.fontMetrics
            } finally {
                graphics.dispose()
            }
        }

        val width = lines.maxOf { metrics.stringWidth(it) } + 2 * TEXT_PADDING
        val height = lines.size * metrics.height + 2 * TEXT_PADDING

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
                graphics.drawString(line, TEXT_PADDING, TEXT_PADDING + metrics.ascent + index * metrics.height)
            }
        } finally {
            graphics.dispose()
        }
        return image
    }

    /** Writes a [BufferedImage] of rendered Persian text out as a photo file. */
    private fun writeTextImage(format: String, vararg lines: String): File {
        val file = tempFolder.newFile("photo-${System.nanoTime()}.$format")
        ImageIO.write(renderPersianText(*lines), format, file)
        return file
    }

    /**
     * Builds a raster-only PDF: each [pages] bitmap embedded losslessly as the page's
     * sole content, so the text layer is empty and the render fallback must fire.
     */
    private fun writeScannedPdf(vararg pages: BufferedImage): File {
        val pdf = tempFolder.newFile("scan-${System.nanoTime()}.pdf")

        PDDocument().use { document ->
            pages.forEach { bitmap ->
                val page = PDPage()
                document.addPage(page)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page).use { content ->
                    content.drawImage(image, 0f, 0f)
                }
            }
            document.save(pdf)
        }

        return pdf
    }

    private companion object {
        val SHARED_ENGINE = DesktopImageOcrEngine(DesktopPersianNormalizer())

        const val FONT_SIZE = 12f
        const val LEADING = 16f
        const val MARGIN_X = 50f
        const val MARGIN_Y = 700f

        const val TEXT_FONT_NAME = "Tahoma"
        const val TEXT_FONT_SIZE = 72
        const val TEXT_PADDING = 24
    }
}
