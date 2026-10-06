package com.invoiceextract.domain.quota

/**
 * Raised when the user has spent every free extraction allowed for the current day
 * (Phase 13.1).
 *
 * Carries a ready-to-show Persian message rather than a code, because the quota gate sits
 * deep in the pipeline and its only consumer is the UI — the message is part of the
 * *contract* of hitting the limit, not an incidental detail of where it was detected.
 * The default argument keeps the message in one place, so the exception is always
 * constructed with a user-facing sentence even from a plain `QuotaExceededException()`.
 *
 * The free tier recharges at the next calendar-day rollover (see
 * [com.invoiceextract.app.data.local.quota.DailyQuotaManager]), so this failure is
 * transient by construction: telling the user "try again tomorrow" is the accurate and
 * complete remedy, unlike a network or validation failure which asks for a retry now.
 */
class QuotaExceededException(
    message: String = DEFAULT_MESSAGE,
) : Exception(message) {

    private companion object {
        const val DEFAULT_MESSAGE =
            "سهمیه رایگان امروز شما به پایان رسیده است. فردا مجدداً شارژ می‌شود."
    }
}
