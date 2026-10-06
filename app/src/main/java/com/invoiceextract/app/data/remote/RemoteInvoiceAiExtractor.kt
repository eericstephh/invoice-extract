package com.invoiceextract.app.data.remote

import android.content.Context
import android.provider.Settings
import com.invoiceextract.app.data.remote.dto.ExtractionRequestDto
import com.invoiceextract.app.data.remote.dto.ExtractionResponseDto
import com.invoiceextract.app.data.remote.dto.toDomain
import com.invoiceextract.app.core.network.NetworkConfig
import com.invoiceextract.domain.extractor.InvoiceAiExtractor
import com.invoiceextract.domain.model.Invoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Network-backed [InvoiceAiExtractor] talking to the Cloudflare Worker proxy
 * (Phase 11.2).
 *
 * The worker holds the LLM provider keys and the failover logic; this class only
 * needs to ship OCR text and read one JSON object back. That split is deliberate:
 * provider rotation happens server-side, so a Gemini-to-Groq switch is a worker
 * redeploy, not an app release through a store review cycle.
 *
 * **Non-blocking.** OkHttp's `enqueue` runs the call on its own dispatcher pool
 * and [suspendCancellableCoroutine] bridges the callback to a coroutine. The
 * coroutine is never parked on a thread while the request is in flight, and
 * cancellation of the collecting coroutine cancels the HTTP call — the pipeline's
 * `viewModelScope` teardown on screen exit drops the request instead of leaking it.
 *
 * **Errors.** Every failure mode surfaces as a typed [ExtractionException] inside
 * [Result.failure], matching the domain contract that callers must never be forced
 * to catch. The messages are Persian because they are shown verbatim to the user.
 */
class RemoteInvoiceAiExtractor(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) : InvoiceAiExtractor {

    private val jsonMediaType = JSON_MEDIA_TYPE.toMediaType()

    /**
     * The hardware identifier of this device ([Settings.Secure.ANDROID_ID]).
     *
     * Resolved lazily on the first request and cached: it never changes for the lifetime
     * of the install, so there is no reason to re-query the provider on every call. The
     * value is sent to the worker as the `X-Device-Id` header for per-device quota and
     * abuse accounting, and falls back to a sentinel when the provider is somehow
     * unavailable rather than failing the extraction.
     */
    private val deviceId: String by lazy {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "unknown_device"
    }

    override suspend fun extractFromText(ocrText: String): Result<Invoice> =
        withContext(Dispatchers.IO) {
            if (ocrText.isBlank()) {
                return@withContext Result.failure(ExtractionException.EmptyInput)
            }

            runCatching {
                val response = executeRequest(ocrText)
                handleResponse(response, ocrText)
            }.recoverCatching { cause ->
                // Cancellation is not a failure: the collecting scope (typically
                // viewModelScope) tore the pipeline down on screen exit, and that must
                // stay a cancellation instead of becoming an error banner.
                if (cause.isCancellation()) throw cause

                // One typed failure for the whole call graph: the use case and the
                // UI branch on ExtractionException, not on OkHttp's exception
                // hierarchy.
                throw when (cause) {
                    is ExtractionException -> cause
                    is IOException -> {
                        val detail = "${cause.javaClass.simpleName}: ${cause.message}"
                        ExtractionException.NetworkErrorWithDetail(detail)
                    }
                    else -> ExtractionException.Unknown(cause)
                }
            }
        }

    /**
     * Enqueues the request and suspends until the callback fires.
     *
     * `suspendCancellableCoroutine` is what makes cancellation work end to end:
     * on cancellation the block calls [Call.cancel], which closes the socket and
     * fails the callback with a cancelled exception instead of leaving the
     * connection to time itself out.
     */
    private suspend fun executeRequest(ocrText: String): Response {
        val body = json.encodeToString(ExtractionRequestDto(ocrText)).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(NetworkConfig.EXTRACTION_ENDPOINT)
            .header(NetworkConfig.CLIENT_KEY_HEADER, NetworkConfig.CLIENT_SECRET_KEY)
            .addHeader("X-Device-Id", deviceId)
            .post(body)
            .build()

        return suspendCancellableCoroutine { continuation ->
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
    }

    /**
     * Turns one HTTP response into a domain [Invoice]. Consumes and closes the
     * response body in every branch so the connection returns to the pool.
     */
    private suspend fun handleResponse(response: Response, ocrText: String): Invoice {
        return response.use { closed ->
            when {
                closed.code == HTTP_UNAUTHORIZED ->
                    throw ExtractionException.Unauthorized

                closed.code == HTTP_TOO_MANY_REQUESTS ->
                    throw ExtractionException.RateLimited

                closed.code in HTTP_SERVER_ERROR_RANGE ->
                    throw ExtractionException.Server(closed.code)

                !closed.isSuccessful ->
                    throw ExtractionException.Server(closed.code)

                else -> parseBody(closed, ocrText)
            }
        }
    }

    private fun parseBody(response: Response, ocrText: String): Invoice {
        val raw = response.body?.string()
        if (raw.isNullOrBlank()) throw ExtractionException.EmptyResponse

        return json
            .decodeFromString<ExtractionResponseDto>(raw)
            .toDomain(rawOcrText = ocrText)
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY_REQUESTS = 429
        val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}
