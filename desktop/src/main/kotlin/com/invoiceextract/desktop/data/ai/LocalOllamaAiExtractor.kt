package com.invoiceextract.desktop.data.ai

import com.invoiceextract.domain.extractor.InvoiceAiExtractor
import com.invoiceextract.domain.model.CurrencyType
import com.invoiceextract.domain.model.Invoice
import com.invoiceextract.domain.model.InvoiceItem
import com.invoiceextract.domain.validation.ValidationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 100% offline [InvoiceAiExtractor] driving a locally running Ollama daemon
 * (`http://127.0.0.1:11434`).
 *
 * The whole call graph stays inside the machine: no proxy, no provider key, no
 * network egress. That is the point of the desktop port — the Android app routes
 * extraction through a Cloudflare Worker because a phone cannot run a useful LLM,
 * while a desktop can, so the worker is replaced by this class and nothing else in
 * the pipeline changes.
 *
 * **Conversation.** A strict system prompt plus the domain's JSON Schema
 * (`resources/invoice_extraction_schema.json`, loaded from the classpath so it stays
 * the single source of truth) is sent as the only system turn; the OCR text is the
 * only user turn. The request asks for non-streamed structured JSON output, so the
 * response is one decodable object.
 *
 * **Robustness.** Models wrap output in markdown fences or add prose around it more
 * often than they admit, so the body is fence-stripped and the outermost JSON object
 * is located before decoding. The injected [Json] must be configured with
 * `ignoreUnknownKeys` and `coerceInputValues` (see the desktop DI module) because both
 * the daemon envelopes and the model payloads carry fields the DTOs do not model.
 *
 * **Errors.** Every failure path is folded into one typed [LocalExtractionException]
 * inside [Result.failure], matching the domain contract that callers never have to
 * catch. Messages are Persian because they are shown verbatim to the user; engineering
 * detail (HTTP codes, exception classes) is kept on [Throwable.cause] for logging.
 *
 * @param client The OkHttp client. Must have generous timeouts: local LLM inference is
 *   slow, and a default 10s read timeout would fail every extraction on a loaded CPU.
 * @param json The serializer. Requires `ignoreUnknownKeys = true` and
 *   `coerceInputValues = true`, or the daemon's telemetry fields and the model's
 *   explicit nulls will crash parsing instead of degrading to defaults.
 */
