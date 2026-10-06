package com.invoiceextract.desktop.data.batch

import com.invoiceextract.desktop.data.ai.LocalOllamaAiExtractor
import com.invoiceextract.desktop.data.document.DesktopDocumentProcessor
import com.invoiceextract.desktop.data.ocr.DesktopImageOcrEngine
import com.invoiceextract.desktop.data.storage.DesktopInvoiceRepository
import com.invoiceextract.desktop.data.mapping.ProductMappingRepository
import com.invoiceextract.desktop.data.text.DesktopPersianNormalizer
import com.invoiceextract.desktop.domain.DesktopProcessInvoiceUseCase
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.repository.InvoiceRepository
import com.invoiceextract.domain.validation.InvoiceValidator
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Hermetic JVM tests for [DesktopBatchCoordinator].
 *
 * The coordinator is exercised through the real single-file pipeline — real PDFBox
 * documents, the real validator, the real file-backed store — with the Ollama daemon
 * replaced by a stub served from the JDK's built-in HTTP server: `/api/tags` reports
 * the expected model, `/api/chat` answers a canned invoice. No daemon, no network
 * egress, no new test dependencies.
 *
 * If something already listens on the daemon port (a real `ollama serve`), the stub
 * cannot bind and the stubbed cases skip instead of fighting it: a busy port means the
 * answers would not be the stub's anyway.
 */
class DesktopBatchCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `mixed batch isolates failures and persists successes`() = runBlocking {
        val storeDir = tempFolder.newFolder("store")
        withStubOllama {
            val coordinator = coordinator(storeDir)
            val garbage = writeTextFile("note.txt", "not an invoice")
            val corrupt = writeBytesFile("corrupt.pdf", "%PDF-1.4\nthis is not a real pdf body")
            val valid = writePdf("Invoice 9001", "Total 2000")

            // The success comes last on purpose: it proves a failure never aborts the run.
            val emissions = coordinator.processBatch(listOf(garbage, corrupt, valid)).toList()

            // Shape of the run: accepted queue first, finished snapshot last.
            val first = emissions.first()
            assertEquals(3, first.total)
            assertEquals(0, first.completed)
            assertTrue(first.items.all { it.status == BatchItemStatus.PENDING })
            assertTrue(!first.isFinished)

            val last = emissions.last()
            assertTrue(last.isFinished)
            assertEquals(3, last.total)
            assertEquals(3, last.completed)
            assertEquals(
                "valid.pdf failed with: ${last.items[2].errorMessage}",
                1,
                last.successCount,
            )
            assertEquals(2, last.failureCount)

            // Per-file verdicts, in drop order, each keeping its own file.
            assertEquals(listOf(garbage, corrupt, valid), last.items.map { it.file })
            assertEquals(BatchItemStatus.FAILED, last.items[0].status)
            assertEquals(BatchItemStatus.FAILED, last.items[1].status)
            assertTrue(
                "expected SUCCESS but was ${last.items[2].status} error=${last.items[2].errorMessage}",
                last.items[2].status == BatchItemStatus.SUCCESS,
            )
            assertNotNull(last.items[2].invoice)
            assertTrue(!last.items[0].errorMessage.isNullOrBlank())
            assertTrue(!last.items[1].errorMessage.isNullOrBlank())
            assertNull(last.items[2].errorMessage)

            // Intermediate transitions were emitted, not just the endpoints.
            assertTrue(emissions.any { progress -> progress.items.any { it.status == BatchItemStatus.PROCESSING } })

            // The success landed in the real store behind the coordinator.
            val saved = last.items[2].invoice!!
            assertEquals(saved, DesktopInvoiceRepository(json, storeDir).getInvoiceById(saved.id))
        }
    }

    @Test
    fun `empty file list finishes immediately`() = runBlocking {
        val coordinator = coordinator(tempFolder.newFolder("store"))

        val emissions = coordinator.processBatch(emptyList()).toList()

        val last = emissions.last()
        assertTrue(last.isFinished)
        assertEquals(0, last.total)
        assertEquals(0, last.completed)
    }

    @Test
    fun `failed store write demotes the item to failed`() = runBlocking {
        withStubOllama {
            val useCase = processInvoiceUseCase()
            val failingStore = FakeInvoiceRepository(saveResult = Result.failure(IOException("disk full")))
            val coordinator = DesktopBatchCoordinator(useCase, failingStore)

            val last = coordinator.processBatch(listOf(writePdf("Invoice 9002"))).toList().last()

            assertTrue(last.isFinished)
            assertEquals(0, last.successCount)
            assertEquals(1, last.failureCount)
            assertEquals(BatchItemStatus.FAILED, last.items.single().status)
            // The store's own reason surfaces — a silent SUCCESS here would lie to the ledger.
            assertTrue(last.items.single().errorMessage?.contains("disk full") == true)
        }
    }

    @Test
    fun `flow is cancellable between emissions`() = runBlocking {
        val coordinator = coordinator(tempFolder.newFolder("store"))
        val files = listOf(
            writeTextFile("a.txt", "nope"),
            writeTextFile("b.txt", "still nope"),
        )

        // Taking one emission cancels the rest; a non-cancellable flow would hang here
        // past any reasonable timeout instead of returning.
        val taken = coordinator.processBatch(files).take(1).toList()

        assertEquals(1, taken.size)
        assertEquals(2, taken.single().total)
    }

    // -- Harness ---------------------------------------------------------------

    private fun coordinator(storeDir: File): DesktopBatchCoordinator =
        DesktopBatchCoordinator(processInvoiceUseCase(), DesktopInvoiceRepository(json, storeDir))

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
        // The invoice rides inside `content` as an escaped JSON string, exactly like a
        // real daemon answer — the extractor decodes the envelope first, then the payload.
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

    private fun writeTextFile(name: String, content: String): File =
        tempFolder.newFile(name).apply { writeText(content, StandardCharsets.UTF_8) }

    private fun writeBytesFile(name: String, content: String): File =
        tempFolder.newFile(name).apply { writeBytes(content.toByteArray(StandardCharsets.UTF_8)) }

    /** A minimal one-page PDF with a real text layer, like the document-stage tests use. */
    private fun writePdf(vararg lines: String): File {
        val pdf = tempFolder.newFile("batch-${System.nanoTime()}.pdf")
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

    /** An [InvoiceRepository] with a scripted save outcome and an in-memory list. */
    private class FakeInvoiceRepository(
        private val saveResult: Result<Unit>,
    ) : InvoiceRepository {
        private val saved = mutableListOf<Invoice>()
        private val flow = MutableStateFlow<List<Invoice>>(emptyList())

        override suspend fun saveInvoice(invoice: Invoice): Result<Unit> =
            saveResult.onSuccess {
                saved += invoice
                flow.value = saved.toList()
            }

        override fun getInvoices(): Flow<List<Invoice>> = flow

        override suspend fun getInvoiceById(id: String): Invoice? =
            saved.firstOrNull { it.id == id }

        override suspend fun deleteInvoice(id: String): Result<Unit> {
            saved.removeAll { it.id == id }
            flow.value = saved.toList()
            return Result.success(Unit)
        }
    }

    private companion object {
        const val OLLAMA_PORT = 11434
        const val PATH_TAGS = "/api/tags"
        const val PATH_CHAT = "/api/chat"

        const val TAGS_JSON = """{"models": [{"name": "qwen2.5:3b"}]}"""

        const val STUB_INVOICE_JSON = """{"invoiceNumber":"9001","date":"1403/05/20",""" +
            """"sellerName":"فروشنده تستی","items":[{"name":"قلم","quantity":2.0,""" +
            """"unitPrice":1000.0,"totalPrice":2000.0}],"grandTotal":2000.0,"currency":"TOMAN"}"""
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
