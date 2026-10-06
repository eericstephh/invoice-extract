package com.invoiceextract.domain.repository

import com.invoiceextract.domain.model.AuthState
import com.invoiceextract.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Authentication and session contract (Phase 12.1).
 *
 * Pure Kotlin: no DataStore, no Android, no network client — the `:app` layer supplies
 * the implementation. The contract is shaped around the offline-first rule below, so a
 * caller can depend on it without knowing how or whether a session is persisted.
 *
 * **Offline-first / guest-first.** Nothing in this contract may block the app behind a
 * network login. A usable [AuthState.Guest] is available from the first launch on, and
 * [continueAsGuest] is always a valid path, so a device with no connectivity reaches a
 * fully working state. Sign-in is an *upgrade* of the identity, never a gate in front of
 * the features.
 */
interface AuthRepository {

    /**
     * The current authentication state, as a cold [Flow] that replays the latest value to
     * every new subscriber and re-emits on any session change.
     *
     * The first emission is [AuthState.Loading] until the session store has been read;
     * every subsequent emission is [AuthState.Authenticated] or [AuthState.Guest]. A
     * caller that needs a settled state can `first()` after filtering out [Loading].
     */
    fun getAuthState(): Flow<AuthState>

    /**
     * Signs the user in with a phone number or email plus a secret (OTP, password or
     * token as the backend requires).
     *
     * @param identifier   The phone number or email identifying the account.
     * @param secretToken  The secret proving ownership of [identifier].
     * @return [Result.success] with the now-authenticated [User] — also persisted, so the
     *   session survives process death — or [Result.failure] carrying a typed cause when
     *   the credentials are rejected, the identifier is empty, or the network is
     *   unreachable. Failure never disturbs the current session: a guest stays a guest.
     */
    suspend fun signInWithPhoneOrEmail(identifier: String, secretToken: String): Result<User>

    /**
     * Establishes — or returns — the local anonymous identity.
     *
     * On the first launch this generates and persists a guest [User] whose UUID is stable
     * for the lifetime of the install; on later launches it returns the existing one. The
     * returned [User] is always a valid identity with a non-blank [User.id], which is what
     * lets the app associate local invoices with a user from the very first run.
     */
    suspend fun continueAsGuest(): User

    /**
     * Signs the user out of the authenticated account.
     *
     * Clears the auth token and the profile, then reverts to the guest identity — keeping
     * the existing guest [User.id] where the app already has local data attached to it,
     * or minting a fresh one on the first ever sign-out. Either way the app lands back in
     * a usable [AuthState.Guest], never in an unauthenticated limbo.
     *
     * @return [Result.success], or [Result.failure] when the session store could not be
     *   written. A failure leaves the authenticated session in place rather than half
     *   clearing it.
     */
    suspend fun signOut(): Result<Unit>
}