class LocalOllamaAiExtractor(
    private val client: OkHttpClient,
    private val json: Json,
) : InvoiceAiExtractor {

    /**
     * Reports whether the local stack can extract right now, without touching the
     * chat endpoint: it reads the installed catalog from `/api/tags` and matches it
     * against the models the extractor can drive.
     *
     * Transport failures are reported as [OllamaStatus.OllamaNotRunning] rather than
     * an exception — a status probe is allowed to fail, and the caller wants a state
     * to render, not a stack trace.
     */
    suspend fun checkStatus(): OllamaStatus = withContext(Dispatchers.IO) {
        runCatching { resolveModel(listInstalledModels()) }
            .fold(
                onSuccess = { resolved -> OllamaStatus.Ready(resolved) },
                onFailure = { cause ->
                    when (cause) {
                        is LocalExtractionException.ModelNotAvailable ->
                            OllamaStatus.ModelMissing(cause.requiredModel)

                        // Unreachable daemon, unreadable catalog, wrong API version:
                        // for the user there is one remedy — start Ollama.
                        else -> OllamaStatus.OllamaNotRunning
                    }
                },
            )
    }

    /**
     * Streams a model download from the daemon's `/api/pull` endpoint.
     *
     * This is the only call in the extractor that reads a response body incrementally: the
     * ~1.9 GB default model cannot arrive as a single object, so Ollama answers with
     * newline-delimited progress chunks and each one becomes a step of the progress bar the
     * window renders.
     *
     * The flow is cold, so nothing is requested until a collector appears, and it stays on
     * [Dispatchers.IO] so the blocking line reads never occupy the render thread. Cancelling
     * the collection closes the response, which drops the socket and stops the transfer on
     * the daemon side too, instead of leaving a 2 GB pull running unseen in the background.
     *
     * Failures are folded into a typed [LocalExtractionException] exactly like every other
     * call here, so callers switch on the one hierarchy and show its Persian message instead
     * of an OkHttp stack trace. A stream that ends without Ollama's terminal `success` chunk
     * is reported as a network failure rather than a quiet success, so the UI never sits on
     * a stuck progress bar waiting for an event that will not come.
     *
     * @param modelName The tag to pull, e.g. `"qwen2.5:3b"`.
     * @return A cold stream of [PullProgress] updates, ending in [PullProgress.Completed]
     *   once the model is installed and resolvable.
     */
    fun pullModel(modelName: String): Flow<PullProgress> = flow {
        execute(buildPullRequest(modelName)).use { response ->
            if (!response.isSuccessful) throw LocalExtractionException.Server(response.code)

            val source = response.body?.source()
                ?: throw LocalExtractionException.EmptyResponse

            var successSeen = false
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue

                val progress = parsePullChunk(line) ?: continue
                emit(progress)

                if (progress is PullProgress.Completed) {
                    successSeen = true
                    break
                }
            }

            // A body that ends without `success` means the transfer was cut short — the
            // daemon was killed, the disk filled, the socket dropped. Reporting it as a
            // failure is what keeps the window from waiting on a terminal event forever.
            if (!successSeen) {
                throw LocalExtractionException.Network(IOException(STREAM_ENDED_BEFORE_SUCCESS))
            }
        }
    }.catch { cause ->
        // `catch` sees upstream failures only and is transparent to cancellation, so a
        // cancelled pull unwinds as a cancellation instead of being reclassified as an error.
        throw when (cause) {
            is LocalExtractionException -> cause
            // A refused connection on localhost can only mean the daemon is down, so it gets
            // the user-facing "Ollama is not running" message rather than the generic one.
            is ConnectException -> LocalExtractionException.OllamaNotRunning
            is IOException -> LocalExtractionException.Network(cause)
            else -> LocalExtractionException.Unknown(cause)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Turns OCR text into a structured [Invoice] via the local model.
     *
     * Never throws: the domain contract returns [Result], so callers switch on
     * [LocalExtractionException] when they want to and are never forced to catch.
     * Cancellation is deliberately re-thrown instead of being reclassified, so tearing
     * the pipeline down on window close unwinds the call instead of showing an error.
     */
    override suspend fun extractFromText(ocrText: String): Result<Invoice> =
        withContext(Dispatchers.IO) {
            if (ocrText.isBlank()) {
                return@withContext Result.failure(LocalExtractionException.EmptyInput)
            }

            runCatching {
                // Resolves the model first so a misconfigured daemon fails fast with
                // the actionable "install this model" message instead of a 404 from
                // the chat endpoint.
                val modelName = resolveModel(listInstalledModels())

                val response = execute(buildChatRequest(ocrText, modelName))
                handleChatResponse(response, ocrText)
            }.recoverCatching { cause ->
                if (cause.isCancellation()) throw cause

                throw when (cause) {
                    is LocalExtractionException -> cause
                    // A refused connection on localhost can only mean the daemon is
                    // down, so it gets the user-facing "Ollama is not running" message
                    // rather than the generic network failure text.
                    is ConnectException -> LocalExtractionException.OllamaNotRunning
                    is IOException -> LocalExtractionException.Network(cause)
                    else -> LocalExtractionException.Unknown(cause)
                }
            }
        }

    /**
     * Lists the models the daemon reports via `/api/tags`, by name.
     *
     * Any failure here is fatal for both callers: [checkStatus] folds it into
     * [OllamaStatus.OllamaNotRunning] and [extractFromText] reports it as an error.
     */
    private suspend fun listInstalledModels(): List<String> {
        val request = Request.Builder()
            .url("$OLLAMA_BASE_URL$PATH_TAGS")
            .get()
            .build()

        return execute(request).use { response ->
            if (!response.isSuccessful) throw LocalExtractionException.Server(response.code)

            val body = response.body?.string()
            if (body.isNullOrBlank()) throw LocalExtractionException.EmptyResponse

            json.decodeFromString<OllamaTagsResponse>(body).models.map { it.name }
        }
    }

    /**
     * Picks the model to drive. Prefers [DEFAULT_MODEL]; falls back to the larger Qwen
     * and then to DeepSeek so a machine with a bigger GPU is used without config, and
     * a machine with only DeepSeek installed still works.
     *
     * Matching is case-insensitive because Ollama normalises tags but callers may not.
     *
     * @return The first installed model in priority order.
     * @throws LocalExtractionException.ModelNotAvailable when none is installed.
     */
    internal fun resolveModel(installed: List<String>): String {
        for (candidate in PREFERRED_MODELS) {
            if (installed.any { it.equals(candidate, ignoreCase = true) }) return candidate
        }
        throw LocalExtractionException.ModelNotAvailable(DEFAULT_MODEL)
    }

    /**
     * Builds the `/api/pull` request: the model tag and a request for a streaming answer.
     */
    private fun buildPullRequest(modelName: String): Request {
        val body = json.encodeToString(OllamaPullRequest(name = modelName))
            .toRequestBody(JSON_MEDIA_TYPE.toMediaType())

        return Request.Builder()
            .url("$OLLAMA_BASE_URL$PATH_PULL")
            .post(body)
            .build()
    }

    /**
     * Decodes one `/api/pull` chunk into the [PullProgress] the UI renders, or `null` for a
     * chunk that carries nothing displayable.
     *
     * Byte counters only ride on `downloading` chunks, and a zero `total` is skipped rather
     * than divided, which would yield a `NaN` percent the progress bar cannot draw. The
     * terminal `success` status is matched first, so a success chunk that also carries the
     * final counters still ends the stream with exactly one [PullProgress.Completed].
     */
    internal fun parsePullChunk(line: String): PullProgress? {
        val chunk = json.decodeFromString<OllamaPullChunk>(line)

        return when {
            chunk.status.equals(STATUS_SUCCESS, ignoreCase = true) -> PullProgress.Completed
            chunk.total != null && chunk.completed != null && chunk.total > 0 ->
                PullProgress.Downloading(
                    completedBytes = chunk.completed,
                    totalBytes = chunk.total,
                    percent = chunk.completed.toFloat() / chunk.total.toFloat(),
                )
            !chunk.status.isNullOrBlank() -> PullProgress.Status(chunk.status)
            else -> null
        }
    }

    /**
     * Builds the `/api/chat` request: one system turn carrying the prompt and schema,
     * one user turn carrying the OCR text.
     *
     * `stream = false` is passed *explicitly* rather than left to the data class default:
     * the extractor decodes exactly one JSON object out of the body, so a streamed
     * NDJSON answer (`{"done": false}\n{"done": false}…`) makes the parser fail with
     * "Expected EOF after parsing, but had { instead". [OllamaChatRequest.stream] is
     * also serialized explicitly so the flag reaches the daemon regardless of the
     * injected [Json]'s `encodeDefaults` setting.
     */
    private fun buildChatRequest(ocrText: String, model: String): Request {
        val payload = OllamaChatRequest(
            model = model,
            messages = listOf(
                OllamaChatMessage(ROLE_SYSTEM, buildSystemPrompt()),
                OllamaChatMessage(ROLE_USER, buildUserPrompt(ocrText)),
            ),
            stream = false,
        )

        val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE.toMediaType())

        return Request.Builder()
            .url("$OLLAMA_BASE_URL$PATH_CHAT")
            .post(body)
            .build()
    }

    /**
     * Consumes one chat response into a domain [Invoice], closing the body in every
     * branch so the connection returns to the pool.
     *
     * The request pins `stream: false`, so a compliant daemon answers with exactly one
     * object whose `message.content` holds the extracted invoice JSON. [assistantContent]
     * still tolerates a streamed NDJSON body, so a daemon or proxy that ignores the flag
     * degrades to a working extraction instead of a parse crash.
     */
    private fun handleChatResponse(response: Response, ocrText: String): Invoice =
        response.use { closed ->
            if (!closed.isSuccessful) throw LocalExtractionException.Server(closed.code)

            val raw = closed.body?.string()
            if (raw.isNullOrBlank()) throw LocalExtractionException.EmptyResponse

            val content = assistantContent(raw)
            if (content.isBlank()) throw LocalExtractionException.EmptyResponse

            // The model is told to emit JSON only and still occasionally adds fences or
            // a sentence of preamble; recovering the object rather than failing keeps
            // a well-extracted invoice from being dropped over formatting.
            val payload = extractJsonObject(stripMarkdownFences(content))

            val dto = runCatching {
                json.decodeFromString<OllamaInvoiceDto>(payload)
            }.getOrElse { cause ->
                throw LocalExtractionException.MalformedResponse(cause)
            }

            dto.toDomain(rawOcrText = ocrText)
        }

    /**
     * Reads the assistant's text out of a chat body.
     *
     * Fast path is the single non-streamed object the request asked for. If the strict
     * single-object decode fails, the body is newline-delimited chunks — meaning the
     * daemon streamed anyway — and the fragments are reassembled in order, the same way
     * the pull endpoint reads its own stream. Failing on both shapes re-throws the
     * single-object error so the typed handler above reports a real cause rather than
     * a misleading "empty response".
     */
    private fun assistantContent(raw: String): String {
        val single = runCatching { json.decodeFromString<OllamaChatResponse>(raw).message.content }
        if (single.isSuccess) return single.getOrNull().orEmpty()

        val reassembled = raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { json.decodeFromString<OllamaChatResponse>(line) }.getOrNull() }
            .joinToString("") { chunk -> chunk.message.content }

        if (reassembled.isNotBlank()) return reassembled

        throw single.exceptionOrNull() ?: LocalExtractionException.EmptyResponse
    }

    /**
     * The system prompt: a strict extraction contract for Persian invoices, followed by
     * the domain's JSON Schema so the model's output validates against it.
     */
    private fun buildSystemPrompt(): String =
        "$SYSTEM_PROMPT\n\n$JSON_SCHEMA_HEADER\n$extractionSchema"

    private fun buildUserPrompt(ocrText: String): String = """
        OCR text of the invoice follows, enclosed in <ocr> tags:
        <ocr>
        $ocrText
        </ocr>
    """.trimIndent()

    /**
     * The domain schema, read once and cached: it is a build-time asset, so re-reading
     * it per extraction would only add I/O to every call.
     */
    private val extractionSchema: String by lazy {
        Thread.currentThread().contextClassLoader
            ?.getResourceAsStream(SCHEMA_RESOURCE)
            ?.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
            ?: throw LocalExtractionException.SchemaUnavailable
    }

    /**
     * Runs [request] on OkHttp's dispatcher pool and suspends until it completes.
     *
     * `suspendCancellableCoroutine` is what makes cancellation work end to end: on
     * coroutine cancellation the block cancels the [Call], closing the socket instead
     * of leaving the connection to time itself out.
     */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)

            continuation.invokeOnCancellation { runCatching { call.cancel() } }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            })
        }

    private companion object {
        const val OLLAMA_BASE_URL = "http://127.0.0.1:11434"
        const val DEFAULT_MODEL = "qwen2.5:3b"

        /** Resolution order: the 3B default, then the stronger Qwen, then DeepSeek. */
        val PREFERRED_MODELS = listOf(DEFAULT_MODEL, "qwen2.5:7b", "deepseek-r1")

        const val PATH_TAGS = "/api/tags"
        const val PATH_CHAT = "/api/chat"
        const val PATH_PULL = "/api/pull"

        // The status string Ollama writes on the terminal chunk of a `/api/pull` stream.
        const val STATUS_SUCCESS = "success"

        // Cause kept for logging when the stream ends without that terminal chunk.
        const val STREAM_ENDED_BEFORE_SUCCESS =
            "Ollama stopped streaming before the pull completed."

        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"

        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val JSON_SCHEMA_HEADER = "JSON Schema the response MUST validate against:"

        const val SCHEMA_RESOURCE = "invoice_extraction_schema.json"
    }
}

