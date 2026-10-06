package com.invoiceextract.desktop.data.ai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.OptIn

/**
 * The health state of the local Ollama daemon, as seen by
 * [LocalOllamaAiExtractor.checkStatus].
 *
 * Sealed on purpose: the desktop UI switches on this type to render one exact call to
 * action per state (start the daemon, pull a model, or proceed), and a non-exhaustive
 * `when` would let a new state silently render as a blank screen. Adding a state is a
 * compile error everywhere until every branch is covered.
 */
sealed interface OllamaStatus {

    /**
     * Nothing is listening on `127.0.0.1:11434`.
     *
     * Reported on any transport failure against `/api/tags` — the only reason the
     * endpoint is unreachable on localhost is that the daemon is not running, so a
     * `ConnectException` and a read timeout are both surfaced as this one state
     * instead of leaking OkHttp's exception hierarchy into the UI.
     */
    data object OllamaNotRunning : OllamaStatus

    /**
     * The daemon is up, but none of the models the extractor can drive is installed.
     *
     * @property requiredModel The preferred model that should be pulled, reported to
     *   the user verbatim in a `pull` instruction. It is always the default model,
     *   never a fallback, so the prompt the UI renders matches the model the extractor
     *   will actually use once it is installed.
     */
    data class ModelMissing(val requiredModel: String) : OllamaStatus

    /**
     * The daemon is up and a supported model is installed; extraction can proceed.
     *
     * @property modelName The model that [LocalOllamaAiExtractor] resolved — the
     *   default when it is installed, otherwise the first available fallback — so the
     *   UI can show exactly which engine is doing the work.
     */
    data class Ready(val modelName: String) : OllamaStatus
}

/**
 * Wire response of `GET /api/tags`, the Ollama model catalog.
 *
 * Extra fields the daemon emits (sizes, digests, modification times) are dropped by the
 * `ignoreUnknownKeys` [kotlinx.serialization.json.Json] instance injected into the
 * extractor; only the names are needed to decide whether extraction can run.
 */
@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModelItem> = emptyList(),
)

/** One installed model in the [OllamaTagsResponse] catalog. */
@Serializable
data class OllamaModelItem(
    val name: String,
)

/**
 * Wire request body of `POST /api/chat`.
 *
 * - [stream] is `false`: the whole answer arrives in one response, so the extractor
 *   decodes a single JSON object instead of reassembling a newline-delimited stream.
 * - [format] is `"json"`: switches Ollama to structured output, which constrains the
 *   decoder to emit valid JSON for the requested schema instead of free-form prose.
 * - [options] pins [temperature][TEMPERATURE] near zero so two extractions of the same
 *   invoice produce the same numbers — an audit and a re-extraction must agree —
 *   and caps GPU offload at [GPU_LAYERS_QWEN_3B] of 36 layers (~72%) so inference
 *   never pins the GPU at 100%, which throttles thermals and lags the system UI.
 *   The remaining layers evaluate on CPU across [INFERENCE_THREADS] threads.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    /**
     * Always serialized, even when the injected [kotlinx.serialization.json.Json] leaves
     * `encodeDefaults` off. If this default were dropped from the body the daemon would
     * fall back to its own default — streaming — and answer with newline-delimited
     * chunks that the single-object chat decoder cannot parse.
     */
    @EncodeDefault val stream: Boolean = false,
    @EncodeDefault val format: String = "json",
    /**
     * Always serialized, like [stream] and [format] above: without this the
     * daemon falls back to full GPU offload and the 100% spikes this map
     * exists to prevent come straight back.
     */
    @EncodeDefault
    val options: Map<String, Double> = mapOf(
        TEMPERATURE to LOW_TEMPERATURE,
        NUM_GPU to GPU_LAYERS_QWEN_3B,
        NUM_THREAD to INFERENCE_THREADS,
    ),
)

/**
 * One message in the [OllamaChatRequest] conversation, and the message the daemon
 * answers with in [OllamaChatResponse].
 */
@Serializable
data class OllamaChatMessage(
    val role: String,
    val content: String,
)

/**
 * Wire response of `POST /api/chat`.
 *
 * Only the assistant message is interesting; the daemon also returns usage telemetry
 * (`total_duration`, `eval_count`, …) which the injected `Json` ignores.
 */
@Serializable
data class OllamaChatResponse(
    val message: OllamaChatMessage,
)

/**
 * Wire request body of `POST /api/pull`, the streaming model-download endpoint.
 *
 * - [name] is the tag to fetch, e.g. `"qwen2.5:3b"`.
 * - [stream] is `true`: the daemon answers with newline-delimited progress chunks instead of
 *   one final object, which is what lets the window draw a live progress bar instead of
 *   an hourglass over a ~2 GB transfer.
 */
@Serializable
data class OllamaPullRequest(
    val name: String,
    val stream: Boolean = true,
)

/**
 * One progress chunk in the newline-delimited `POST /api/pull` response.
 *
 * The daemon emits a run of these — `pulling manifest`, then a sequence of `downloading`
 * chunks carrying byte counters as each layer streams in, and finally one `success` chunk —
 * one JSON object per line.
 *
 * All three fields are nullable because they never appear all together: the manifest chunk
 * carries only a status, a `downloading` chunk carries counters plus a status, and the
 * terminal chunk carries only a status. Everything else the daemon writes into a chunk
 * (the layer digest) is dropped by the lenient injected [kotlinx.serialization.json.Json].
 */
@Serializable
data class OllamaPullChunk(
    val status: String? = null,
    val total: Long? = null,
    val completed: Long? = null,
)

private const val TEMPERATURE = "temperature"
private const val LOW_TEMPERATURE = 0.1

private const val NUM_GPU = "num_gpu"
private const val NUM_THREAD = "num_thread"

/**
 * Transformer layers offloaded to the GPU for `qwen2.5:3b`, which has 36 layers
 * in total — 26 of 36 lands at ~72%, inside the ~70–75% utilization band that
 * avoids full-GPU spikes, thermal throttling and system UI lag. The rest of the
 * layers evaluate on CPU. Revisit if the default model changes: the ratio, not
 * the absolute count, is the contract.
 */
private const val GPU_LAYERS_QWEN_3B = 26.0

/** CPU threads for the non-offloaded layers and tokenizer work. */
private const val INFERENCE_THREADS = 4.0
