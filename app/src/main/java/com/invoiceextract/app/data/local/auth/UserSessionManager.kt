package com.invoiceextract.app.data.local.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.invoiceextract.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persistence for the user session, backed by AndroidX Preferences DataStore (Phase 12.1).
 *
 * The session is deliberately stored as a handful of flat keys rather than a serialized
 * object: there is no schema to version, and reading any single key stays valid when
 * another one is missing or corrupt. That is the whole recovery story — a future field is
 * just a new key with a default, and a half-written file degrades to "guest" instead of
 * crashing the session read.
 *
 * **The guest identity is permanent.** `KEY_USER_ID` is written on the first launch and is
 * deliberately *never* deleted: the app must be able to associate local invoices with a
 * user identity at all times, even before any login. [clearSession] wipes the auth token
 * and the profile but keeps the guest id, so a sign-out lands back on the same anonymous
 * identity the app has been using all along.
 *
 * **Corrupt-state safety.** Every read is funnelled through [readUser], which treats an
 * empty, unreadable or partially-written store as "no session yet" and returns `null`.
 * DataStore's own `catch` also converts an [IOException] from a broken preferences file
 * into an empty emission, so the flow never terminates with an exception the UI would have
 * to handle as a crash. The class is a Koin `single` and holds the [Context] only to reach
 * the DataStore file; no activity or view is referenced.
 */
class UserSessionManager(private val context: Context) {

    /**
     * The single Preferences DataStore for this process.
     *
     * The delegate is top-level in the file, so the store is a process-wide singleton: two
     * instances of this class would share one store instead of opening two files.
     */
    private val dataStore: DataStore<Preferences> = context.dataStore

    /**
     * The persisted user, or `null` when nothing has been written yet (first launch) or
     * the store is unreadable.
     *
     * Each emission is mapped through [readUser], so an incomplete or corrupt preferences
     * file degrades to `null` rather than propagating an exception to the collector.
     */
    fun observeSession(): Flow<User?> = dataStore.data
        .catch { cause ->
            // A broken preferences file surfaces here as an IOException. Emitting an empty
            // preferences set keeps the flow alive: the caller sees "no session" and the
            // app falls back to a fresh guest identity instead of crashing on boot.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences -> readUser(preferences) }

    /**
     * Persists [user] as the current session, replacing any previous one.
     *
     * Writes every field in one `edit` block, so a mid-write process kill leaves either the
     * complete previous session or the complete new one — never a half-mixed identity.
     */
    suspend fun saveSession(user: User) {
        dataStore.edit { preferences ->
            preferences[KEY_USER_ID] = user.id
            user.displayName?.let { preferences[KEY_DISPLAY_NAME] = it }
            user.contact?.let { preferences[KEY_CONTACT] = it }
            preferences[KEY_IS_GUEST] = user.isGuest
            preferences[KEY_CREATED_AT] = user.createdAtEpochMs
        }
    }

    /**
     * Persists an auth token alongside the current profile, marking the session as
     * non-guest.
     *
     * The token is stored in its own key and never logged or surfaced to the UI; it is the
     * credential the network layer attaches to authenticated requests.
     */
    suspend fun saveAuthToken(token: String) {
        dataStore.edit { preferences -> preferences[KEY_AUTH_TOKEN] = token }
    }

    /**
     * Wipes the authenticated profile and token, keeping the guest id.
     *
     * `KEY_USER_ID` is intentionally preserved: the guest identity is permanent, and the
     * invoices already saved under it stay associated with it after a sign-out.
     * `KEY_CREATED_AT` is preserved too, as the creation time of that guest identity.
     */
    suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_AUTH_TOKEN)
            preferences.remove(KEY_DISPLAY_NAME)
            preferences.remove(KEY_CONTACT)
            preferences[KEY_IS_GUEST] = true
        }
    }

    /**
     * Returns the current session value, suspending until the first read completes.
     *
     * A one-shot read for callers that need the user once rather than observing changes;
     * it goes through the same corrupt-state handling as [observeSession].
     */
    suspend fun getSession(): User? = observeSession().first()

    /**
     * Extracts a [User] from a preferences snapshot, or `null` when the store holds no
     * usable identity.
     *
     * The guard is [KEY_USER_ID]: it is the first key written on first launch and the last
     * one removed, so a blank id means the store is empty or was truncated — not that the
     * user is merely "logged out". A blank id can never produce a guest user, because a
     * guest with no id cannot own records.
     */
    private fun readUser(preferences: Preferences): User? {
        val id = preferences[KEY_USER_ID]?.takeIf { it.isNotBlank() } ?: return null

        return User(
            id = id,
            displayName = preferences[KEY_DISPLAY_NAME],
            contact = preferences[KEY_CONTACT],
            // Absent means "not written yet"; default to guest, the safe identity.
            isGuest = preferences[KEY_IS_GUEST] ?: true,
            createdAtEpochMs = preferences[KEY_CREATED_AT] ?: DEFAULT_CREATED_AT,
        )
    }

    private companion object {
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_CONTACT = stringPreferencesKey("contact")
        private val KEY_IS_GUEST = booleanPreferencesKey("is_guest")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_CREATED_AT = longPreferencesKey("created_at_epoch_ms")

        private const val DEFAULT_CREATED_AT = 0L
    }
}

/**
 * Process-wide Preferences DataStore for the user session.
 *
 * Declared at file scope so every [UserSessionManager] instance resolves the same store:
 * the DataStore API rejects a second active store over the same file, so the singleton
 * scope is a hard requirement, not an optimisation.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_session")
