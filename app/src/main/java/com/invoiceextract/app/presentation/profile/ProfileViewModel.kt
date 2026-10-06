package com.invoiceextract.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.invoiceextract.domain.model.AuthState
import com.invoiceextract.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the Profile & Authentication bottom sheet (Phase 12.2).
 *
 * A thin view-model over [AuthRepository]: it mirrors the repository's auth-state flow into
 * [uiState] and turns the two credential actions into state transitions. It holds no
 * session of its own — every read and write goes through the repository — so the sheet is
 * exactly a function of what DataStore persists, and a session change started elsewhere in
 * the app lands here without any manual refresh.
 *
 * ### Auto-guest bootstrap
 *
 * [init] never leaves the sheet on [AuthState.Loading]. The flow emits `Loading` only while
 * the session store has no identity for the app yet; the moment that is observed,
 * [AuthRepository.continueAsGuest] establishes the persistent anonymous identity, which
 * makes the flow re-emit a settled `Guest`. The call is idempotent and preserves an
 * existing authenticated session, so it is safe to fire on every `Loading` emission and
 * guarantees the app is never stuck behind a spinner with no identity.
 *
 * ### Threading
 *
 * Both credential actions suspend inside [AuthRepository], which shifts its writes to
 * `Dispatchers.IO`; collecting the state flow in [viewModelScope] scopes everything to the
 * sheet's lifetime, so dismissing it cancels any in-flight sign-in.
 *
 * @property authRepository The offline-first auth contract from `:domain`.
 */
class ProfileViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.getAuthState().collect { state ->
                _uiState.update { it.copy(authState = state) }

                // "Loading" means the store has not resolved an identity yet. Establishing
                // the guest one here is what guarantees a settled state always follows: the
                // write makes the flow re-emit, and `continueAsGuest` returns (never
                // overwrites) an existing authenticated session, so this can neither mint a
                // spurious guest nor leave the sheet spinning forever.
                if (state is AuthState.Loading) {
                    authRepository.continueAsGuest()
                }
            }
        }
    }

    /**
     * Signs the user in with a phone number or email.
     *
     * Validates the pair up front so a blank field is reported as a local message rather
     * than round-tripped through the repository as a failure. [isSubmitting] flips on
     * synchronously and off when the call lands, disabling the button for the whole
     * duration. Success needs no state work here: the persisted session makes the collected
     * flow re-emit [AuthState.Authenticated], which is the single source of truth for the
     * sheet's body.
     *
     * @param identifier Phone number or email identifying the account.
     * @param secret     Password / OTP proving ownership of [identifier].
     */
    fun signIn(identifier: String, secret: String) {
        if (identifier.isBlank() || secret.isBlank()) {
            _uiState.update { it.copy(errorMessage = MESSAGE_BLANK_CREDENTIALS) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
            authRepository.signInWithPhoneOrEmail(identifier.trim(), secret.trim())
                .onSuccess {
                    // The collected auth-state flow re-emits Authenticated; only the submit
                    // flag and any stale error need clearing here.
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = error.localizedMessage ?: MESSAGE_SIGN_IN_FAILED,
                        )
                    }
                }
        }
    }

    /**
     * Signs the user out and restores the guest identity.
     *
     * [isSubmitting] flips on so the destructive button disables itself for the duration.
     * On success the collected flow re-emits [AuthState.Guest] — the repository keeps the
     * guest id so local invoices stay owned by the same identity — and [continueAsGuest] is
     * called explicitly to make the restored guest independent of that implementation
     * detail. A failure leaves the authenticated session in place and reports a message
     * rather than half-clearing the identity.
     */
    fun signOut() {
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

        viewModelScope.launch {
            authRepository.signOut()
                .onSuccess {
                    authRepository.continueAsGuest()
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = MESSAGE_SIGN_OUT_FAILED)
                    }
                }
        }
    }

    /** Clears [ProfileUiState.errorMessage]; wired to the error banner's dismiss action. */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private companion object {
        private const val MESSAGE_BLANK_CREDENTIALS =
            "شماره موبایل/ایمیل و کلمه عبور را وارد کنید."

        private const val MESSAGE_SIGN_IN_FAILED =
            "ورود ناموفق بود. دوباره تلاش کنید."

        private const val MESSAGE_SIGN_OUT_FAILED =
            "خروج از حساب ناموفق بود."
    }
}