/**
 * The single typed failure the local extraction layer can produce.
 *
 * Mirrors the Android app's `ExtractionException`: every failure — a dead daemon, a
 * missing model, an unparseable answer — is folded into one of these so callers switch
 * on this type only when they want to, and never on OkHttp's exception hierarchy.
 *
 * Sealed so a `when` over the subtypes is exhaustive: adding a failure mode is a
 * compile error everywhere it is handled until every branch is covered.
 */
sealed class LocalExtractionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The OCR stage handed over blank text, so there is nothing to send the model. */
    object EmptyInput : LocalExtractionException(MSG_EMPTY_INPUT)

    /** Nothing is listening on the local daemon port. Remedy: start `ollama serve`. */
    object OllamaNotRunning : LocalExtractionException(MSG_OLLAMA_NOT_RUNNING)

    /**
     * The daemon is up but none of the drivable models is installed.
     *
     * @property requiredModel The model the user should pull; reported in the message.
     */
    class ModelNotAvailable(val requiredModel: String) :
        LocalExtractionException(MSG_MODEL_MISSING.format(requiredModel, requiredModel))

    /** The daemon answered a failure code. [code] is kept for logging, not for the UI. */
    class Server(val code: Int) : LocalExtractionException(MSG_SERVER)

    /** The daemon returned a body with no extractable JSON, so no invoice can be built. */
    object EmptyResponse : LocalExtractionException(MSG_EMPTY_RESPONSE)

    /** A well-formed answer that does not conform to the schema. Carries the parse cause. */
    class MalformedResponse(cause: Throwable) : LocalExtractionException(MSG_MALFORMED, cause)

    /** Transport failure while waiting on local LLM reasoning (e.g. a read timeout). */
    class Network(cause: Throwable) : LocalExtractionException(MSG_NETWORK, cause)

    /** Anything the extractor did not classify. */
    class Unknown(cause: Throwable) : LocalExtractionException(MSG_UNKNOWN, cause)

    /** The bundled domain schema could not be read off the classpath — a packaging bug. */
    object SchemaUnavailable : LocalExtractionException(MSG_SCHEMA_UNAVAILABLE)

    private companion object {
        const val MSG_EMPTY_INPUT = "متن استخراج‌شده از سند خالی است؛ چیزی برای پردازش وجود ندارد."
        const val MSG_OLLAMA_NOT_RUNNING =
            "نرم‌افزار Ollama در حال اجرا نیست. ابتدا آن را با دستور «ollama serve» اجرا کنید."
        const val MSG_MODEL_MISSING =
            "مدل %s روی Ollama نصب نیست. آن را با دستور «ollama pull %s» دانلود کنید."
        const val MSG_SERVER = "خطا در برقراری ارتباط با Ollama. لطفاً دوباره تلاش کنید."
        const val MSG_EMPTY_RESPONSE = "پاسخ خالی از Ollama دریافت شد."
        const val MSG_MALFORMED = "پاسخ Ollama ساختار قابل‌قبولی نداشت؛ فاکتور استخراج نشد."
        const val MSG_NETWORK =
            "خطا در برقراری ارتباط با Ollama. مطمئن شوید سرویس محلی در حال اجرا است."
        const val MSG_UNKNOWN = "خطای ناشناخته هنگام استخراج فاکتور."
        const val MSG_SCHEMA_UNAVAILABLE = "طرح‌واره استخراج فاکتور یافت نشد."
    }
}

