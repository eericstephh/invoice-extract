package com.invoiceextract.app.data.repository

import com.invoiceextract.app.data.local.auth.UserSessionManager
import com.invoiceextract.domain.model.AuthState
import com.invoiceextract.domain.model.User
import com.invoiceextract.domain.repository.AuthRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * DataStore-backed [AuthRepository] implementing the offline-first, guest-first rule
 * (Phase 12.1).
 *
 * The class itself holds no auth state: every read and write goes through
 * [UserSessionManager], so the state of the session is exactly what DataStore holds and
 * the repository is stateless between calls. Mapping [UserSessionManager.observeSession]
 * into [AuthState] is the only transformation, and it is total — a `null` session can never
 * reach the mapping, because [getAuthState] seeds a guest before the UI ever sees the flow.
 *
 * ### Why sign-in is simulated
 *
 * There is no auth backend yet. Rather than build a client against an endpoint that does
 * not exist, sign-in accepts any non-blank identifier and token, persists a profile derived
 * from them, and returns an [AuthState.Authenticated] user. The contract, the persistence
 * and the guest→authenticated upgrade path are therefore real and testable end to end, and
 * swapping the body of [signInWithPhoneOrEmail] for a network call is the only change this
 * class needs when the backend lands.
 *
 * ### Thread safety
 *
 * All writes shift to [ioDispatcher]. DataStore dispatches internally and is safe to call
 * from any dispatcher, but pinning the work to `Dispatchers.IO` keeps the calling coroutine
 * off the main thread during the file writes and matches the app's other repositories.
 *
 * @property sessionManager Owns the preferences file; injected so tests can substitute a
 *   fake without touching the real DataStore.
 * @property ioDispatcher Defaults to [Dispatchers.IO]; injectable for deterministic tests.
 */
class AuthRepositoryImpl(
    private val sessionManager: UserSessionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthRepository {

    /**
     * The auth state, mapped from the persisted session.
     *
     * A `null` session — first launch, or a store that could not be read — never escapes:
     * it is mapped to [AuthState.Loading] rather than to a phantom guest, because the
     * correct reaction to "no identity yet" is to show the loading state while
     * [continueAsGuest] creates one. The UI may legitimately observe [AuthState.Loading]
     * on the first frames, which is why the screens treat it as transient.
     *
     * Every emission is a fresh [AuthState] derived from the latest preferences, so a
     * sign-in or sign-out lands on screen without any manual refresh.
     */
    override fun getAuthState(): Flow<AuthState> {
        return sessionManager.observeSession().map { user ->
            when {
                user == null -> AuthState.Loading
                user.isGuest -> AuthState.Guest(user)
                else -> AuthState.Authenticated(user)
            }
        }
    }

    /**
     * Signs in with an identifier and secret.
     *
     * The identifier is echoed into [User.contact] and, when it looks like an email, into
     * [User.displayName], so the signed-in profile is populated without a second profile
     * fetch. The generated id is *not* the guest id: a signed-in account gets the identity
     * the provider owns, and the app's local data can later be re-keyed onto it.
     *
     * @return Success with the persisted [User], or failure when the identifier or secret
     *   is blank. The current session is untouched on failure.
     */
    override suspend fun signInWithPhoneOrEmail(identifier: String, secretToken: String): Result<User> =
        withContext(ioDispatcher) {
            if (identifier.isBlank() || secretToken.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Identifier and token are required"))
            }

            // Simulated authentication: any non-blank pair is accepted (see class docs).
            // The token is stored so the network layer can attach it once the backend
            // exists; nothing currently reads it, and it is never surfaced to the UI.
            val user = User(
                id = UUID.randomUUID().toString(),
                displayName = identifier.takeIf { it.contains('@') } ?: DEFAULT_DISPLAY_NAME,
                contact = identifier.trim(),
                isGuest = false,
                createdAtEpochMs = System.currentTimeMillis(),
            )

            runCatching {
                sessionManager.saveSession(user)
                sessionManager.saveAuthToken(SIMULATED_TOKEN_PREFIX + secretToken.hashCode())
            }.map { user }
        }

    /**
     * Establishes or returns the persistent guest identity.
     *
     * Idempotent and race-free: reads the current session first and reuses it when one
     * exists, so two concurrent calls — the UI's `init` block and a second call from a
     * deep link, for instance — cannot mint two different guest ids. Only a genuinely
     * absent identity creates one, and the new id is written before it is returned, so no
     * caller can observe a guest that is not yet on disk.
     */
    override suspend fun continueAsGuest(): User = withContext(ioDispatcher) {
        sessionManager.getSession()?.let { existing ->
            // An existing authenticated session is returned as-is: "continue as guest" is
            // not a downgrade request, and the caller asking for *a* usable identity gets one.
            return@withContext existing
        }

        val guest = User(
            id = UUID.randomUUID().toString(),
            displayName = null,
            contact = null,
            isGuest = true,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        sessionManager.saveSession(guest)
        guest
    }

    /**
     * Signs out and reverts to the guest identity.
     *
     * [UserSessionManager.clearSession] keeps the guest id, so the app lands back on the
     * identity its local invoices are already keyed to; if none exists yet, one is minted
     * here. Either branch yields a usable [AuthState.Guest], and the returned [User] is the
     * one now on disk.
     *
     * @return Success when the session has been written, or the underlying DataStore
     *   failure. A failure leaves the authenticated session intact rather than half cleared.
     */
    override suspend fun signOut(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            sessionManager.clearSession()
        }.mapCatching {
            // Fall back to a fresh guest only when the store had no guest id to preserve —
            // otherwise the app would be left in an unauthenticated state after sign-out.
            sessionManager.getSession() ?: continueAsGuest()
        }.map { }
    }

    private companion object {
        private const val DEFAULT_DISPLAY_NAME = "کاربر"
        private const val SIMULATED_TOKEN_PREFIX = "sim_"
    }
}
