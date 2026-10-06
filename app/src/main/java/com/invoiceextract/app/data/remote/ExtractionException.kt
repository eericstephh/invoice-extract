package com.invoiceextract.app.data.remote

import kotlin.coroutines.cancellation.CancellationException

/**
 * The single typed failure the network extraction layer can produce (Phase 11.2).
 *
 * Every failure path of [RemoteInvoiceAiExtractor] — a dead socket, a 500 from the
 * worker, an unparseable body — is folded into one of these. The domain contract
 * returns `Result`, so callers switch on this type only when they want to, and never
 * have to know OkHttp's exception hierarchy.
 *
 * **Messages are Persian and user-facing.** They are the last stop before the UI, so
 * they must be readable by the person holding the phone; the originating [cause] is
 * kept for logging instead of being shown. Engineering detail (HTTP codes, exception
 * class names) never reaches the user.
 *
 * Sealed so a `when` over the subtypes is exhaustive: adding a new failure mode is a
 * compile error everywhere it is handled until every branch is covered.
 */
sealed class ExtractionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The OCR stage handed over blank text, so there is nothing to send the model.
     * Reported locally without a network round trip.
     */
    object EmptyInput : ExtractionException(MESSAGE_EMPTY_INPUT)

    /** The worker rejected [com.invoiceextract.app.core.network.NetworkConfig.CLIENT_SECRET_KEY]. */
    object Unauthorized : ExtractionException(MESSAGE_UNAUTHORIZED)

    /** The worker's rate limit (or the upstream provider's) was hit. Retryable after a wait. */
    object RateLimited : ExtractionException(MESSAGE_RATE_LIMITED)

    /**
     * The worker answered with a failure code.
     *
     * @property code The HTTP status, kept for logging and crash reports only.
     */
    class Server(val code: Int) : ExtractionException(MESSAGE_SERVER)

    /** The worker returned 200 but the body was empty, so no invoice can be built. */
    object EmptyResponse : ExtractionException(MESSAGE_EMPTY_RESPONSE)

    /**
     * Transport-level failure: no connectivity, a refused connection, or a read timeout
     * while waiting on LLM reasoning. Carries the original [cause] for diagnostics.
     */
    class Network(cause: Throwable) : ExtractionException(MESSAGE_NETWORK, cause)

    /** Like [Network], but surfaces the underlying exception class and message for diagnostics. */
    class NetworkErrorWithDetail(val detail: String) : ExtractionException("خطا در ارتباط با سرور: $detail")

    /** Anything the extractor did not classify, including a malformed JSON body. */
    class Unknown(cause: Throwable) : ExtractionException(MESSAGE_UNKNOWN, cause)

    private companion object {
        const val MESSAGE_EMPTY_INPUT = "متن استخراج‌شده از سند خالی است؛ چیزی برای پردازش وجود ندارد."
        const val MESSAGE_UNAUTHORIZED = "کلید دسترسی به سرور نامعتبر است."
        const val MESSAGE_RATE_LIMITED = "تعداد درخواست‌ها بیش از حد مجاز است. کمی بعد دوباره تلاش کنید."
        const val MESSAGE_SERVER = "خطا در برقراری ارتباط با سرور هوش مصنوعی."
        const val MESSAGE_EMPTY_RESPONSE = "پاسخ خالی از سرور هوش مصنوعی دریافت شد."
        const val MESSAGE_NETWORK = "خطا در برقراری ارتباط با سرور هوش مصنوعی. ارتباط اینترنت را بررسی کنید."
        const val MESSAGE_UNKNOWN = "خطای ناشناخته هنگام استخراج فاکتور."
    }
}

/**
 * True only for coroutine cancellation, which must never be reclassified as an
 * [ExtractionException]. Turning cancellation into an ordinary failure would break
 * structured concurrency: the caller's `viewModelScope` teardown is supposed to
 * unwind the whole pipeline, not turn into an error banner the user can see.
 */
internal fun Throwable.isCancellation(): Boolean = this is CancellationException