/**
 * One update from a streaming model download, as emitted by
 * [LocalOllamaAiExtractor.pullModel].
 *
 * Sealed on purpose: the download panel switches on this type to draw one exact view per
 * stage, so adding a stage is a compile error in the screen until it is drawn, and a panel
 * can never silently render nothing while a 1.9 GB transfer is running.
 */
sealed interface PullProgress {

    /**
     * Bytes are moving from the Ollama registry into the local model store.
     *
     * @property completedBytes Bytes fetched so far, from the chunk's `completed` field.
     * @property totalBytes Total size of the model's layer set, from the chunk's `total`
     *   field.
     * @property percent [completedBytes] / [totalBytes], precomputed and already guarded
     *   against a zero total by the extractor, so the progress bar divides nothing and clamps
     *   nothing itself.
     */
    data class Downloading(
        val completedBytes: Long,
        val totalBytes: Long,
        val percent: Float,
    ) : PullProgress

    /**
     * A status line from the daemon that carries no byte counters — "pulling manifest",
     * "verifying sha256 digest" — and, by convention, the Persian sentence of a failed pull,
     * which the view model publishes through the same channel.
     */
    data class Status(val message: String) : PullProgress

    /** The pull finished and the model is installed; the caller may re-probe the daemon. */
    data object Completed : PullProgress
}

