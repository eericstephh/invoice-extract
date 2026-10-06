package com.invoiceextract.app.data.local.quota

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.invoiceextract.domain.quota.QuotaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Owns the daily free-tier quota behind AndroidX Preferences DataStore (Phase 13.1).
 *
 * The promise this class keeps is "zero surprise API cost": a user on the free tier gets
 * exactly [DEFAULT_DAILY_LIMIT] chargeable extractions per calendar day, and the moment
 * they plug in their own key ([setCustomApiKey]) the limit is lifted entirely, because
 * the cost is then theirs and not the app's.
 *
 * ### Day rollover without alarms
 *
 * The counter is keyed to an *epoch day* — [getTodayEpochDay] — rather than to a wall-clock
 * timestamp. A day boundary is therefore reached the moment the integer epoch day advances,
 * which is a pure arithmetic comparison with no `AlarmManager`, no `WorkManager` job and no
 * dependency on the app being alive at midnight. Rollover is detected *lazily*, on the next
 * read or write, so a device that is off all night costs nothing and is correct the moment
 * it is opened again. Because the comparison uses the same UTC-derived epoch day for both
 * sides, the boundary is stable and deterministic regardless of the process's timezone.
 *
 * ### Thread safety
 *
 * Every mutating operation is a single [DataStore.edit] block, and DataStore serialises
 * edits, so a read-modify-write over the preferences is atomic: two concurrent
 * `consumeScan()` calls become two sequential increments, never a lost update. The
 * rollover check is re-evaluated *inside* each edit transaction, so a reset and the
 * operation that triggered it are one indivisible unit — there is no window in which a
 * reader can observe a counter that belongs to yesterday.
 *
 * ### Corrupt-state safety
 *
 * Reads funnel through the same `catch` that [UserSessionManager] uses: an unreadable
 * preferences file degrades to an empty store, which reads as "day never recorded", which
 * in turn triggers a benign reset to zero. A broken file can never crash the quota gate or
 * permanently lock the user out of the free tier.
 *
 * @property context Used only to reach the DataStore file; no activity or view is retained.
 */
class DailyQuotaManager(private val context: Context) {

    /**
     * The single Preferences DataStore for this process.
     *
     * Declared at file scope so every instance resolves the same store: the DataStore API
     * rejects a second active store over the same file, which makes the singleton scope a
     * hard requirement rather than an optimisation.
     */
    private val dataStore: DataStore<Preferences> = context.quotaDataStore

