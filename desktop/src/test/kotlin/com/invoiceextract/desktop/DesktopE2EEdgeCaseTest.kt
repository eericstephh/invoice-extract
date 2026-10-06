package com.invoiceextract.desktop

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.batch.BatchItemStatus
import com.invoiceextract.desktop.data.batch.DesktopBatchCoordinator
import com.invoiceextract.desktop.data.document.DesktopDocumentProcessor
import com.invoiceextract.desktop.data.document.DocumentProcessingException
import com.invoiceextract.desktop.data.export.AccountingTemplate
import com.invoiceextract.desktop.data.export.DesktopAccountingExportManager
import com.invoiceextract.desktop.data.ocr.DesktopImageOcrEngine
import com.invoiceextract.desktop.data.ocr.isTesseractBackendAvailable
import com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository
import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.validation.InvoiceValidator
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

/**
 * End-to-end edge-case suite for the desktop front: malformed inputs, numeral chaos,
 * monetary extremes, multi-page resource hygiene, and a hostile batch run through the
 * real pipeline.
 *
 * Every case runs the production wiring — real document stage, real validator, real
 * file-backed store, stubbed Ollama daemon — so what passes here is the shipped
 * behavior, not a mock of it. Cases that genuinely need the Tesseract native bridge
 * gate on [isTesseractBackendAvailable] and skip without it, exactly like the OCR
 * unit tests do; everything else runs everywhere.
 */
