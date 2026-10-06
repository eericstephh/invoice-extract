package com.invoiceextract.domain.quota

/**
 * Snapshot of the daily free-tier usage, used to keep the "zero external API cost"
 * promise visible to the user.
 *
 * The free tier resets every calendar day (Iran local time). When the user supplies
 * their own API key the daily limit is effectively lifted, which is expressed by
 * [hasCustomApiKey] and should be reflected by [dailyLimit] being set to
 * [UNLIMITED] by the owner of this state.
 *
 * @property usedToday       Number of chargeable extractions already consumed today.
 * @property dailyLimit      Number of free extractions allowed per day.
 * @property hasCustomApiKey `true` when the user configured a personal API key, meaning
 *                           the free tier no longer applies.
 */
data class QuotaState(
    val usedToday: Int = 0,
    val dailyLimit: Int,
    val hasCustomApiKey: Boolean = false,
) {

    /**
     * Remaining free extractions today. Derived from [dailyLimit] and [usedToday] so the
     * three values can never drift out of sync, and floored at `0` so an over-limit state
     * is reported as exhausted rather than as a negative number.
     */
    val remainingScans: Int
        get() = (dailyLimit - usedToday).coerceAtLeast(MIN_REMAINING)

    /** `true` when no free extraction is left for the current day. */
    val isQuotaExhausted: Boolean
        get() = remainingScans <= MIN_REMAINING

    private companion object {
        const val MIN_REMAINING = 0

        /** Sentinel used for [dailyLimit] when a custom API key removes the cap. */
        const val UNLIMITED = Int.MAX_VALUE
    }
}
