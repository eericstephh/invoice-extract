package com.invoiceextract.domain.model

/**
 * The app's authentication status as an observable value (Phase 12.1).
 *
 * A sealed interface rather than a nullable [User]: "loading", "authenticated" and
 * "guest" are mutually exclusive states, and making them distinct types means a `when`
 * over [AuthState] is exhaustive at compile time and an in-between state such as
 * "authenticated but with no user" is unrepresentable instead of merely checked for.
 *
 * **Guest is a real state, not the absence of one.** The offline-first contract is that
 * the app is fully usable without a network login, so [Guest] carries a complete [User]
 * with a persistent id and nothing is deferred or half-initialised. Consumers that treat
 * guest and authenticated alike read [user]; consumers that need the distinction match
 * on the type.
 *
 * Emitted by [com.invoiceextract.domain.repository.AuthRepository.getAuthState] as a cold
 * [kotlinx.coroutines.flow.Flow], so a session change propagates to every screen holding
 * a subscription without any polling.
 */
sealed interface AuthState {

    /**
     * The session store has not been read yet. Transient: the repository resolves this to
     * [Authenticated] or [Guest] on the first DataStore emission, so the UI only ever
     * observes it while the preferences file is being loaded.
     */
    data object Loading : AuthState

    /**
     * The user signed in with a real account.
     *
     * @property user The authenticated identity. Never a guest by construction.
     */
    data class Authenticated(val user: User) : AuthState

    /**
     * The user is working under the local anonymous identity.
     *
     * @property user The guest identity. [User.isGuest] is `true` by construction, and the
     *   [User.id] is stable across launches.
     */
    data class Guest(val user: User) : AuthState
}
