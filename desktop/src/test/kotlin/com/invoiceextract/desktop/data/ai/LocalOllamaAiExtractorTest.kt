package com.invoiceextract.desktop.data.ai

import com.invoiceextract.domain.model.CurrencyType
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM tests for the pure pieces of [LocalOllamaAiExtractor].
 *
 * The HTTP layer is deliberately not covered here: exercising `/api/tags` and
 * `/api/chat` would need a live daemon or a mock web server, while every real
 * correctness risk in an offline extractor — schema mapping, confidence clamping,
 * fence-stripping robustness — sits in the pure helpers below and runs in milliseconds.
 */
class LocalOllamaAiExtractorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val extractor = LocalOllamaAiExtractor(OkHttpClient(), json)

    @Test
    fun `full payload maps to domain invoice`() {
        val invoice = SAMPLE_PAYLOAD.toDomain(rawOcrText = OCR)

        assertEquals("10234567890", invoice.invoiceNumber)
        assertEquals("1403/05/20", invoice.date)
        assertEquals("شرکت نمونه", invoice.sellerName)
        assertEquals("14008238774", invoice.sellerTaxId)
        assertEquals("علی محمدی", invoice.buyerName)
        assertEquals("14007654321", invoice.buyerTaxId)
        assertEquals(3_000_000.0, invoice.subtotal, DELTA)
        assertEquals(270_000.0, invoice.totalTax, DELTA)
        assertEquals(0.0, invoice.totalDiscount, DELTA)
        assertEquals(3_270_000.0, invoice.grandTotal, DELTA)
        assertEquals(1, invoice.items.size)
        // rawOcrText comes from the caller, never from the model, so extraction stays
        // auditable even when the model truncates the echo.
        assertEquals(OCR, invoice.rawOcrText)
    }

    @Test
    fun `every item gets a fresh system-assigned id`() {
        val invoice = SAMPLE_PAYLOAD.copy(items = listOf(SAMPLE_ITEM, SAMPLE_ITEM)).toDomain("")

        val ids = invoice.items.map { it.id }
        assertEquals(2, ids.size)
        assertEquals("duplicate ids would collide as database keys", 2, ids.toSet().size)
        ids.forEach { assertTrue("ids are UUIDs, not model output", it.isNotBlank()) }
    }

    @Test
    fun `confidence is clamped to the contract range`() {
        val high = SAMPLE_ITEM.copy(confidence = 1.4f).toDomainTest()
        val low = SAMPLE_ITEM.copy(confidence = -0.25f).toDomainTest()

        assertEquals(1f, high.confidence, FLOAT_DELTA)
        assertEquals(0f, low.confidence, FLOAT_DELTA)
    }

    @Test
    fun `blank item name degrades to the Persian placeholder`() {
        val invoice = SAMPLE_PAYLOAD.copy(items = listOf(SAMPLE_ITEM.copy(name = "   "))).toDomain("")

        assertEquals("نامشخص", invoice.items.single().name)
    }

    @Test
    fun `unknown currency degrades to UNKNOWN and recognised ones map exactly`() {
        val toman = SAMPLE_PAYLOAD.copy(currency = "TOMAN").toDomain("").currency
        val rial = SAMPLE_PAYLOAD.copy(currency = "rial").toDomain("").currency
        val usd = SAMPLE_PAYLOAD.copy(currency = "usd").toDomain("").currency
        val eur = SAMPLE_PAYLOAD.copy(currency = "EUR").toDomain("").currency
        val usdt = SAMPLE_PAYLOAD.copy(currency = "usdt").toDomain("").currency
        val junk = SAMPLE_PAYLOAD.copy(currency = "GBP").toDomain("").currency

        assertEquals(CurrencyType.TOMAN, toman)
        assertEquals(CurrencyType.RIAL, rial)
        assertEquals(CurrencyType.USD, usd)
        assertEquals(CurrencyType.EUR, eur)
        assertEquals(CurrencyType.USDT, usdt)
        assertEquals(CurrencyType.UNKNOWN, junk)
    }

    @Test
    fun `chat request caps gpu offload inside the seventy percent band`() {
        val options = OllamaChatRequest(
            model = "qwen2.5:3b",
            messages = listOf(OllamaChatMessage("system", "prompt")),
        ).options

        // 26 of qwen2.5:3b's 36 layers ≈ 72%: full-GPU spikes, thermal
        // throttling and UI lag stay out of reach by construction.
        assertEquals(26.0, options.getValue("num_gpu"), DELTA)
        assertEquals(4.0, options.getValue("num_thread"), DELTA)
        assertEquals(0.1, options.getValue("temperature"), DELTA)
    }

    @Test
    fun `chat request serializes the gpu options for the daemon`() {
        val body = json.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest(
                model = "qwen2.5:3b",
                messages = listOf(OllamaChatMessage("system", "prompt")),
            ),
        )

        assertTrue("options missing in: $body", body.contains("\"num_gpu\":26.0"))
        assertTrue("options missing in: $body", body.contains("\"num_thread\":4.0"))
        assertTrue("options missing in: $body", body.contains("\"temperature\":0.1"))
    }

    @Test
    fun `payload decodes even when the model adds unknown fields`() {
        val withNoise = """
            {"invoiceNumber":"1","date":null,"sellerName":null,"sellerTaxId":null,
             "buyerName":null,"buyerTaxId":null,"items":[],"subtotal":0.0,"totalTax":0.0,
             "totalDiscount":0.0,"grandTotal":0.0,"currency":"TOMAN",
             "model":"qwen2.5:3b","thinking":"the header is null"}
        """.trimIndent()

        val invoice = json.decodeFromString<OllamaInvoiceDto>(withNoise).toDomain("")

        assertEquals("1", invoice.invoiceNumber)
        assertTrue(invoice.items.isEmpty())
    }

    @Test
    fun `chat envelope decodes while ignoring daemon telemetry`() {
        val envelope = """
            {"model":"qwen2.5:3b","created_at":"2025-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"{}"},"done":true,
             "total_duration":1234,"eval_count":56}
        """.trimIndent()

        assertEquals("{}", json.decodeFromString<OllamaChatResponse>(envelope).message.content)
    }

    @Test
    fun `fences with a json tag are stripped`() {
        assertEquals(EXPECTED_JSON, stripMarkdownFences("```json\n$EXPECTED_JSON\n```"))
    }

    @Test
    fun `fences without a language tag are stripped`() {
        assertEquals(EXPECTED_JSON, stripMarkdownFences("```\n$EXPECTED_JSON\n```"))
    }

    @Test
    fun `text without fences passes through untouched`() {
        assertEquals(EXPECTED_JSON, stripMarkdownFences(EXPECTED_JSON))
    }

    @Test
    fun `json object is recovered from surrounding prose`() {
        val withPreamble = "Here is the invoice:\n$EXPECTED_JSON\nDone."

        assertEquals(EXPECTED_JSON, extractJsonObject(withPreamble))
    }

    @Test
    fun `text without braces is returned unchanged`() {
        assertEquals("not json at all", extractJsonObject("not json at all"))
    }

    @Test
    fun `resolveModel prefers the default then falls back in priority order`() {
        assertEquals("qwen2.5:3b", extractor.resolveModel(listOf("llama3.1:8b", "qwen2.5:3b")))
        assertEquals("qwen2.5:7b", extractor.resolveModel(listOf("deepseek-r1", "qwen2.5:7b")))
        assertEquals("deepseek-r1", extractor.resolveModel(listOf("deepseek-r1")))
    }

    @Test(expected = LocalExtractionException.ModelNotAvailable::class)
    fun `resolveModel reports the default model when none is installed`() {
        try {
            extractor.resolveModel(listOf("llama3.1:8b"))
        } catch (e: LocalExtractionException.ModelNotAvailable) {
            assertEquals("qwen2.5:3b", e.requiredModel)
            throw e
        }
    }

    @Test
    fun `downloading chunk maps to byte counters and a percent`() {
        val progress = extractor.parsePullChunk(DOWNLOADING_CHUNK)

        val downloading = progress as? PullProgress.Downloading
        assertNotNull("a downloading chunk emits byte counters", downloading)
        assertEquals(500_000_000L, downloading!!.completedBytes)
        assertEquals(2_000_000_000L, downloading.totalBytes)
        assertEquals(0.25f, downloading.percent, FLOAT_DELTA)
    }

    @Test
    fun `success chunk completes the pull even when it carries counters`() {
        // The terminal event must win over byte counters on the same line, or the stream
        // would report one more download update after the pull has already finished.
        assertTrue(extractor.parsePullChunk(SUCCESS_CHUNK) is PullProgress.Completed)
        assertTrue(extractor.parsePullChunk(SUCCESS_CHUNK_WITH_BYTES) is PullProgress.Completed)
    }

    @Test
    fun `status-only chunk surfaces as a status line`() {
        val progress = extractor.parsePullChunk(MANIFEST_CHUNK)

        assertEquals("pulling manifest", (progress as? PullProgress.Status)?.message)
    }

    @Test
    fun `chunk without status or counters is skipped`() {
        // A digest line in the stream carries nothing the progress bar can draw.
        assertNull(extractor.parsePullChunk(DIGEST_ONLY_CHUNK))
    }

    @Test
    fun `unknown fields on a pull chunk are ignored`() {
        // The daemon writes a digest onto every downloading chunk; the lenient Json must
        // drop it rather than fail the whole pull.
        val progress = extractor.parsePullChunk(DOWNLOADING_CHUNK_WITH_DIGEST)

        assertTrue(progress is PullProgress.Downloading)
    }

    @Test
    fun `domain schema is on the desktop runtime classpath`() {
        val stream = Thread.currentThread().contextClassLoader
            .getResourceAsStream("invoice_extraction_schema.json")

        assertNotNull("the :domain schema must ship in the desktop classpath", stream)
        val text = stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue(text.contains("invoiceNumber"))
        assertFalse(text.isEmpty())
    }

    private fun OllamaItemDto.toDomainTest(): com.invoiceextract.domain.model.InvoiceItem =
        SAMPLE_PAYLOAD.copy(items = listOf(this)).toDomain("").items.single()

    private companion object {
        const val DELTA = 0.0
        const val FLOAT_DELTA = 0f
        const val OCR = "فاکتور فروش\nشماره ۱۰۲۳۴۵۶۷۸۹۰"

        val SAMPLE_ITEM = OllamaItemDto(
            name = "هدفون بی‌سیم",
            quantity = 2.0,
            unitPrice = 1_500_000.0,
            discount = 0.0,
            tax = 270_000.0,
            totalPrice = 3_270_000.0,
            confidence = 0.92f,
            isSuspicious = false,
        )

        val SAMPLE_PAYLOAD = OllamaInvoiceDto(
            invoiceNumber = "10234567890",
            date = "1403/05/20",
            sellerName = "شرکت نمونه",
            sellerTaxId = "14008238774",
            buyerName = "علی محمدی",
            buyerTaxId = "14007654321",
            items = listOf(SAMPLE_ITEM),
            subtotal = 3_000_000.0,
            totalTax = 270_000.0,
            totalDiscount = 0.0,
            grandTotal = 3_270_000.0,
            currency = "TOMAN",
        )

        const val EXPECTED_JSON = """{"invoiceNumber":"10234567890","currency":"TOMAN"}"""

        // One chunk per stage of a `POST /api/pull` stream, as the daemon actually writes them.
        const val DOWNLOADING_CHUNK =
            """{"status":"downloading","total":2000000000,"completed":500000000}"""
        const val DOWNLOADING_CHUNK_WITH_DIGEST =
            """{"status":"downloading","digest":"sha256:8c28b3","total":2000000000,"completed":1000000000}"""
        const val SUCCESS_CHUNK = """{"status":"success"}"""
        const val SUCCESS_CHUNK_WITH_BYTES =
            """{"status":"success","total":2000000000,"completed":2000000000}"""
        const val MANIFEST_CHUNK = """{"status":"pulling manifest"}"""
        const val DIGEST_ONLY_CHUNK = """{"digest":"sha256:8c28b3"}"""
    }
}