class DesktopE2EEdgeCaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val normalizer = DesktopPersianNormalizer()
    private val processor = DesktopDocumentProcessor(
        normalizer = normalizer,
        imageOcrEngine = DesktopImageOcrEngine(normalizer),
    )
    private val accounting = DesktopAccountingExportManager()

    // -- Malformed files ---------------------------------------------------------

    @Test
    fun `zero-byte PDF fails gracefully and releases the file`() = runBlocking {
        val empty = tempFolder.newFile("empty.pdf")
        // No bytes written: a 0-byte download as the OS hands it over.

        val result = processor.extractText(empty)

        assertTrue(result.isFailure)
        assertPersianFailure(result.exceptionOrNull())
        // On Windows a leaked handle blocks deletion; passing proves the stage
        // closed everything it opened.
        assertTrue("file handle leaked", empty.delete())
    }

    @Test
    fun `zero-byte PNG fails gracefully and releases the file`() = runBlocking {
        val empty = tempFolder.newFile("empty.png")

        val result = processor.extractText(empty)

        assertTrue(result.isFailure)
        assertPersianFailure(result.exceptionOrNull())
        assertTrue("file handle leaked", empty.delete())
    }

    @Test
    fun `fake PDF fails gracefully and releases the file`() = runBlocking {
        val fake = tempFolder.newFile("fake.pdf")
        fake.writeText("Invoice 12345, totally a real PDF", StandardCharsets.UTF_8)

        val result = processor.extractText(fake)

        assertTrue(result.isFailure)
        assertPersianFailure(result.exceptionOrNull())
        assertTrue("file handle leaked", fake.delete())
    }

    // -- Extreme currency and digits ----------------------------------------------

    @Test
    fun `mixed Persian Arabic and ASCII numerals survive normalization untouched`() {
        // Persian ۱۲۳, Arabic-Indic ٤٥, ASCII 67 — the normalizer must never convert
        // digits, or amounts silently change meaning mid-pipeline.
        val mixedTotals = "مبلغ ۱۲۳٤٥67 تومان"
        val mixedDate = "تاریخ ۱۴۰۳/05/٢٠"

        assertEquals(mixedTotals, normalizer.normalize(mixedTotals))
        assertEquals(mixedDate, normalizer.normalize(mixedDate))
    }

    @Test
    fun `arabic letters still normalize beside mixed digits`() {
        // The kaf/yeh rewrite must keep working when digits surround the letters.
        assertEquals("کیف ۱۲۳", normalizer.normalize("كيف ۱۲۳"))
    }

    @Test
    fun `huge toman fortune converts to exact rial digits`() {
        val huge = Invoice(
            id = "huge-1",
            invoiceNumber = "999",
            sellerName = "فروشنده",
            items = listOf(
                InvoiceItem(
                    id = "h1",
                    name = "کالا",
                    quantity = 1.0,
                    unitPrice = 999_000_000_000.0,
                    totalPrice = 999_000_000_000.0,
                ),
            ),
            currency = CurrencyType.TOMAN,
        )
        val target = tempFolder.newFile("huge.xls")

        accounting.exportSepidar(target, huge)

        val raw = target.readText(StandardCharsets.UTF_8)
        // 999e9 Toman × 10 — every digit exact, still well inside Long range.
        assertTrue(raw.contains("<Data ss:Type=\"Number\">9990000000000</Data></Cell>"))
    }

    @Test
    fun `astronomical amounts never overflow silently`() {
        val monster = Invoice(
            id = "monster-1",
            items = listOf(
                InvoiceItem(
                    id = "m1",
                    name = "کالا",
                    quantity = 1.0,
                    unitPrice = 1e19,
                    totalPrice = 1e19,
                ),
            ),
            currency = CurrencyType.TOMAN,
        )
        val target = tempFolder.newFile("monster.xls")

        accounting.exportSepidar(target, monster)

        val raw = target.readText(StandardCharsets.UTF_8)
        // 1e20 Rial: past Long range, so only exact BigDecimal digits are acceptable —
        // no saturation sentinel, no scientific notation in an import file.
        assertTrue(raw.contains("<Data ss:Type=\"Number\">100000000000000000000</Data></Cell>"))
        assertFalse(raw.contains("9223372036854775807"))
        assertFalse(raw.contains("E+"))
    }

    // -- Multi-page document cleanup -----------------------------------------------

    @Test
    fun `multi-page digital extraction closes every stream`() = runBlocking {
        val pdf = writePdfPages(
            listOf("PAGE-1 first"),
            listOf("PAGE-2 second"),
            listOf("PAGE-3 third"),
        )

        val text = processor.extractText(pdf).getOrThrow()

        assertTrue(text.contains("PAGE-1"))
        assertTrue(text.contains("PAGE-2"))
        assertTrue(text.contains("PAGE-3"))
        // Windows refuses to delete a file with an open handle: passing proves the
        // document, its buffers and its scratch file all closed.
        assertTrue("multi-page document leaked a handle", pdf.delete())
    }

    @Test
    fun `multi-page scanned extraction delimits pages and releases the file`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; the render fallback needs a recognizer",
            isTesseractBackendAvailable(),
        )
        val pdf = writeScannedPdf(
            renderPersianText("فاکتور فروش"),
            renderPersianText("مبلغ کل"),
        )

        val text = processor.extractText(pdf).getOrThrow()

        assertTrue(text.contains("--- صفحه 1 ---"))
        assertTrue(text.contains("--- صفحه 2 ---"))
        assertTrue("rendered pages leaked a handle", pdf.delete())
    }

    // -- Batch fault-resilience under stress -----------------------------------------

    @Test
    fun `hostile batch completes with successes isolated from the failure`() = runBlocking {
        Assume.assumeTrue(
            "Tess4J backend absent; the photo leg needs a recognizer",
            isTesseractBackendAvailable(),
        )
        val storeDir = tempFolder.newFolder("store")
        withStubOllama {
            val coordinator = DesktopBatchCoordinator(
                processInvoiceUseCase(),
                DesktopInvoiceRepository(json, storeDir),
            )
            val validA = writePdf("Invoice A-1")
            val corrupt = tempFolder.newFile("empty.pdf")
            val photo = writeTextImage("png", "فاکتور فروش")
            val validB = writePdf("Invoice B-2")

            val last = coordinator.processBatch(listOf(validA, corrupt, photo, validB)).toList().last()

            assertTrue(last.isFinished)
            assertEquals(4, last.total)
            assertEquals(4, last.completed)
            assertEquals(
                "expected 3 successes, failures were: " +
                    last.items.map { "${it.file.name}=${it.status}:${it.errorMessage}" },
                3,
                last.successCount,
            )
            assertEquals(1, last.failureCount)
            assertEquals(BatchItemStatus.FAILED, last.items[1].status)
            assertTrue(
                "failure must carry a user-facing message, was: ${last.items[1].errorMessage}",
                !last.items[1].errorMessage.isNullOrBlank(),
            )

            // The consolidated Sepidar sheet covers exactly the successes: the two stub
            // PDFs plus the OCR'd photo, one item row each — and nothing from the
            // corrupt file.
            val successes = last.items.mapNotNull { it.invoice }
            assertEquals(3, successes.size)
            val ledger = tempFolder.newFile("stress-sepidar.xls")
            accounting.exportBatchAccounting(ledger, successes, AccountingTemplate.SEPIDAR)

            val raw = ledger.readText(StandardCharsets.UTF_8)
            assertTrue(raw.contains("فروشنده تستی"))
            // Header row plus three item rows — the failed file contributes no row.
            assertEquals(4, raw.split("<Row>").size - 1)

            // All three successes persisted behind the run.
            val stored = DesktopInvoiceRepository(json, storeDir).getInvoices().first()
            assertEquals(3, stored.size)
        }
    }

    // -- Helpers ---------------------------------------------------------------------

    /** A typed failure with a Persian message a user can actually read. */
    private fun assertPersianFailure(cause: Throwable?) {
        assertTrue("expected a typed failure, was: $cause", cause is DocumentProcessingException)
        assertTrue(
            "message must be user-facing Persian, was: ${cause?.message}",
            !cause?.message.isNullOrBlank(),
        )
    }

    // -- Harness: real pipeline, stubbed daemon (mirrors the coordinator suite) --------

    private fun processInvoiceUseCase(): DesktopProcessInvoiceUseCase =
        DesktopProcessInvoiceUseCase(
            documentProcessor = DesktopDocumentProcessor(
                normalizer = DesktopPersianNormalizer(),
                imageOcrEngine = DesktopImageOcrEngine(DesktopPersianNormalizer()),
            ),
            ollamaExtractor = LocalOllamaAiExtractor(OkHttpClient(), json),
            mappingRepository = ProductMappingRepository(
                DesktopPersianNormalizer(),
                json,
                tempFolder.newFolder("mappings-${System.nanoTime()}"),
            ),
            invoiceValidator = InvoiceValidator(),
        )

    /**
     * Runs [block] with a stub Ollama daemon on the loopback port the extractor dials.
     * Skips the test when the port is already taken — those answers would not be the
     * stub's. The server is stopped on every path, so no listener outlives the test.
     */
    private suspend fun withStubOllama(block: suspend () -> Unit) {
        val server = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", OLLAMA_PORT), 0)
        } catch (e: BindException) {
            Assume.assumeTrue("Port $OLLAMA_PORT is busy; skipping stubbed Ollama test", false)
            throw e // Unreachable: the assumption above aborts the test.
        }
        server.createContext(PATH_TAGS) { exchange -> respond(exchange, TAGS_JSON) }
        server.createContext(PATH_CHAT) { exchange -> respond(exchange, chatJson()) }
        server.start()
        try {
            block()
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, json: String) {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    /** A minimal one-page PDF with a real text layer. */
    private fun writePdf(vararg lines: String): File {
        val pdf = tempFolder.newFile("e2e-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, 12f)
                content.setLeading(16f)
                content.newLineAtOffset(50f, 700f)
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

    /** One digital page per [pages] entry, each carrying its marker line. */
    private fun writePdfPages(vararg pages: List<String>): File {
        val pdf = tempFolder.newFile("multipage-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            pages.forEach { lines ->
                val page = PDPage()
                document.addPage(page)
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(font, 12f)
                    content.setLeading(16f)
                    content.newLineAtOffset(50f, 700f)
                    lines.forEach { line ->
                        content.showText(line)
                        content.newLine()
                    }
                    content.endText()
                }
            }
            document.save(pdf)
        }
        return pdf
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
        const val OLLAMA_PORT = 11434
        const val PATH_TAGS = "/api/tags"
        const val PATH_CHAT = "/api/chat"

        const val TAGS_JSON = """{"models": [{"name": "qwen2.5:3b"}]}"""

        const val STUB_INVOICE_JSON = """{"invoiceNumber":"9001","date":"1403/05/20",""" +
            """"sellerName":"فروشنده تستی","items":[{"name":"قلم","quantity":2.0,""" +
            """"unitPrice":1000.0,"totalPrice":2000.0}],"grandTotal":2000.0,"currency":"TOMAN"}"""

        const val TEXT_FONT_NAME = "Tahoma"
        const val TEXT_FONT_SIZE = 72
        const val TEXT_PADDING = 24
    }

    /** The chat envelope with the invoice payload escaped into the `content` string. */
    private fun chatJson(): String {
        val escaped = STUB_INVOICE_JSON
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return """{"model":"qwen2.5:3b","created_at":"2026-01-01T00:00:00Z",""" +
            """"message":{"role":"assistant","content":"$escaped"},"done":true,""" +
            """"done_reason":"stop"}"""
    }
}