/**
 * The extracted invoice on the wire, mirroring the domain's JSON Schema field-for-field
 * (camelCase, so no [kotlinx.serialization.SerialName] is needed).
 *
 * Header fields are nullable and numeric fields default to zero: a model legitimately
 * omits a buyer or a discount, and `coerceInputValues` turns an explicit JSON `null`
 * into that default rather than crashing. Required item fields that go missing still
 * fail parsing, which is correct — an item with no name is not an item.
 */
@Serializable
internal data class OllamaInvoiceDto(
    val invoiceNumber: String? = null,
    val date: String? = null,
    val sellerName: String? = null,
    val sellerTaxId: String? = null,
    val buyerName: String? = null,
    val buyerTaxId: String? = null,
    // Pass-through for national identifiers the model may emit once the extraction
    // schema asks for them. Defaulted so today's prompt — which does not request them —
    // decodes unchanged; `coerceInputValues` already turns explicit nulls into this.
    val sellerNationalId: String? = null,
    val buyerNationalId: String? = null,
    val items: List<OllamaItemDto> = emptyList(),
    val subtotal: Double = 0.0,
    val totalTax: Double = 0.0,
    val totalDiscount: Double = 0.0,
    val grandTotal: Double = 0.0,
    val currency: String = "TOMAN",
)