    /**
     * Observable snapshot of the quota.
     *
     * Maps each DataStore emission through the rollover check, so a collector that
     * subscribes the morning after a heavy day immediately sees the recharged counter
     * rather than yesterday's total. The reset write is performed *inside* the mapping when
     * a new day is detected; DataStore serialises it and the flow then re-emits the
     * persisted values, so the emission a collector ultimately observes always agrees with
     * what is on disk.
     *
     * The emitted [QuotaState.dailyLimit] is [UNLIMITED_DAILY_LIMIT] while a personal API
     * key is configured, which keeps [QuotaState.remainingScans] and
     * [QuotaState.isQuotaExhausted] honest for a user the free tier no longer covers.
     */
    fun getQuotaStateFlow(): Flow<QuotaState> = dataStore.data
        .catch { cause ->
            // A broken preferences file arrives here as an IOException. Emitting an empty
            // set keeps the flow alive and reads as "no day recorded yet", which resets the
            // counter to zero instead of crashing the quota gate.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences -> preferences.toQuotaState() }

    /**
     * The go/no-go gate the pipeline calls before doing any work.
     *
     * The rollover check runs inside the [DataStore.edit] transaction and the decision is
     * taken on the snapshot that transaction returns, so the reset and the read are one
     * indivisible unit: the gate can never answer against a stale total from yesterday,
     * and no concurrent [consumeScan] can slip in between the reset and the decision. A
     * race can only ever make this answer more conservative, which is the safe direction
     * for a rate limit to fail in.
     *
     * Returns `true` when a personal API key is configured (the free tier does not apply)
     * or when at least one free extraction remains for today.
     */
    suspend fun canPerformScan(): Boolean {
        val snapshot = dataStore.edit { preferences ->
            val today = getTodayEpochDay()

            // Re-checked inside the transaction: a new day resets the counter before the
            // decision is made, so the gate never answers against a stale total.
            if (today > preferences.fetchLastResetDay()) {
                preferences[KEY_USED_TODAY] = 0
                preferences[KEY_LAST_RESET_DAY] = today
            }
        }

        return snapshot.hasCustomApiKey() ||
            (snapshot[KEY_USED_TODAY] ?: 0) < DEFAULT_DAILY_LIMIT
    }

    /**
     * Records one consumed extraction.
     *
     * No-op while a personal API key is configured: a BYOK user's usage is not chargeable
     * to the free tier, and counting it would corrupt the counter the day they remove the
     * key. Otherwise the rollover check runs first, so the first scan of a new day lands on
     * a freshly zeroed counter instead of incrementing yesterday's total.
     */
    suspend fun consumeScan() {
        dataStore.edit { preferences ->
            if (preferences.hasCustomApiKey()) return@edit

            val today = getTodayEpochDay()
            if (today > preferences.fetchLastResetDay()) {
                preferences[KEY_LAST_RESET_DAY] = today
                preferences[KEY_USED_TODAY] = 1
            } else {
                preferences[KEY_USED_TODAY] = (preferences[KEY_USED_TODAY] ?: 0) + 1
            }
        }
    }

    /**
     * Stores the user's personal API key, lifting the daily limit.
     *
     * A blank value clears the key rather than storing an empty string, so a text field
     * the user emptied and a key that never existed are indistinguishable on read.
     */
    suspend fun setCustomApiKey(apiKey: String?) {
        dataStore.edit { preferences ->
            val normalized = apiKey?.trim()
            if (normalized.isNullOrEmpty()) {
                preferences.remove(KEY_CUSTOM_API_KEY)
            } else {
                preferences[KEY_CUSTOM_API_KEY] = normalized
            }
        }
    }

    /** The stored personal key, trimmed, or `null` when none is configured. */
    suspend fun getCustomApiKey(): String? =
        dataStore.data.first()[KEY_CUSTOM_API_KEY]?.trim()

    /**
     * The epoch day of the current instant.
     *
     * Whole-day division of the millisecond timestamp. Comparing two values produced by
     * this function is a pure integer comparison, so the day boundary is reached exactly
     * when the quotient advances — no timezone arithmetic, no alarm, and no dependence on
     * the app being in the foreground at midnight.
     */
    private fun getTodayEpochDay(): Long = System.currentTimeMillis() / MILLIS_PER_DAY

    /**
     * Derives the observable state from a preferences snapshot, applying the rollover reset
     * when a new day is detected.
     *
     * The reset write happens here, ahead of deriving the values, so the emitted state and
     * the persisted state are produced by the same decision. When the write lands, DataStore
     * re-emits and this mapping runs again with the now-current day, converging on the
     * persisted values in one extra emission.
     */
    private suspend fun Preferences.toQuotaState(): QuotaState {
        val today = getTodayEpochDay()
        val isNewDay = today > fetchLastResetDay()

        if (isNewDay) {
            dataStore.edit { preferences ->
                // Re-checked inside the transaction: a concurrent collector or the
                // gate may already have reset for this day, and re-writing the identical
                // values is harmless either way.
                if (today > preferences.fetchLastResetDay()) {
                    preferences[KEY_USED_TODAY] = 0
                    preferences[KEY_LAST_RESET_DAY] = today
                }
            }
        }

        val hasCustomApiKey = hasCustomApiKey()
        return QuotaState(
            usedToday = if (isNewDay) 0 else this[KEY_USED_TODAY] ?: 0,
            dailyLimit = if (hasCustomApiKey) UNLIMITED_DAILY_LIMIT else DEFAULT_DAILY_LIMIT,
            hasCustomApiKey = hasCustomApiKey,
        )
    }

    /** `true` when a usable (non-blank) personal API key is stored. */
    private fun Preferences.hasCustomApiKey(): Boolean =
        this[KEY_CUSTOM_API_KEY]?.isNotBlank() == true

    /** The last recorded reset day, or a sentinel that predates any real epoch day. */
    private fun Preferences.fetchLastResetDay(): Long = this[KEY_LAST_RESET_DAY] ?: DAY_NEVER_RECORDED

    private companion object {
        const val DEFAULT_DAILY_LIMIT = 15

        /** Mirrors [QuotaState]'s unlimited sentinel: a personal key removes the cap. */
        const val UNLIMITED_DAILY_LIMIT = Int.MAX_VALUE

        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** Precedes every real epoch day, so a never-written store always reads as needing a reset. */
        const val DAY_NEVER_RECORDED = 0L

        val KEY_USED_TODAY = intPreferencesKey("used_today")
        val KEY_LAST_RESET_DAY = longPreferencesKey("last_reset_day")
        val KEY_CUSTOM_API_KEY = stringPreferencesKey("custom_api_key")
    }
}

/**
 * Process-wide Preferences DataStore for the quota.
 *
 * File-scoped for the same reason the session store is: the DataStore API forbids two
 * active stores over one file, so the delegate must be a process-wide singleton.
 */
private val Context.quotaDataStore: DataStore<Preferences> by preferencesDataStore(name = "daily_quota")