@Serializable
internal data class OllamaItemDto(
    val name: String,
    val quantity: Double = 1.0,
    val unitPrice: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val totalPrice: Double = 0.0,
    val confidence: Float = 1.0f,
    val isSuspicious: Boolean = false,
)

/**
 * Converts the wire payload into the domain aggregate.
 *
 * This is the only place the AI layer touches the domain, so it is the only place that
 * has to be defensive:
 * - Ids are generated here, never trusted from the model: an LLM is not a reliable
 *   source of unique keys, and a duplicate id would collide as a database primary key
 *   and silently drop a line on save.
 * - Confidence is clamped because [InvoiceItem]'s contract requires `0f..1f` and
 *   throws otherwise — a model emitting `1.05` must not crash the pipeline.
 * - Currency degrades to [CurrencyType.UNKNOWN], never to an exception.
 *
 * @param rawOcrText The verbatim OCR text that produced this response, kept on the
 *   invoice for auditing and re-extraction.
 */
internal fun OllamaInvoiceDto.toDomain(rawOcrText: String): Invoice = Invoice(
    id = UUID.randomUUID().toString(),
    invoiceNumber = invoiceNumber,
    date = date,
    sellerName = sellerName,
    sellerTaxId = sellerTaxId,
    buyerName = buyerName,
    buyerTaxId = buyerTaxId,
    sellerNationalId = sellerNationalId?.ifBlank { null },
    buyerNationalId = buyerNationalId?.ifBlank { null },
    items = items.map { it.toDomain() },
    subtotal = subtotal,
    totalTax = totalTax,
    totalDiscount = totalDiscount,
    grandTotal = grandTotal,
    currency = parseCurrency(currency),
    rawOcrText = rawOcrText,
    validationStatus = ValidationStatus.Valid,
)

private fun OllamaItemDto.toDomain(): InvoiceItem = InvoiceItem(
    id = UUID.randomUUID().toString(),
    name = name.trim().ifBlank { DEFAULT_ITEM_NAME },
    quantity = quantity,
    unitPrice = unitPrice,
    discount = discount,
    tax = tax,
    totalPrice = totalPrice,
    confidence = confidence.coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE),
    isSuspicious = isSuspicious,
)

/** Case-insensitive currency parse; anything unrecognised becomes [CurrencyType.UNKNOWN]. */
private fun parseCurrency(value: String?): CurrencyType = when (value?.trim()?.uppercase()) {
    "TOMAN" -> CurrencyType.TOMAN
    "RIAL" -> CurrencyType.RIAL
    "USD", "$", "DOLLAR", "DOLLARS" -> CurrencyType.USD
    "EUR", "€", "EURO", "EUROS" -> CurrencyType.EUR
    "USDT", "TETHER", "₮" -> CurrencyType.USDT
    else -> CurrencyType.UNKNOWN
}

/**
 * Removes ```json … ``` wrappers when a model adds them despite being told not to.
 *
 * Handles the fence with and without a language tag, and a stray closing fence with no
 * opening one; when there is no fence at all the text passes through untouched.
 */
internal fun stripMarkdownFences(raw: String): String {
    val text = raw.trim()

    // `removeSurrounding` first, as the exact inverse of the two wrappers a model
    // actually emits: ```json … ``` and the untagged ``` … ```. Order matters — the
    // tagged pair is a strict superset of the untagged one, so it is probed first.
    val unwrapped = text
        .removeSurrounding(prefix = MARKDOWN_FENCE_WITH_LANG, suffix = MARKDOWN_FENCE)
        .removeSurrounding(prefix = MARKDOWN_FENCE, suffix = MARKDOWN_FENCE)

    if (unwrapped != text) return stripLanguageTag(unwrapped).trim()

    // No matched pair: fall back to the older, looser recovery below.
    if (!text.startsWith(MARKDOWN_FENCE)) return text

    val afterOpening = stripLanguageTag(text.removePrefix(MARKDOWN_FENCE))

    return afterOpening.substringBeforeLast(MARKDOWN_FENCE, afterOpening).trim()
}

/**
 * Drops a leading `json` language tag left by [stripMarkdownFences] when the model
 * uppercased it — `removeSurrounding` is case-sensitive, so ```JSON … ``` leaves the
 * tag behind after its fences come off.
 */
private fun stripLanguageTag(body: String): String =
    if (body.startsWith(JSON_LANG_TAG, ignoreCase = true)) body.drop(JSON_LANG_TAG.length) else body

/**
 * Locates the outermost JSON object inside [text], discarding any surrounding prose or
 * whitespace the model emitted. Returns [text] itself when it contains no braces, so a
 * genuinely malformed answer reaches the decoder and reports a precise parse error.
 */
internal fun extractJsonObject(text: String): String {
    val start = text.indexOf(OBJECT_OPEN)
    val end = text.lastIndexOf(OBJECT_CLOSE)
    return if (start != INDEX_NOT_FOUND && end > start) {
        text.substring(start, end + OBJECT_CLOSE_LENGTH)
    } else {
        text.trim()
    }
}

/** True only for coroutine cancellation, which must never become a user-facing failure. */
private fun Throwable.isCancellation(): Boolean = this is CancellationException

private const val DEFAULT_ITEM_NAME = "نامشخص"
private const val MIN_CONFIDENCE = 0f
private const val MAX_CONFIDENCE = 1f

private const val MARKDOWN_FENCE = "```"
private const val JSON_LANG_TAG = "json"
private const val MARKDOWN_FENCE_WITH_LANG = "```json"
private const val OBJECT_OPEN = '{'
private const val OBJECT_CLOSE = '}'
private const val OBJECT_CLOSE_LENGTH = 1
private const val INDEX_NOT_FOUND = -1

private const val SYSTEM_PROMPT = """
You are a precision data-extraction engine for Persian (Iranian) invoices.

Given the raw OCR text of one invoice, you extract its fields and reply with ONE value:
a single JSON object that validates against the JSON Schema provided below.

Hard rules:
1. Reply with the JSON object ONLY. No prose, no greeting, no markdown, no code fences.
2. Field names and structure come from the schema. Do not add, rename or nest fields.
3. Transcribe values VERBATIM as printed on the document. Keep Persian text and Persian
   digits exactly; never translate, transliterate or normalize digits to ASCII.
4. Dates stay Persian/Solar (Jalali) exactly as printed, e.g. "1403/05/20".
5. Numbers are plain digits: no grouping separators, no locale decimal comma.
6. currency is exactly one of "TOMAN", "RIAL", "USD", "EUR", "USDT" or "UNKNOWN".
7. For any header field you cannot determine, emit null. For a number you cannot
   determine, emit the schema default (0 for amounts, 1 for quantity).
8. Per line, report confidence in the inclusive range 0.0..1.0 and set isSuspicious
   true when the line is low-confidence or arithmetically inconsistent with the rest.
9. Never invent ids, validationStatus or createdAtEpochMs; the application assigns them.
10. Ignore page furniture, OCR noise and repeated total rows; keep real line items only,
    in document order.
"""
